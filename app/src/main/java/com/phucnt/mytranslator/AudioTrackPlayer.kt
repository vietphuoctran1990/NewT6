package com.phucnt.mytranslator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64

/**
 * Streams base64-encoded 24 kHz / mono / 16-bit PCM to the speaker.
 * Used for OpenAI Realtime's native translated voice output.
 */
class AudioTrackPlayer(private val sampleRate: Int = 24000) {

    private var track: AudioTrack? = null

    @Synchronized
    fun start() {
        if (track != null) return
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
    }

    fun push(pcmBase64: String) {
        val t = track ?: return
        val pcm = try {
            Base64.decode(pcmBase64, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return
        }
        t.write(pcm, 0, pcm.size)
    }

    @Synchronized
    fun stop() {
        track?.let {
            try {
                it.pause()
                it.flush()
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
    }
}
