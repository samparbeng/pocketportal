package app.pocketportal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Captures what other apps are playing (media, games, other sounds) through the
 * screen-sharing projection and hands it out in 40 ms chunks of 48 kHz stereo
 * 16-bit PCM. Calls and apps that opt out of capture are silent, by Android's design.
 */
class AudioStreamer(private val onChunk: (ByteArray) -> Unit) {

    companion object {
        private const val TAG = "AudioStreamer"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        /** 40 ms per chunk keeps packet overhead low without adding much delay. */
        private const val CHUNK_BYTES = SAMPLE_RATE / 25 * CHANNELS * 2

        /** First byte of an audio message; video frames are JPEGs and start with 0xFF. */
        const val MESSAGE_TYPE: Byte = 0x01

        fun hasPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Volatile private var running = false
    private var record: AudioRecord? = null

    /** Returns false when capture couldn't start, e.g. the permission is missing. */
    @SuppressLint("MissingPermission") // checked by the caller with hasPermission()
    fun start(projection: MediaProjection): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audio = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "audio capture unavailable", e)
            return false
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return false
        }
        record = audio
        running = true
        audio.startRecording()
        thread(name = "pocketportal-audio", isDaemon = true) {
            while (running) {
                val chunk = ByteArray(1 + CHUNK_BYTES)
                chunk[0] = MESSAGE_TYPE
                var filled = 1
                while (running && filled < chunk.size) {
                    val n = audio.read(chunk, filled, chunk.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (running && filled == chunk.size) onChunk(chunk)
            }
        }
        return true
    }

    fun stop() {
        running = false
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}
