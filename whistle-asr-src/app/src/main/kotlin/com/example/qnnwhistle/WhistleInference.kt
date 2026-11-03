package com.example.qnnwhistle

/** Thin JNI bridge to the Cactus-Compute "Whistle" speech-to-text engine
 *  (native static lib libneedle.a, C API in needle.h). See WhistleDecoder for
 *  the higher-level wrapper used by the rest of the app. */
class WhistleInference {
    /** cactPath — absolute path to whistle.cact on internal storage.
     *  Returns "" on success, an error message on failure. */
    external fun initWhistle(cactPath: String): String

    /** pcm — mono 16kHz PCM16 samples, any length.
     *  language — ISO 639-1 code ("en", "de", ...) or null to auto-detect.
     *  Returns the raw JSON string from needle_transcribe(), e.g.
     *  {"text":"...","language":"en","ttft_ms":0.0,"decode_tps":0.0} */
    external fun transcribe(pcm: ShortArray, language: String?): String

    companion object {
        init { System.loadLibrary("qnn_whistle") }
    }
}
