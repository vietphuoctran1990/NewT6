package com.phucnt.mytranslator

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Captures microphone audio as 16 kHz / mono / 16-bit little-endian PCM —
 * exactly the format the Soniox real-time endpoint expects — and hands each
 * chunk to [onChunk] on a background thread.
 */
class AudioCapture(
    private val onChunk: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16000
    }

    @Volatile
    private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the Activity before start()
    fun start() {
        if (running) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("Microphone not available on this device")
            return
        }
        // Read ~100 ms at a time; use a comfortably large recorder buffer.
        val readSize = SAMPLE_RATE / 10 * 2 // 0.1s * 16000 * 2 bytes
        val bufferSize = maxOf(minBuf, readSize * 4)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("Failed to initialize the microphone")
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
