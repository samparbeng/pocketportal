package app.pocketportal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Hardware H.264 encoder fed straight from the virtual display's [surface].
 *
 * Each packet goes out as `[0x02][flags][8-byte timestamp µs][Annex-B data]`,
 * where flags bit 0 marks a keyframe. Keyframes carry the SPS/PPS in front, so a
 * viewer can start decoding from any keyframe.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    quality: Quality,
    handler: Handler,
    private val onPacket: (packet: ByteArray, keyframe: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val MESSAGE_TYPE: Byte = 0x02
        private const val HEADER = 10

        /** False on the rare device without an H.264 encoder; the app then streams JPEG. */
        fun isAvailable(): Boolean = runCatching { MediaCodec.createEncoderByType(MIME).release() }.isSuccess
    }

    private val codec = MediaCodec.createEncoderByType(MIME)
    val surface: Surface
    @Volatile private var config: ByteArray? = null
    /** WebCodecs codec string such as "avc1.640028", known once the encoder emits its SPS. */
    @Volatile var codecString: String? = null
        private set
    var onConfigChanged: () -> Unit = {}

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, quality.videoFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // A still screen produces no new frames; repeating the last one keeps
            // keyframe requests answered within a tenth of a second.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buffer = codec.getOutputBuffer(index) ?: return
                    if (info.size > 0) handle(buffer, info)
                } catch (e: IllegalStateException) {
                    return // stopped while a buffer was in flight
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "encoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    private fun handle(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(data)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            config = data
            codecString = parseCodecString(data)
            onConfigChanged()
            return
        }
        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val prefix = if (keyframe) config ?: ByteArray(0) else ByteArray(0)
        val packet = ByteArray(HEADER + prefix.size + data.size)
        packet[0] = MESSAGE_TYPE
        packet[1] = if (keyframe) 1 else 0
        for (i in 0 until 8) packet[2 + i] = (info.presentationTimeUs shr (56 - 8 * i)).toByte()
        System.arraycopy(prefix, 0, packet, HEADER, prefix.size)
        System.arraycopy(data, 0, packet, HEADER + prefix.size, data.size)
        onPacket(packet, keyframe)
    }

    fun requestKeyframe() {
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
        surface.release()
    }

    /** Reads profile, constraints and level from the SPS (the NAL unit of type 7). */
    private fun parseCodecString(annexB: ByteArray): String? {
        var i = 0
        while (i + 4 < annexB.size) {
            val startCode = annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte() &&
                (annexB[i + 2] == 1.toByte() || (annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte()))
            if (startCode) {
                val nal = i + if (annexB[i + 2] == 1.toByte()) 3 else 4
                if (nal + 3 < annexB.size && annexB[nal].toInt() and 0x1F == 7) {
                    return "avc1.%02x%02x%02x".format(annexB[nal + 1], annexB[nal + 2], annexB[nal + 3])
                }
                i = nal
            } else {
                i++
            }
        }
        return null
    }
}
