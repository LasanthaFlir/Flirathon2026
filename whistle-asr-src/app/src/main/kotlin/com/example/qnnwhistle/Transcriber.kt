package com.example.qnnwhistle

/**
 * Common interface implemented by the speech-to-text engine so that
 * [MicRecorder], [TranscriptionService] and [WhistleRecognitionService] can
 * depend on an abstraction rather than a concrete decoder class.
 */
interface Transcriber {
    fun transcribe(pcm: ShortArray): String
    fun close()
}
