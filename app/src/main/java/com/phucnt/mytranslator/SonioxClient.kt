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
 * Soniox returns the original transcript *and* the translation in one stream.
 *
 * Latency tuning vs. the desktop original:
 *  - non-final translation tokens are surfaced as a live (provisional) subtitle
 *    so text appears almost immediately, then settles;
 *  - the endpoint delay is configurable (default lower than the desktop's 3 s).
 */
class SonioxClient(
    private val config: Config,
    private val listener: EngineListener,
) : TranslationEngine {

    data class Config(
        val apiKey: String,
        val sourceLanguage: String, // "auto" or a language code (one-way)
        val targetLanguage: String, // one-way target
        val endpointDelayMs: Int = 1200,
        val twoWay: Boolean = false,
        val langA: String = "",     // two-way side A
        val langB: String = "",     // two-way side B
    )

    companion object {
        private const val TAG = "SonioxClient"
        private const val ENDPOINT = "wss://stt-rt.soniox.com/transcribe-websocket"
        private const val KEEPALIVE_MS = 15_000L
        private const val MAX_RECONNECT = 3
        private const val RECONNECT_DELAY_MS = 2_000L
    }

    override val sampleRate = 16000

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var intentionalDisconnect = false
    private var reconnectAttempts = 0
    private val keepalive = object : Runnable {
        override fun run() {
            ws?.send("{\"type\":\"keepalive\"}")
            main.postDelayed(this, KEEPALIVE_MS)
        }
    }

    override fun connect() {
        intentionalDisconnect = false
        reconnectAttempts = 0
        if (config.apiKey.isBlank()) {
            listener.onStatus("error")
            listener.onError("API key is required. Enter your Soniox API key.")
            return
        }
        doConnect()
    }

    private fun doConnect() {
        listener.onStatus("connecting")
        ws = http.newWebSocket(Request.Builder().url(ENDPOINT).build(), wsListener)
    }

    override fun sendAudio(pcm: ByteArray) {
        if (connected) ws?.send(pcm.toByteString())
    }

    override fun disconnect() {
        intentionalDisconnect = true
        stopKeepalive()
        connected = false
        ws?.let {
            try {
                it.send(ByteString.EMPTY)
                it.close(1000, "User disconnected")
            } catch (_: Exception) {
            }
        }
        ws = null
        listener.onStatus("disconnected")
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
            webSocket.send(buildConfig().toString())
            connected = true
            reconnectAttempts = 0
            listener.onStatus("connected")
            startKeepalive()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return // stale socket from before a reconnect
            try {
                val json = JSONObject(text)
                if (json.has("error_code")) handleApiError(json) else handleResponse(json)
            } catch (e: Exception) {
                Log.e(TAG, "Parse error", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            Log.e(TAG, "WebSocket failure", t)
            connected = false
            if (!intentionalDisconnect) tryReconnect(t.message ?: "Connection error")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            connected = false
            stopKeepalive()
            if (intentionalDisconnect) {
                listener.onStatus("disconnected")
                return
            }
            when (code) {
                1000 -> listener.onStatus("disconnected")
                4001, 4003 -> fail("Invalid API key. Check your key in Settings.")
                4029 -> fail("Rate limit exceeded. Please wait and try again.")
                4002 -> fail("Subscription issue. Check your Soniox account.")
                else -> tryReconnect("Connection closed (code $code)")
            }
        }
    }

    private fun buildConfig(): JSONObject {
        val msg = JSONObject().apply {
            put("api_key", config.apiKey)
            put("model", "stt-rt-v4")
            put("audio_format", "pcm_s16le")
            put("sample_rate", sampleRate)
            put("num_channels", 1)
            put("enable_endpoint_detection", true)
            put("max_endpoint_delay_ms", config.endpointDelayMs)
            put("enable_language_identification", true)
        }
        if (config.twoWay) {
            // Soniox detects which side is speaking and translates to the other.
            msg.put("language_hints", JSONArray().put(config.langA).put(config.langB))
            msg.put("translation", JSONObject().apply {
                put("type", "two_way")
                put("language_a", config.langA)
                put("language_b", config.langB)
            })
        } else {
            if (config.sourceLanguage != Languages.AUTO) {
                msg.put("language_hints", JSONArray().put(config.sourceLanguage))
            }
            msg.put("translation", JSONObject().apply {
                put("type", "one_way")
                put("target_language", config.targetLanguage)
            })
        }
        return msg
    }

    private fun handleResponse(data: JSONObject) {
        val tokens = data.optJSONArray("tokens") ?: return
        if (tokens.length() == 0) return
        if (config.twoWay) { handleTwoWay(tokens); return }

        val finalOriginal = StringBuilder()
        val finalTranslation = StringBuilder()
        val provOriginal = StringBuilder()
        val provTranslation = StringBuilder()

        for (i in 0 until tokens.length()) {
            val token = tokens.optJSONObject(i) ?: continue
            val tokenText = token.optString("text")
            if (tokenText == "<end>") continue
            val status = token.optString("translation_status", "none")
            val isFinal = token.optBoolean("is_final", false)
            when (status) {
                "translation" -> if (isFinal) finalTranslation.append(tokenText) else provTranslation.append(tokenText)
                else -> if (isFinal) finalOriginal.append(tokenText) else provOriginal.append(tokenText)
            }
        }

        if (finalOriginal.isNotBlank()) listener.onSourceText(finalOriginal.toString(), true)
        if (finalTranslation.isNotBlank()) listener.onTranslationText(finalTranslation.toString(), true)
        // Provisional tails replace the live line (empty string clears it).
        listener.onSourceText(provOriginal.toString(), false)
        listener.onTranslationText(provTranslation.toString(), false)
    }

    /**
     * Two-way: separate each batch into spoken original and its translation,
     * carrying the per-side language so the service can label and speak it.
     */
    private fun handleTwoWay(tokens: JSONArray) {
        val origText = StringBuilder(); var origLang = ""
        val transText = StringBuilder(); var transLang = ""
        val provOrig = StringBuilder()
        val provTrans = StringBuilder()

        for (i in 0 until tokens.length()) {
            val token = tokens.optJSONObject(i) ?: continue
            val tokenText = token.optString("text")
            if (tokenText == "<end>") continue
            val status = token.optString("translation_status", "none")
            val isFinal = token.optBoolean("is_final", false)
            val lang = token.optString("language")

            if (status == "translation") {
                if (isFinal) {
                    transText.append(tokenText)
                    if (transLang.isEmpty() && lang.isNotEmpty()) transLang = lang
                } else provTrans.append(tokenText)
            } else { // "original" or "none" (third language)
                if (isFinal) {
                    origText.append(tokenText)
                    if (origLang.isEmpty() && lang.isNotEmpty()) origLang = lang
                } else provOrig.append(tokenText)
            }
        }

        // Soniox sometimes omits the translation language tag — infer the other side.
        if (transText.isNotBlank() && transLang.isEmpty()) {
            transLang = if (origLang == config.langA) config.langB else config.langA
        }

        if (origText.isNotBlank()) listener.onSegment(origText.toString(), origLang, false, true)
        if (transText.isNotBlank()) listener.onSegment(transText.toString(), transLang, true, true)

        // Live (provisional) tail: prefer the translation, else the original.
        when {
            provTrans.isNotBlank() -> listener.onSegment(provTrans.toString(), transLang, true, false)
            provOrig.isNotBlank() -> listener.onSegment(provOrig.toString(), origLang, false, false)
            else -> listener.onSegment("", "", false, false)
        }
    }

    private fun handleApiError(data: JSONObject) {
        val code = data.optInt("error_code", 0)
        val message = data.optString("error_message", "Unknown API error")
        if (code == 408) {
            tryReconnect("Request timeout")
            return
        }
        fail(
            when (code) {
                401 -> "Invalid API key. Check your key in Settings."
                429 -> "Rate limit exceeded. Please wait a moment."
                402 -> "Insufficient credits. Check your Soniox account."
                400 -> "Config error: $message"
                else -> message
            }
        )
    }

    private fun tryReconnect(reason: String) {
        if (reconnectAttempts >= MAX_RECONNECT) {
            fail("$reason. Reconnect failed after $MAX_RECONNECT attempts.")
            return
        }
        reconnectAttempts++
        listener.onStatus("connecting")
        listener.onError("$reason. Reconnecting ($reconnectAttempts/$MAX_RECONNECT)…")
        main.postDelayed({ if (!intentionalDisconnect) doConnect() }, RECONNECT_DELAY_MS * reconnectAttempts)
    }

    private fun fail(message: String) {
        listener.onStatus("error")
        listener.onError(message)
    }

    private fun startKeepalive() {
        stopKeepalive()
        main.postDelayed(keepalive, KEEPALIVE_MS)
    }

    private fun stopKeepalive() = main.removeCallbacks(keepalive)
}
