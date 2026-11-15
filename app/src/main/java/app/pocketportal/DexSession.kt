package app.pocketportal

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbStream
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The app's side of [DexDisplay]: starts it over [AdbBridge], reads its video, and
 * forwards keyframe and bitrate requests. Stop it to close DeX's display.
 */
class DexSession(
    context: Context,
    val codec: VideoCodec,
    quality: Quality,
    private val onConfig: (JSONObject) -> Unit,
    private val onPacket: (packet: ByteArray, keyframe: Boolean) -> Unit,
    private val onEnded: () -> Unit,
) {
    companion object {
        private const val TAG = "DexSession"
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val DPI = 240
    }

    @Volatile private var stream: AdbStream? = null
    @Volatile private var stopped = false
    /** DeX's display, for routing touches there; -1 until it exists. */
    @Volatile var displayId = -1
        private set
    @Volatile var config: JSONObject? = null
        private set

    init {
        AdbBridge.startProgram(context, DexDisplay::class.java, "${codec.name} $WIDTH $HEIGHT $DPI ${quality.name}") { s ->
            if (s == null || stopped) {
                runCatching { s?.close() }
                if (!stopped) onEnded()
                return@startProgram
            }
            stream = s
            thread(name = "pocketportal-dex", isDaemon = true) { read(s) }
        }
    }

    private fun read(s: AdbStream) {
        try {
            val input = DataInputStream(s.openInputStream())
            while (!stopped) {
                val type = input.readUnsignedByte().toChar()
                val length = input.readInt()
                if (length < 0 || length > 16 shl 20) error("bad message length $length")
                val payload = ByteArray(length).also { input.readFully(it) }
                when (type) {
                    'D' -> {
                        displayId = String(payload).toInt()
                        Log.i(TAG, "DeX display $displayId")
                    }
                    'C' -> JSONObject(String(payload)).also { config = it; onConfig(it) }
                    'V' -> onPacket(payload, payload.size > 1 && payload[1].toInt() and 1 == 1)
                }
            }
        } catch (e: Exception) {
            // libadb reports the end of the stream as "Stream closed".
            if (!stopped) Log.w(TAG, "DeX stream ended", e)
        } finally {
            displayId = -1
            if (!stopped) onEnded()
        }
    }

    fun requestKeyframe() = command("k")

    fun setBitrate(value: Int) = command("b $value")

    private fun command(line: String) {
        val s = stream ?: return
        thread(isDaemon = true) {
            runCatching { s.openOutputStream().apply { write("$line\n".toByteArray()); flush() } }
        }
    }

    fun stop() {
        stopped = true
        displayId = -1
        val s = stream ?: return
        stream = null
        thread(isDaemon = true) { runCatching { s.close() } }
    }
}
