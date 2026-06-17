package com.phucnt.mytranslator

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread

/**
 * Captures audio as mono / 16-bit PCM at a requested sample rate and hands each
 * chunk to [onChunk] on a background thread.
 *
 *  - [Source.MIC] records the microphone.
 *  - [Source.SYSTEM] records other apps' playback via [MediaProjection]
 *    (Android 10+). NOTE: Android only allows capturing MEDIA/GAME audio —
 *    voice-call audio (Zoom/Meet/phone) and DRM content cannot be captured.
 */
class AudioCapture(
    private val source: Source,
    private val sampleRate: Int,
    private val mediaProjection: MediaProjection?,
    private val onChunk: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    enum class Source { MIC, SYSTEM }

    companion object {
        private const val TAG = "AudioCapture"
    }

    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO requested by the caller before start()
    fun start() {
        if (running) return

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("Audio not available on this device")
            return
        }
        // ~60 ms per read for low latency.
        val readSize = sampleRate / 1000 * 60 * 2
        val bufferSize = maxOf(minBuf, readSize * 4)

        val rec = try {
            buildRecorder(bufferSize)
        } catch (e: Exception) {
            onError("Failed to start audio: ${e.message}")
            return
        }
        if (rec == null) return
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("Failed to initialize audio capture")
            return
        }

        recorder = rec
        running = true
        rec.startRecording()

        worker = thread(name = "audio-capture") {
            val buffer = ByteArray(readSize)
            while (running) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onChunk(if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read))
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                }
            }
        }
    }

    private fun buildRecorder(bufferSize: Int): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        return when (source) {
            Source.MIC ->
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()

            Source.SYSTEM -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    onError("System audio capture needs Android 10 or newer")
                    return null
                }
                val projection = mediaProjection ?: run {
                    onError("System audio permission was not granted")
                    return null
                }
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    // Exclude our own playback so the translated voice (TTS / OpenAI
                    // native audio) isn't recaptured and translated again — otherwise
                    // enabling voice while capturing system audio creates a feedback
                    // loop. Routing to headphones does NOT prevent this; capture happens
                    // at the audio mix, not the acoustic output.
                    .excludeUid(android.os.Process.myUid())
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        recorder?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        recorder = null
    }
}
