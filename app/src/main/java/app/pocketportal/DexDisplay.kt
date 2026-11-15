package app.pocketportal

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Surface
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * Runs Samsung DeX on a separate, hidden display and streams it.
 *
 * Started as the shell user over the phone's own ADB connection ([AdbBridge]):
 * `CLASSPATH=<apk> app_process / app.pocketportal.DexDisplay CODEC W H DPI QUALITY 2>/dev/null`.
 * Only the shell may create a *trusted* display with system decorations, and
 * that's what DeX starts its desktop on; an app's own (MediaProjection) display
 * never gets DeX. Nothing appears on the phone's screen.
 *
 * stdout carries binary messages, `[type][4-byte length][payload]`:
 *  - 'D' the display id (UTF-8), once the display exists, for touch input;
 *  - 'C' the video config as JSON (codec string, name, size), whenever it changes;
 *  - 'V' a video packet in [VideoEncoder]'s format.
 * stdin takes lines: `k` (send a keyframe) and `b BITRATE`. Closing stdin ends
 * DeX's display and this program.
 */
object DexDisplay {
    // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden).
    private const val PUBLIC = 1 shl 0
    private const val PRESENTATION = 1 shl 1
    private const val OWN_CONTENT_ONLY = 1 shl 3
    private const val SUPPORTS_TOUCH = 1 shl 6
    private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    private const val TRUSTED = 1 shl 10
    private const val OWN_DISPLAY_GROUP = 1 shl 11
    private const val ALWAYS_UNLOCKED = 1 shl 12

    // No DESTROY_CONTENT_ON_REMOVAL: when sharing ends, DeX's apps move to the phone instead of closing.
    private const val FLAGS = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or
        SHOULD_SHOW_SYSTEM_DECORATIONS or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED

    private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out), 1 shl 16))

    private fun send(type: Char, payload: ByteArray) = synchronized(out) {
        out.writeByte(type.code)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val codec = VideoCodec.valueOf(args.getOrNull(0) ?: "H264")
        val w = args.getOrNull(1)?.toIntOrNull() ?: 1920
        val h = args.getOrNull(2)?.toIntOrNull() ?: 1080
        val dpi = args.getOrNull(3)?.toIntOrNull() ?: 240
        val quality = runCatching { Quality.valueOf(args[4]) }.getOrDefault(Quality.Balanced)
        // Anything else printing to stdout would corrupt the stream.
        System.setOut(System.err)

        val display: VirtualDisplay
        val encoder: VideoEncoder
        try {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            val thread = HandlerThread("dex-encoder").also { it.start() }
            encoder = VideoEncoder(codec, w, h, quality, Handler(thread.looper)) { packet, _ -> send('V', packet) }
            encoder.onConfigChanged = {
                val config = JSONObject()
                    .put("codec", encoder.codecString)
                    .put("name", codec.label)
                    .put("width", w)
                    .put("height", h)
                send('C', config.toString().toByteArray())
            }
            display = create(w, h, dpi, encoder.surface)
            send('D', display.display.displayId.toString().toByteArray())
        } catch (e: Throwable) {
            System.err.println("dex display failed: $e")
            exitProcess(1)
        }

        // Commands from the app; when it closes stdin, DeX's display goes away.
        val reader = System.`in`.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            when {
                line == "k" -> encoder.requestKeyframe()
                line.startsWith("b ") -> line.substring(2).toIntOrNull()?.let(encoder::setBitrate)
            }
        }
        display.release()
        encoder.release()
        exitProcess(0)
    }

    private fun create(w: Int, h: Int, dpi: Int, surface: Surface): VirtualDisplay {
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        val dm = ctor.newInstance(shellContext())
        return dm.createVirtualDisplay("Pocket Portal DeX", w, h, dpi, surface, FLAGS)
    }

    /** A Context that says it's the shell package, the way scrcpy does it. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun shellContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getDeclaredMethod("systemMain").invoke(null)
        val system = activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        return object : ContextWrapper(system) {
            override fun getPackageName() = "com.android.shell"
            override fun getOpPackageName() = "com.android.shell"
            override fun getAttributionSource(): AttributionSource =
                AttributionSource.Builder(Process.SHELL_UID).setPackageName("com.android.shell").build()
            override fun getApplicationContext(): Context = this
        }
    }
}
