package com.phucnt.mytranslator

/** Common interface for a streaming speech-translation engine (Soniox or OpenAI). */
interface TranslationEngine {
    fun connect()
    fun sendAudio(pcm: ByteArray)
    fun disconnect()

    /** PCM sample rate this engine expects from [sendAudio]. */
    val sampleRate: Int
}

/** Callbacks an engine emits. May be invoked from background (WebSocket) threads. */
interface EngineListener {
    fun onStatus(status: String)

    /** Source-language transcript. [isFinal] false = live/provisional tail. */
    fun onSourceText(text: String, isFinal: Boolean)

    /** Translated text. [isFinal] false = live/provisional tail. */
    fun onTranslationText(text: String, isFinal: Boolean)

    /** Native translated audio (OpenAI only), base64 PCM16. Default: ignored. */
    fun onAudioChunk(pcmBase64: String) {}

    /**
     * Two-way (bilingual) segment. [language] is the BCP-47 code of *this* text:
     * for a translation it is the target language to speak. [isTranslation]
     * distinguishes the spoken original from its translation. Default: ignored.
     */
    fun onSegment(text: String, language: String, isTranslation: Boolean, isFinal: Boolean) {}

    fun onError(message: String)
}
