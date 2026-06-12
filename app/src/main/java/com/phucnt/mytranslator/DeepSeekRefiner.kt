package com.phucnt.mytranslator

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Polishes a draft translation with DeepSeek's chat API (OpenAI-compatible).
 *
 * Used only with the Soniox engine: Soniox gives a fast, literal subtitle, then
 * DeepSeek re-translates the same segment into more fluent target-language text
 * and applies the user's glossary. Requests run on a single-thread queue so
 * segments finalize in order; any failure falls back to the original draft.
 */
class DeepSeekRefiner(
    private val apiKey: String,
    private val targetLanguageName: String,
    glossary: String,
) {
    companion object {
        private const val TAG = "DeepSeekRefiner"
        private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val systemPrompt = buildString {
        append("You are a professional real-time subtitle translator. ")
        append("Rewrite the draft translation into natural, fluent $targetLanguageName, ")
        append("faithful to the source and concise enough for a subtitle. ")
        append("Output ONLY the final translation text — no quotes, labels, or notes.")
        val terms = glossary.trim()
        if (terms.isNotEmpty()) {
            append("\nApply this glossary (use the right-hand term):\n")
            append(terms)
        }
    }

    private val http = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Refine [draft] (optionally guided by [source]); [onResult] is invoked on a
     * background thread with the improved text, or the draft if refinement fails.
     */
    fun refine(source: String, draft: String, onResult: (String) -> Unit) {
        try {
            executor.execute {
                val improved = try {
                    request(source, draft) ?: draft
                } catch (e: Exception) {
                    Log.w(TAG, "Refine failed, using draft", e)
                    draft
                }
                onResult(improved.ifBlank { draft })
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // shutdown raced a late segment — keep the draft, nothing to do
        }
    }

    private fun request(source: String, draft: String): String? {
        val userContent = buildString {
            if (source.isNotBlank()) append("Source: ").append(source).append('\n')
            append("Draft translation: ").append(draft)
        }

        val body = JSONObject().apply {
            put("model", "deepseek-chat")
            put("temperature", 0.3)
            put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userContent))
            })
        }.toString()

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful || text == null) {
                Log.w(TAG, "DeepSeek HTTP ${resp.code}")
                return null
            }
            val content = JSONObject(text)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
            return content?.trim()?.trim('"')
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
