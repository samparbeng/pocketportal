package app.pocketportal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/** Video codecs the phone can stream, best first. */
enum class VideoCodec(val mime: String, val label: String) {
    H265(MediaFormat.MIMETYPE_VIDEO_HEVC, "H.265"),
    H264(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264");

    /** True when this phone has a hardware encoder for it; software encoders are too slow to stream with. */
    val available: Boolean by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(mime, true) }
        }
    }
}

/**
 * Hardware H.265 or H.264 encoder fed straight from the virtual display's [surface].
 *
 * Each packet goes out as `[0x02][flags][8-byte timestamp µs][Annex-B data]`,
 * where flags bit 0 marks a keyframe. Keyframes carry the parameter sets
 * (VPS/SPS/PPS) in front, so a viewer can start decoding from any keyframe.
 * The timestamp is the capture time on the phone's monotonic clock
 * ([System.nanoTime] / 1000), which the viewer uses to measure latency.
 */
class VideoEncoder(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    quality: Quality,
    handler: Handler,
    private val onPacket: (packet: ByteArray, keyframe: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "VideoEncoder"
        const val MESSAGE_TYPE: Byte = 0x02
        private const val HEADER = 10
    }

    private val mediaCodec = MediaCodec.createEncoderByType(codec.mime)
    val surface: Surface
    @Volatile private var config: ByteArray? = null
    /** WebCodecs codec string such as "avc1.640028" or "hvc1.1.6.L120.B0", known once the encoder emits its parameter sets. */
    @Volatile var codecString: String? = null
        private set
    var onConfigChanged: () -> Unit = {}

    /** The bitrate the encoder is currently asked for; [setBitrate] changes it while running. */
    @Volatile var bitrate: Int = quality.bitrate
        private set

    init {
        val format = MediaFormat.createVideoFormat(codec.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, quality.videoFps)
            // Clock the encoder for the full frame rate, so 120 fps doesn't queue up.
            setInteger(MediaFormat.KEY_OPERATING_RATE, quality.videoFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // A still screen produces no new frames; repeating the last one keeps
            // keyframe requests answered within a tenth of a second.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
            setInteger(MediaFormat.KEY_LATENCY, 1)
            // No B-frames: they'd hold frames back waiting for later ones.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        mediaCodec.setCallback(object : MediaCodec.Callback() {
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
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = mediaCodec.createInputSurface()
        mediaCodec.start()
    }

    private fun handle(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(data)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            config = data
            codecString = when (codec) {
                VideoCodec.H264 -> avcCodecString(data)
                VideoCodec.H265 -> hevcCodecString(data)
            }
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
        runCatching { mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun setBitrate(value: Int) {
        if (value == bitrate) return
        bitrate = value
        runCatching { mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, value) }) }
    }

    fun release() {
        runCatching { mediaCodec.stop() }
        mediaCodec.release()
        surface.release()
    }
}

/** Start offsets of the NAL units (just past each start code) in an Annex-B buffer. */
private fun nalUnits(annexB: ByteArray): List<Int> {
    val starts = mutableListOf<Int>()
    var i = 0
    while (i + 3 < annexB.size) {
        if (annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte()) {
            if (annexB[i + 2] == 1.toByte()) { starts += i + 3; i += 3; continue }
            if (annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte()) { starts += i + 4; i += 4; continue }
        }
        i++
    }
    return starts
}

/** "avc1.PPCCLL": profile, constraints and level from the H.264 SPS (NAL type 7). */
internal fun avcCodecString(annexB: ByteArray): String? {
    val sps = nalUnits(annexB).firstOrNull { it + 3 < annexB.size && annexB[it].toInt() and 0x1F == 7 } ?: return null
    return "avc1.%02x%02x%02x".format(annexB[sps + 1], annexB[sps + 2], annexB[sps + 3])
}

/**
 * The RFC 6381 / ISO 14496-15 codec string for H.265, e.g. "hvc1.1.6.L120.B0",
 * read from the profile_tier_level at the start of the SPS (NAL type 33).
 */
internal fun hevcCodecString(annexB: ByteArray): String? {
    val sps = nalUnits(annexB).firstOrNull { it < annexB.size && (annexB[it].toInt() shr 1) and 0x3F == 33 } ?: return null
    // Remove emulation-prevention bytes (00 00 03 → 00 00) from what follows the 2-byte NAL header.
    val rbsp = ArrayList<Int>(16)
    var zeros = 0
    var i = sps + 2
    while (i < annexB.size && rbsp.size < 13) {
        val b = annexB[i].toInt() and 0xFF
        if (zeros >= 2 && b == 3) { zeros = 0; i++; continue }
        zeros = if (b == 0) zeros + 1 else 0
        rbsp += b
        i++
    }
    if (rbsp.size < 13) return null
    // rbsp[0]: vps id, max sub-layers, nesting flag. Then the general profile_tier_level:
    val profileSpace = rbsp[1] shr 6
    val tier = (rbsp[1] shr 5) and 1
    val profile = rbsp[1] and 0x1F
    var compat = 0L
    for (k in 2..5) compat = (compat shl 8) or rbsp[k].toLong()
    val reversed = java.lang.Long.reverse(compat) ushr 32
    val constraints = (6..11).map { rbsp[it] }.dropLastWhile { it == 0 }
    val level = rbsp[12]
    return buildString {
        append("hvc1.")
        if (profileSpace > 0) append('A' + profileSpace - 1)
        append(profile).append('.')
        append(java.lang.Long.toHexString(reversed).uppercase()).append('.')
        append(if (tier == 1) 'H' else 'L').append(level)
        constraints.forEach { append('.').append("%02X".format(it)) }
    }
}
