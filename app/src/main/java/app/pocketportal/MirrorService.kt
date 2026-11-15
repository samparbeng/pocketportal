package app.pocketportal

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Captures the screen with MediaProjection and serves it to the headset over
 * Wi-Fi through [MirrorServer]. Runs as a foreground service so capture keeps
 * going while other apps are in front.
 */
class MirrorService : Service() {

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "mirroring"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "app.pocketportal.STOP"
        private const val MIN_BITRATE = 1_500_000
        /** Video packets waiting to be sent before the link counts as congested (~50 ms at 60 fps). */
        private const val CONGESTED_QUEUE = 3

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MirrorService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var captureThread: HandlerThread
    private lateinit var handler: Handler
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var server: MirrorServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audio: AudioStreamer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var advertising = false

    private val nsdListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) { advertising = true }
        override fun onServiceUnregistered(info: NsdServiceInfo) { advertising = false }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
            Log.w(TAG, "mDNS register failed: $code")
        }
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
    }
    private lateinit var quality: Quality

    // Capture-thread state. The virtual display renders into exactly one surface:
    // a hardware video encoder's when every viewer can decode its codec (H.265
    // first, then H.264), otherwise an ImageReader whose frames are sent as JPEG.
    /** The codec being streamed, or null for JPEG. */
    private var codec: VideoCodec? = null
    private val brokenCodecs = mutableSetOf<VideoCodec>()
    private var encoder: VideoEncoder? = null
    /** Samsung DeX on its own display, streamed instead of the phone's screen; see [DexSession]. */
    private var dex: DexSession? = null
    private var dexBitrate = 0
    /** DeX failed once this session; don't keep retrying. */
    private var dexFailed = false
    /** The display the input helper was last pointed at. */
    @Volatile private var inputDisplay = 0
    /** Adaptive bitrate: what the network can currently carry, between [MIN_BITRATE] and the preset's. */
    private var targetBitrate = 0
    private var calmTicks = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var padded: Bitmap? = null
    private var dirty = false
    private var lastSentAt = 0L
    private var sendScheduled = false
    private val jpeg = ByteArrayOutputStream(256 * 1024)

    private val sendLatest = Runnable {
        sendScheduled = false
        encodeAndSend()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        // The user tapped "Stop sharing" in the status bar, or another app took over capture.
        override fun onStop() = stopSelf()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateCaptureSize()
        }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MirrorState.init(this)
        captureThread = HandlerThread("pocketportal-capture").also { it.start() }
        handler = Handler(captureThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be in the foreground with the mediaProjection type before asking for the projection.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        if (projection != null) return START_NOT_STICKY

        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        if (data == null || resultCode != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            begin(resultCode, data)
            MirrorState.error.value = null
            MirrorState.running.value = true
        } catch (e: Exception) {
            Log.e(TAG, "could not start mirroring", e)
            MirrorState.error.value = when (e) {
                is IOException -> "Port ${MirrorState.PORT} is busy. Close other mirroring apps and try again."
                else -> "Could not start screen sharing: ${e.message}"
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(resultCode: Int, data: Intent) {
        quality = MirrorState.quality.value
        val page = assets.open("index.html").use { it.readBytes() }
        targetBitrate = quality.bitrate
        server = MirrorServer(
            port = MirrorState.PORT,
            redirectPort = MirrorState.REDIRECT_PORT,
            ssl = CertStore.sslContext(),
            page = page,
            authorize = MirrorState::isAuthorized,
            onConnected = { client ->
                sendHello()
                handler.post { onViewerJoined(client) }
            },
            onMessage = { text -> handleInput(JSONObject(text)) },
            onViewersChanged = {
                MirrorState.viewers.value = it
                handler.post { chooseMode() }
            },
            onNeedKeyframe = { encoder?.requestKeyframe() ?: dex?.requestKeyframe() },
        ).also { it.start() }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data) ?: error("permission was not granted")
        projection = mp
        mp.registerCallback(projectionCallback, handler)

        val metrics = realScreenMetrics(this)
        val (w, h) = scaledSize(metrics.widthPixels, metrics.heightPixels)
        captureWidth = w
        captureHeight = h
        val imageReader = newReader(w, h)
        reader = imageReader
        virtualDisplay = mp.createVirtualDisplay(
            "Pocket Portal", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler
        )
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        handler.postDelayed(adaptBitrate, 500)

        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "pocketportal")
            .also { it.acquire() }

        // Lets the Quest app list this phone without typing its address. The PIN is never advertised.
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        getSystemService(NsdManager::class.java).registerService(
            NsdServiceInfo().apply {
                serviceName = name
                serviceType = MirrorState.SERVICE_TYPE
                port = MirrorState.PORT
                // The headset app pins this certificate, so nobody else can pose as the phone.
                CertStore.fingerprint()?.let { setAttribute("fp", it) }
            },
            NsdManager.PROTOCOL_DNS_SD, nsdListener,
        )

        // Tell open viewers when remote touch is switched on or off on the phone.
        scope.launch { MirrorState.touchEnabled.drop(1).collect { sendHello() } }
        scope.launch { AdbBridge.injectorReady.drop(1).collect { sendHello() } }
        // With screen-off set up, touches go in through the shell, which also reaches
        // screens that ignore remote touch (Google sign-in, permission dialogs).
        AdbBridge.startInjector(this)

        // Sharing stops if the phone sleeps, and with its panel switched off nobody can
        // see it time out, so stay awake until sharing ends.
        @Suppress("DEPRECATION")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "pocketportal:sharing")
            .also { it.acquire() }
        PhoneScreen.apply(this, MirrorState.screenMode.value)

        if (MirrorState.sound.value && AudioStreamer.hasPermission(this)) {
            val streamer = AudioStreamer { chunk -> server?.broadcastAudio(chunk) }
            if (streamer.start(mp)) audio = streamer
        }
    }

    /**
     * A tap, swipe, key or text from the headset. Touches and keys go through the
     * shell input injector when it runs; text, and everything when it doesn't, through
     * the remote-touch accessibility service.
     */
    private fun handleInput(m: JSONObject) {
        // With DeX showing, input goes to its display, at its size.
        val dexDisplay = dex?.displayId ?: -1
        val (width, height) = if (dexDisplay >= 0) DexSession.WIDTH to DexSession.HEIGHT
        else realScreenMetrics(this).let { it.widthPixels to it.heightPixels }
        val target = maxOf(dexDisplay, 0)
        if (target != inputDisplay && AdbBridge.sendInput("display $target")) inputDisplay = target
        fun px(x: Double, y: Double) = "%.1f %.1f".format(
            java.util.Locale.US,
            x.coerceIn(0.0, 1.0) * (width - 1),
            y.coerceIn(0.0, 1.0) * (height - 1),
        )
        val line = when (m.optString("t")) {
            "tap" -> "tap ${px(m.getDouble("x"), m.getDouble("y"))}"
            "press" -> "press ${px(m.getDouble("x"), m.getDouble("y"))} ${m.optLong("ms", 700).coerceIn(1, 10_000)}"
            "swipe" -> {
                val pts = m.getJSONArray("pts")
                val coords = (0 until pts.length()).joinToString(" ") { i -> pts.getJSONArray(i).let { px(it.getDouble(0), it.getDouble(1)) } }
                "swipe ${m.optLong("ms", 300).coerceIn(1, 10_000)} $coords"
            }
            // Keys only when remote touch is off: its global actions also cover Quick settings.
            // On DeX they go to its display, which the accessibility service can't do.
            "key" -> if (TouchService.instance != null && dexDisplay < 0) null else when (m.optString("k")) {
                "back" -> "key 4"
                "home" -> "key 3"
                "recents" -> "key 187"
                "notifications" -> "key 83"
                else -> null
            }
            else -> null
        }
        if (line != null && AdbBridge.sendInput(line)) return
        TouchService.instance?.handle(m)
    }

    private fun sendHello() {
        server?.broadcastText(
            JSONObject()
                .put("type", "hello")
                .put("input", TouchService.instance != null || AdbBridge.injectorReady.value)
                .put("audio", audio != null)
                .put("rate", AudioStreamer.SAMPLE_RATE)
                .toString()
        )
    }

    private fun sendVideoConfig() {
        dex?.let { session ->
            val config = session.config ?: return
            server?.broadcastText(
                JSONObject(config.toString()).put("type", "video").put("fps", quality.videoFps).put("dex", true).toString()
            )
            return
        }
        val enc = encoder ?: return
        val codecString = enc.codecString ?: return
        server?.broadcastText(
            JSONObject()
                .put("type", "video")
                .put("codec", codecString)
                .put("name", enc.codec.label)
                .put("fps", quality.videoFps)
                .put("width", enc.width)
                .put("height", enc.height)
                .toString()
        )
    }

    /**
     * Adaptive bitrate, twice a second: back off quickly when a viewer's queue
     * builds up (the network can't keep up), creep back up after a few calm
     * seconds. Lower bitrate costs some sharpness but keeps the picture live.
     */
    private val adaptBitrate = object : Runnable {
        override fun run() {
            val srv = server ?: return
            val enc = encoder
            val session = dex
            if ((enc != null || session != null) && srv.hasViewers) {
                val (queued, dropped) = srv.takeCongestion()
                when {
                    dropped -> { targetBitrate /= 2; calmTicks = 0 }
                    queued > CONGESTED_QUEUE -> { targetBitrate = targetBitrate * 7 / 10; calmTicks = 0 }
                    queued <= 1 && ++calmTicks >= 4 -> { targetBitrate = targetBitrate * 11 / 10; calmTicks = 0 }
                }
                targetBitrate = targetBitrate.coerceIn(MIN_BITRATE, quality.bitrate)
                if (enc != null) enc.setBitrate(targetBitrate)
                else if (session != null && targetBitrate != dexBitrate) {
                    dexBitrate = targetBitrate
                    session.setBitrate(targetBitrate)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    /**
     * Fits the screen inside the quality preset's longest side. Hardware H.264
     * encoders want both sides in multiples of 16; JPEG only needs them even.
     */
    private fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1f, quality.maxDimension.toFloat() / maxOf(width, height))
        val align = if (codec != null) 16 else 2
        fun fit(side: Int) = maxOf(align, ((side * scale).toInt() + align / 2) / align * align)
        return fit(width) to fit(height)
    }

    private fun newReader(width: Int, height: Int) =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ onImageAvailable(it) }, handler)
        }

    private fun onViewerJoined(client: MirrorServer.Client) {
        if (codec != null && codec in client.codecs) {
            sendVideoConfig()
            encoder?.requestKeyframe()
            dex?.requestKeyframe()
        } else if (padded != null) {
            dirty = true
            encodeAndSend()
        }
    }

    /** The best codec every viewer can decode; one older browser drops everyone back to JPEG. */
    private fun chooseMode() {
        val viewers = server?.viewers ?: return
        val wanted = if (viewers.isEmpty()) null else VideoCodec.entries.firstOrNull { c ->
            c.available && c !in brokenCodecs && viewers.all { c in it.codecs }
        }
        if (wanted == codec) return
        codec = wanted
        attachSurface()
    }

    /** Rotation swaps width and height; resize the virtual display so the picture isn't squashed. */
    private fun updateCaptureSize() {
        val metrics = realScreenMetrics(this)
        val (w, h) = scaledSize(metrics.widthPixels, metrics.heightPixels)
        if (w == captureWidth && h == captureHeight) return
        attachSurface()
    }

    /**
     * With DeX chosen and a viewer that decodes video, streams DeX's display instead
     * of the phone's screen and pauses the phone capture. DeX stays up while viewers
     * come and go; it's restarted only if the codec has to change.
     */
    private fun showDex(display: VirtualDisplay): Boolean {
        if (!MirrorState.dex.value || !AdbBridge.paired.value || dexFailed) return false
        val wanted = codec
        if (wanted != null && dex?.codec != wanted) startDex(wanted)
        if (dex == null) return false
        val oldReader = reader
        val oldEncoder = encoder
        reader = null
        encoder = null
        display.surface = null
        oldReader?.close()
        oldEncoder?.release()
        return true
    }

    private fun startDex(c: VideoCodec) {
        dex?.stop()
        dexBitrate = targetBitrate
        lateinit var session: DexSession
        session = DexSession(
            this, c, quality,
            onConfig = { sendVideoConfig() },
            onPacket = { packet, key -> server?.broadcastVideo(packet, key) },
            onEnded = {
                handler.post {
                    if (dex !== session) return@post
                    Log.w(TAG, "DeX ended; showing the phone screen")
                    dex = null
                    dexFailed = true
                    MirrorState.error.value = "DeX couldn't start or stopped, so the headset shows the phone screen."
                    attachSurface()
                }
            },
        )
        dex = session
    }

    /** Points the virtual display at a fresh encoder or ImageReader sized for the current screen. */
    private fun attachSurface() {
        val display = virtualDisplay ?: return
        if (showDex(display)) return
        val metrics = realScreenMetrics(this)
        val (w, h) = scaledSize(metrics.widthPixels, metrics.heightPixels)
        val oldReader = reader
        val oldEncoder = encoder
        reader = null
        encoder = null
        val active = codec
        val surface = if (active != null) {
            val enc = try {
                VideoEncoder(active, w, h, quality, handler) { packet, key -> server?.broadcastVideo(packet, key) }
            } catch (e: Exception) {
                // Try the next codec down (H.265 → H.264 → JPEG).
                Log.w(TAG, "${active.label} encoder failed", e)
                // Leave the current surface in place; the retry below replaces and releases it.
                reader = oldReader
                encoder = oldEncoder
                brokenCodecs += active
                codec = null
                chooseMode()
                if (codec == null) attachSurface()
                return
            }
            enc.onConfigChanged = { sendVideoConfig() }
            enc.setBitrate(targetBitrate)
            encoder = enc
            enc.surface
        } else {
            newReader(w, h).also { reader = it }.surface
        }
        captureWidth = w
        captureHeight = h
        display.resize(w, h, metrics.densityDpi)
        display.surface = surface
        oldReader?.close()
        oldEncoder?.release()
    }

    /**
     * The virtual display only produces images when the screen changes, so each
     * one is copied into [padded] and sent at most [Quality.fps] times a second,
     * with a delayed send so the last image of an animation is never dropped.
     */
    private fun onImageAvailable(imageReader: ImageReader) {
        val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (imageReader !== reader) return
            val plane = image.planes[0]
            val rowWidth = plane.rowStride / plane.pixelStride
            val bitmap = padded?.takeIf { it.width == rowWidth && it.height == image.height }
                ?: Bitmap.createBitmap(rowWidth, image.height, Bitmap.Config.ARGB_8888).also {
                    padded?.recycle()
                    padded = it
                }
            bitmap.copyPixelsFromBuffer(plane.buffer)
            captureWidth = image.width
            dirty = true
        } finally {
            image.close()
        }
        if (server?.hasViewers != true || sendScheduled) return
        val wait = lastSentAt + 1000L / quality.fps - SystemClock.uptimeMillis()
        if (wait <= 0) {
            encodeAndSend()
        } else {
            sendScheduled = true
            handler.postDelayed(sendLatest, wait)
        }
    }

    private fun encodeAndSend() {
        val srv = server ?: return
        val bitmap = padded ?: return
        if (!dirty || !srv.hasViewers) return
        dirty = false
        lastSentAt = SystemClock.uptimeMillis()
        val width = minOf(captureWidth, bitmap.width)
        val frame = if (width == bitmap.width) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, bitmap.height)
        jpeg.reset()
        frame.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, jpeg)
        if (frame !== bitmap) frame.recycle()
        srv.broadcastFrame(jpeg.toByteArray())
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val address = localIpAddress()?.let { "https://$it:${MirrorState.PORT}" } ?: "Connect to Wi-Fi"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Sharing screen to headset")
            .setContentText("$address  ·  PIN ${MirrorState.pin.value}")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        if (advertising) runCatching { getSystemService(NsdManager::class.java).unregisterService(nsdListener) }
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        dex?.stop()
        dex = null
        AdbBridge.stopInjector()
        PhoneScreen.restore(this)
        wakeLock?.takeIf { it.isHeld }?.release()
        audio?.stop()
        audio = null
        server?.stop()
        server = null
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        reader?.close()
        encoder?.release()
        encoder = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        wifiLock?.takeIf { it.isHeld }?.release()
        captureThread.quitSafely()
        MirrorState.running.value = false
        MirrorState.viewers.value = 0
        super.onDestroy()
    }
}
