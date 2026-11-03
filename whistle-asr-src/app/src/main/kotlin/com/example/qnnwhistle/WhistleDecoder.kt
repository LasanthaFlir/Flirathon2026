package com.example.qnnwhistle

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Implements [Transcriber] on top of the Cactus-Compute "Whistle" model via
 * [WhistleInference]'s JNI bridge. Unlike the QNN Moonshine pipeline, Whistle
 * runs entirely on the CPU (no HTP/DSP), and is a single-call API — no
 * separate encoder/decoder stage or KV-cache bookkeeping needed.
 *
 * whistle.cact (16.9 MB) ships in assets/ and is copied to internal storage
 * on first launch, since the native engine mmaps it directly from a real
 * filesystem path.
 */
class WhistleDecoder(context: Context) : Transcriber {
    companion object {
        private const val TAG = "WhistleDecoder"
        private const val ASSET_NAME = "whistle.cact"
    }

    private val inference = WhistleInference()

    init {
        val cactFile = copyAssetIfMissing(context, ASSET_NAME)
        val err = inference.initWhistle(cactFile.absolutePath)
        if (err.isNotEmpty()) throw RuntimeException("Whistle init failed: $err")
        Log.i(TAG, "Whistle loaded from ${cactFile.absolutePath}")
    }

    override fun transcribe(pcm: ShortArray): String {
        val json = inference.transcribe(pcm, /* language = */ "en")
        Log.i(TAG, "Raw transcribe JSON: $json")
        return try {
            JSONObject(json).optString("text", "").trim()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Whistle JSON output: $json", e)
            ""
        }
    }

    override fun close() {
        // No explicit release call in needle.h — the process-global model
        // is torn down when the process exits.
    }

    private fun copyAssetIfMissing(context: Context, assetName: String): File {
        val outFile = File(context.getExternalFilesDir(null) ?: context.filesDir, assetName)
        if (!outFile.exists() || outFile.length() == 0L) {
            Log.i(TAG, "Copying $assetName to ${outFile.absolutePath}")
            context.assets.open(assetName).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return outFile
    }
}
