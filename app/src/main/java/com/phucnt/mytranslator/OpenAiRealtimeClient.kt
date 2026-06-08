package com.phucnt.mytranslator

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI Realtime Translate over WebSocket — returns translated text *and*
 * native translated speech audio in one stream (lowest latency, ~$4/hr).
 *
 * Mirrors the desktop app's Rust bridge (`commands/openai_realtime.rs`):
 * connect with a Bearer header (allowed on Android, unlike browsers), send a
 * `session.update`, stream 24 kHz PCM as base64, and parse transcript/audio
 * deltas. Input audio must be 24 kHz s16le mono.
 */
class OpenAiRealtimeClient(
    private val config: Config,
    private val listener: EngineListener,
) : TranslationEngine {

    data class Config(
        val apiKey: String,
        val targetLanguage: String,
        val wantAudio: Boolean,
    )

    companion object {
        private const val TAG = "OpenAiRealtime"
        private const val URL =
            "wss://api.openai.com/v1/realtime/translations?model=gpt-realtime-translate"
    }

    override val sampleRate = 24000

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var intentionalDisconnect = false

    // Deltas arrive as incremental pieces; accumulate so we can emit the full
    // live line each time (matching Soniox's "provisional tail = replace" model).
    private val srcBuffer = StringBuilder()
    private val tgtBuffer = StringBuilder()

    override fun connect() {
        intentionalDisconnect = false
        if (config.apiKey.isBlank()) {
            listener.onStatus("error")
            listener.onError("OpenAI API key is required.")
            return
        }
        listener.onStatus("connecting")
        val request = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()
        ws = http.newWebSocket(request, wsListener)
    }

    override fun sendAudio(pcm: ByteArray) {
        if (!connected) return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val msg = JSONObject()
            .put("type", "session.input_audio_buffer.append")
            .put("audio", b64)
        ws?.send(msg.toString())
    }

    override fun disconnect() {
        intentionalDisconnect = true
        connected = false
        ws?.let {
            try {
                it.close(1000, "User disconnected")
            } catch (_: Exception) {
            }
        }
        ws = null
        listener.onStatus("disconnected")
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(buildSessionUpdate().toString())
            connected = true
            listener.onStatus("connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleEvent(JSONObject(text))
            } catch (e: Exception) {
                Log.e(TAG, "Parse error", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            connected = false
            if (!intentionalDisconnect) {
                listener.onStatus("error")
                listener.onError(t.message ?: "Connection error")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            listener.onStatus("disconnected")
        }
    }

    private fun buildSessionUpdate(): JSONObject {
        val input = JSONObject()
            .put("transcription", JSONObject().put("model", "gpt-realtime-whisper"))
            .put("noise_reduction", JSONObject().put("type", "near_field"))
        val audio = JSONObject()
            .put("input", input)
            .put("output", JSONObject().put("language", config.targetLanguage))
        return JSONObject()
            .put("type", "session.update")
            .put("session", JSONObject().put("audio", audio))
    }

    private fun handleEvent(value: JSONObject) {
        when (value.optString("type")) {
            "session.input_transcript.delta" -> {
                val d = value.optString("delta")
                if (d.isNotEmpty()) {
                    srcBuffer.append(d)
                    listener.onSourceText(srcBuffer.toString(), false)
                }
            }

            "session.input_transcript.done", "session.input_audio_transcription.completed" -> {
                val t = value.optString("transcript", value.optString("text"))
                if (t.isNotEmpty()) listener.onSourceText(t, true)
                srcBuffer.setLength(0)
                listener.onSourceText("", false)
            }

            "session.output_transcript.delta" -> {
                val d = value.optString("delta")
                if (d.isNotEmpty()) {
                    tgtBuffer.append(d)
                    listener.onTranslationText(tgtBuffer.toString(), false)
                }
            }

            "session.output_transcript.done" -> {
                val t = value.optString("transcript")
                if (t.isNotEmpty()) listener.onTranslationText(t, true)
                tgtBuffer.setLength(0)
                listener.onTranslationText("", false)
            }

            "session.output_audio.delta" -> {
                if (config.wantAudio) {
                    value.optString("delta").takeIf { it.isNotEmpty() }
                        ?.let { listener.onAudioChunk(it) }
                }
            }

            "session.closed" -> listener.onStatus("disconnected")

            "error" -> {
                val err = value.optJSONObject("error")
                val code = err?.optString("code") ?: "unknown"
                val message = err?.optString("message") ?: "OpenAI error"
                listener.onStatus("error")
                listener.onError("$message ($code)")
            }
        }
    }
}
