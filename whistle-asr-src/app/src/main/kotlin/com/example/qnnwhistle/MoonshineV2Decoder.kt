package com.example.qnnwhistle

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Implements [Transcriber] on top of Moonshine v2 "streaming tiny" — run as
 * plain float32 ONNX Runtime graphs on the CPU execution provider (no
 * QNN/HTP, no INT8 quantization). This is a drop-in alternate engine next to
 * [WhistleDecoder] for comparing accuracy on accented/domain speech; see the
 * `benchmark_moonshine_vs_whistle.py` PC-side benchmark this was ported from.
 *
 * Encoder takes a fixed 30s (480000-sample) window, zero-padded if shorter.
 * Decoder is autoregressive with explicit self/cross-attention KV cache
 * tensors threaded through each step, mirroring the PC reference script.
 */
class MoonshineV2Decoder(context: Context) : Transcriber {
    companion object {
        private const val TAG = "MoonshineV2Decoder"
        private const val N_SAMPLES = 480_000   // 30s @ 16kHz, fixed encoder input window
        private const val N_LAYERS = 6
        private const val BOS = 1L
        private const val EOS = 2L
        private const val MAX_TOKENS_PER_SEC = 6.5
        private const val SAMPLE_RATE = 16_000
    }

    private val env = OrtEnvironment.getEnvironment()
    private val sessEnc: OrtSession
    private val sessInit: OrtSession
    private val sessStep: OrtSession
    private val vocab: List<String>

    init {
        val opts = OrtSession.SessionOptions()
        sessEnc = env.createSession(
            copyAssetIfMissing(context, "moonshine_v2_encoder.onnx").absolutePath, opts)
        sessInit = env.createSession(
            copyAssetIfMissing(context, "moonshine_v2_decoder_init.onnx").absolutePath, opts)
        sessStep = env.createSession(
            copyAssetIfMissing(context, "moonshine_v2_decoder_step.onnx").absolutePath, opts)
        vocab = loadVocab(context)
        Log.i(TAG, "Moonshine v2 loaded (ORT CPU, ${vocab.size} vocab entries)")
    }

    override fun transcribe(pcm: ShortArray): String {
        return try {
            runPipeline(pcm)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            ""
        }
    }

    override fun close() {
        sessEnc.close(); sessInit.close(); sessStep.close()
    }

    private fun runPipeline(pcm: ShortArray): String {
        val durationSec = pcm.size.toDouble() / SAMPLE_RATE
        val padded = FloatArray(N_SAMPLES)
        val n = minOf(pcm.size, N_SAMPLES)
        for (i in 0 until n) padded[i] = pcm[i] / 32768f

        val encInput = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(padded), longArrayOf(1, N_SAMPLES.toLong()))
        val encResult = encInput.use { sessEnc.run(mapOf("input_values" to it)) }
        val hiddenTensor = encResult[0] as OnnxTensor

        val tokens = mutableListOf<Long>()
        try {
            val bos = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(BOS)), longArrayOf(1, 1))
            var initResult = sessInit.run(
                mapOf("input_ids" to bos, "encoder_hidden_states" to hiddenTensor))
            bos.close()

            var logits = initResult[0] as OnnxTensor
            var selfK = Array(N_LAYERS) { initResult[1 + it * 4] as OnnxTensor }
            var selfV = Array(N_LAYERS) { initResult[2 + it * 4] as OnnxTensor }
            val crossK = Array(N_LAYERS) { initResult[3 + it * 4] as OnnxTensor }
            val crossV = Array(N_LAYERS) { initResult[4 + it * 4] as OnnxTensor }

            var nextId = argmaxLastRow(logits)
            var pos = 1L
            val maxTokens = maxOf(10, (durationSec * MAX_TOKENS_PER_SEC).toInt())

            while (nextId != EOS && tokens.size < maxTokens) {
                tokens.add(nextId)
                val idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(nextId)), longArrayOf(1, 1))
                val posTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(pos)), longArrayOf(1, 1))
                val feed = mutableMapOf<String, OnnxTensor>(
                    "input_ids" to idTensor, "position_ids" to posTensor
                )
                for (li in 0 until N_LAYERS) {
                    feed["past.$li.sa_k"] = selfK[li]
                    feed["past.$li.sa_v"] = selfV[li]
                    feed["cross.$li.ca_k"] = crossK[li]
                    feed["cross.$li.ca_v"] = crossV[li]
                }
                val stepResult = sessStep.run(feed)
                idTensor.close(); posTensor.close()

                val newLogits = stepResult[0] as OnnxTensor
                val newSelfK = Array(N_LAYERS) { stepResult[1 + 2 * it] as OnnxTensor }
                val newSelfV = Array(N_LAYERS) { stepResult[2 + 2 * it] as OnnxTensor }

                nextId = argmaxLastRow(newLogits)
                pos += 1

                // Release the previous step's logits/self-KV (decoder_init's
                // result stays alive for cross-K/V, which never change).
                if (tokens.size > 1) { logits.close(); selfK.forEach { it.close() }; selfV.forEach { it.close() } }
                logits = newLogits
                selfK = newSelfK
                selfV = newSelfV
            }
            logits.close(); selfK.forEach { it.close() }; selfV.forEach { it.close() }
            // Don't close crossK/crossV manually — they (and any unused
            // init-sourced tensors) are still owned by initResult and get
            // released exactly once below.
            initResult.close()
        } finally {
            encResult.close()
        }

        val text = tokens.joinToString("") { vocab.getOrElse(it.toInt()) { "" } }
            .replace('\u2581', ' ')
            .trim()
        Log.i(TAG, "Moonshine v2 text: '$text' (${tokens.size} tokens, ${"%.2f".format(durationSec)}s audio)")
        return text
    }

    /** argmax over the last (and only) row of a [1, vocab_size] logits tensor. */
    private fun argmaxLastRow(t: OnnxTensor): Long {
        val arr = t.floatBuffer
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        val n = arr.remaining()
        for (i in 0 until n) {
            val v = arr.get(i)
            if (v > bestVal) { bestVal = v; bestIdx = i }
        }
        arr.rewind()
        return bestIdx.toLong()
    }

    private fun loadVocab(context: Context): List<String> {
        val json = context.assets.open("moonshine_v2_vocab.json").bufferedReader().use { it.readText() }
        // vocab.json is a flat JSON array of piece strings indexed by token id.
        val obj = org.json.JSONArray(json)
        return (0 until obj.length()).map { obj.getString(it) }
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
