package com.phucnt.mytranslator

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real-time speech-to-text + translation over Soniox's WebSocket API.
 *
 * Mirrors the proven flow used by the desktop app (`src/js/soniox.js`):
 * connect → send JSON config → stream raw PCM → receive translated tokens.
 * Soniox returns the original transcript *and* the translation in one stream,
 * so this single engine produces both the subtitle and the text we read aloud.
 */
class SonioxClient(
    private val onStatus: (Status) -> Unit,
    private val onOriginal: (String) -> Unit,
    private val onTranslation: (String) -> Unit,
    private val onProvisional: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    data class Config(
        val apiKey: String,
        val sourceLanguage: String, // "auto" or a language code
        val targetLanguage: String,
    )

    companion object {
        private const val TAG = "SonioxClient"
        private const val ENDPOINT = "wss://stt-rt.soniox.com/transcribe-websocket"
        private const val KEEPALIVE_MS = 15_000L
        private const val MAX_RECONNECT = 3
        private const val RECONNECT_DELAY_MS = 2_000L
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // never time out the streaming socket
        .build()

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var intentionalDisconnect = false
    private var config: Config? = null
    private var reconnectAttempts = 0
    private val keepalive = object : Runnable {
        override fun run() {
            ws?.send("{\"type\":\"keepalive\"}")
            main.postDelayed(this, KEEPALIVE_MS)
        }
    }

    fun connect(config: Config) {
        this.config = config
        intentionalDisconnect = false
        reconnectAttempts = 0
        if (config.apiKey.isBlank()) {
            onStatus(Status.ERROR)
            onError("API key is required. Enter your Soniox API key.")
            return
        }
        doConnect(config)
    }

    private fun doConnect(config: Config) {
        onStatus(Status.CONNECTING)
        val request = Request.Builder().url(ENDPOINT).build()
        ws = http.newWebSocket(request, listener)
    }

    /** Send raw PCM bytes. No-op if the socket isn't open yet. */
    fun sendAudio(pcm: ByteArray) {
        if (connected) ws?.send(pcm.toByteString())
    }

    fun disconnect() {
        intentionalDisconnect = true
        stopKeepalive()
        connected = false
        ws?.let {
            try {
                it.send(ByteString.EMPTY) // graceful end-of-stream signal
                it.close(1000, "User disconnected")
            } catch (_: Exception) {
            }
        }
        ws = null
        onStatus(Status.DISCONNECTED)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val cfg = config ?: return
            webSocket.send(buildConfig(cfg).toString())
            connected = true
            reconnectAttempts = 0
            onStatus(Status.CONNECTED)
            startKeepalive()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                if (json.has("error_code")) {
                    handleApiError(json)
                    return
                }
                handleResponse(json)
            } catch (e: Exception) {
                Log.e(TAG, "Parse error", e)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Soniox sends JSON as text; ignore unexpected binary frames.
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            connected = false
            if (intentionalDisconnect) return
            tryReconnect(t.message ?: "Connection error")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            stopKeepalive()
            if (intentionalDisconnect) {
                onStatus(Status.DISCONNECTED)
                return
            }
            when (code) {
                1000 -> onStatus(Status.DISCONNECTED)
                4001, 4003 -> fail("Invalid API key. Check your key in Settings.")
                4029 -> fail("Rate limit exceeded. Please wait and try again.")
                4002 -> fail("Subscription issue. Check your Soniox account.")
                else -> tryReconnect("Connection closed (code $code)")
            }
        }
    }

    private fun buildConfig(cfg: Config): JSONObject {
        val msg = JSONObject().apply {
            put("api_key", cfg.apiKey)
            put("model", "stt-rt-v4")
            put("audio_format", "pcm_s16le")
            put("sample_rate", AudioCapture.SAMPLE_RATE)
            put("num_channels", 1)
            put("enable_endpoint_detection", true)
            put("enable_language_identification", true)
        }
        if (cfg.sourceLanguage != Languages.AUTO) {
            msg.put("language_hints", JSONArray().put(cfg.sourceLanguage))
        }
        msg.put("translation", JSONObject().apply {
            put("type", "one_way")
            put("target_language", cfg.targetLanguage)
        })
        return msg
    }

    private fun handleResponse(data: JSONObject) {
        val tokens = data.optJSONArray("tokens") ?: return
        if (tokens.length() == 0) return

        val original = StringBuilder()
        val translation = StringBuilder()
        val provisional = StringBuilder()

        for (i in 0 until tokens.length()) {
            val token = tokens.optJSONObject(i) ?: continue
            val tokenText = token.optString("text")
            if (tokenText == "<end>") continue

            val status = token.optString("translation_status", "none")
            val isFinal = token.optBoolean("is_final", false)

            when (status) {
                "translation" -> if (isFinal) translation.append(tokenText)
                "original", "none" -> if (isFinal) original.append(tokenText) else provisional.append(tokenText)
            }
        }

        if (original.isNotBlank()) onOriginal(original.toString())
        if (translation.isNotBlank()) onTranslation(translation.toString())
        if (provisional.isNotBlank()) {
            onProvisional(provisional.toString())
        } else if (original.isNotBlank() || translation.isNotBlank()) {
            onProvisional("")
        }
    }

    private fun handleApiError(data: JSONObject) {
        val code = data.optInt("error_code", 0)
        val message = data.optString("error_message", "Unknown API error")
        if (code == 408) {
            tryReconnect("Request timeout")
            return
        }
        val userMessage = when (code) {
            401 -> "Invalid API key. Check your key in Settings."
            429 -> "Rate limit exceeded. Please wait a moment."
            402 -> "Insufficient credits. Check your Soniox account."
            400 -> "Config error: $message"
            else -> message
        }
        fail(userMessage)
    }

    private fun tryReconnect(reason: String) {
        val cfg = config ?: return
        if (reconnectAttempts >= MAX_RECONNECT) {
            fail("$reason. Reconnect failed after $MAX_RECONNECT attempts.")
            return
        }
        reconnectAttempts++
        val delay = RECONNECT_DELAY_MS * reconnectAttempts
        onStatus(Status.CONNECTING)
        onError("$reason. Reconnecting ($reconnectAttempts/$MAX_RECONNECT)…")
        main.postDelayed({
            if (!intentionalDisconnect) doConnect(cfg)
        }, delay)
    }

    private fun fail(message: String) {
        onStatus(Status.ERROR)
        onError(message)
    }

    private fun startKeepalive() {
        stopKeepalive()
        main.postDelayed(keepalive, KEEPALIVE_MS)
    }

    private fun stopKeepalive() {
        main.removeCallbacks(keepalive)
    }
}
