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

    // Capture-thread state.
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
        server = MirrorServer(
            port = MirrorState.PORT,
            page = page,
            pin = { MirrorState.pin.value },
            onConnected = {
                sendHello()
                handler.post { if (padded != null) { dirty = true; encodeAndSend() } }
            },
            onMessage = { text -> TouchService.instance?.handle(JSONObject(text)) },
            onViewersChanged = { MirrorState.viewers.value = it },
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
            },
            NsdManager.PROTOCOL_DNS_SD, nsdListener,
        )

        // Tell open viewers when remote touch is switched on or off on the phone.
        scope.launch { MirrorState.touchEnabled.drop(1).collect { sendHello() } }

        if (MirrorState.sound.value && AudioStreamer.hasPermission(this)) {
            val streamer = AudioStreamer { chunk -> server?.broadcastAudio(chunk) }
            if (streamer.start(mp)) audio = streamer
        }
    }

    private fun sendHello() {
        server?.broadcastText(
            JSONObject()
                .put("type", "hello")
                .put("input", TouchService.instance != null)
                .put("audio", audio != null)
                .put("rate", AudioStreamer.SAMPLE_RATE)
                .toString()
        )
    }

    /** Fits the screen inside the quality preset's longest side, keeping both sides even for the encoder. */
    private fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1f, quality.maxDimension.toFloat() / maxOf(width, height))
        return ((width * scale).toInt() and 1.inv()) to ((height * scale).toInt() and 1.inv())
    }

    private fun newReader(width: Int, height: Int) =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ onImageAvailable(it) }, handler)
        }

    /** Rotation swaps width and height; resize the virtual display so the picture isn't squashed. */
    private fun updateCaptureSize() {
        val display = virtualDisplay ?: return
        val metrics = realScreenMetrics(this)
        val (w, h) = scaledSize(metrics.widthPixels, metrics.heightPixels)
        if (w == captureWidth && h == captureHeight) return
        captureWidth = w
        captureHeight = h
        val old = reader
        val imageReader = newReader(w, h)
        reader = imageReader
        display.resize(w, h, metrics.densityDpi)
        display.surface = imageReader.surface
        old?.close()
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
        val address = localIpAddress()?.let { "http://$it:${MirrorState.PORT}" } ?: "Connect to Wi-Fi"
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
        audio?.stop()
        audio = null
        server?.stop()
        server = null
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        reader?.close()
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
