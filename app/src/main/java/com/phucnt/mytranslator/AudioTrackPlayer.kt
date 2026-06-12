package com.phucnt.mytranslator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Streams base64-encoded 24 kHz / mono / 16-bit PCM to the speaker.
 * Used for OpenAI Realtime's native translated voice output.
 *
 * Chunks are queued and written by a dedicated thread: AudioTrack.write blocks
 * when its buffer is full, and blocking the WebSocket reader thread would stall
 * the live subtitle events arriving on the same socket.
 */
class AudioTrackPlayer(private val sampleRate: Int = 24000) {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Volatile private var running = false
    @Volatile private var muted = false

    @Synchronized
    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate)) // ~0.5s headroom
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        running = true
        worker = thread(name = "voice-player") {
            while (running) {
                val pcm = try {
                    queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    continue
                }
                if (!muted) track?.write(pcm, 0, pcm.size)
            }
        }
    }

    /** Decode and enqueue; never blocks the caller. */
    fun push(pcmBase64: String) {
        if (!running || muted) return
        val pcm = try {
            Base64.decode(pcmBase64, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return
        }
        queue.offer(pcm)
    }

    fun setMuted(value: Boolean) {
        muted = value
        if (value) {
            queue.clear()
            try {
                track?.pause(); track?.flush(); track?.play()
            } catch (_: IllegalStateException) {
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt()
        worker?.join(500)
        worker = null
        queue.clear()
        track?.let {
            try {
                it.pause(); it.flush(); it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
    }
}
