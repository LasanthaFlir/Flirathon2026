package com.example.qnnwhistle

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android SpeechRecognizer entrypoint backed by Whistle.
 *
 * This service keeps a single active recognition session at a time. Internally,
 * it reuses [WhistleDecoder] and [MicRecorder].
 */
class WhistleRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "WhistleRecService"
        private const val STOP_GRACE_MS = 1200L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private data class Session(
        val callback: Callback,
        var recorder: MicRecorder? = null,
        var finished: Boolean = false,
        var stopJob: Job? = null
    )

    private val lock = Any()
    private var decoder: Transcriber? = null
    private var initJob: Job? = null
    private var session: Session? = null

    override fun onDestroy() {
        synchronized(lock) {
            session?.recorder?.stop()
            session?.stopJob?.cancel()
            session = null
        }
        decoder?.close()
        decoder = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening(recognizerIntent: android.content.Intent?, listener: Callback?) {
        if (listener == null) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        val s = synchronized(lock) {
            if (session != null) {
                listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                return
            }
            Session(callback = listener).also { session = it }
        }

        listener.readyForSpeech(Bundle())
        ensureDecoderReady(s)
    }

    override fun onStopListening(listener: Callback?) {
        if (listener == null) return
        val s = synchronized(lock) {
            val cur = session
            if (cur == null || cur.callback != listener) {
                listener.error(SpeechRecognizer.ERROR_CLIENT)
                return
            }
            cur
        }

        s.recorder?.stop()
        s.stopJob?.cancel()
        s.stopJob = scope.launch {
            delay(STOP_GRACE_MS)
            if (!s.finished) {
                deliverNoMatch(s)
            }
        }
    }

    override fun onCancel(listener: Callback?) {
        if (listener == null) return
        val s = synchronized(lock) { session }
        if (s == null || s.callback != listener) return
        finishSession(s, null)
    }

    private fun ensureDecoderReady(s: Session) {
        val existing = decoder
        if (existing != null) {
            startMic(s, existing)
            return
        }

        synchronized(lock) {
            if (initJob?.isActive == true) return
            initJob = scope.launch {
                val err = withContext(Dispatchers.IO) {
                    try {
                        decoder = when (Prefs.getEngine(this@WhistleRecognitionService)) {
                            Prefs.ENGINE_MOONSHINE_V2 -> MoonshineV2Decoder(this@WhistleRecognitionService)
                            else -> WhistleDecoder(this@WhistleRecognitionService)
                        }
                        null
                    } catch (e: Exception) {
                        e
                    }
                }

                if (err != null) {
                    Log.e(TAG, "Decoder init failed", err)
                    deliverErrorAndFinish(s, SpeechRecognizer.ERROR_CLIENT)
                    return@launch
                }

                val dec = decoder
                if (dec == null) {
                    deliverErrorAndFinish(s, SpeechRecognizer.ERROR_CLIENT)
                    return@launch
                }
                startMic(s, dec)
            }
        }
    }

    private fun startMic(s: Session, dec: Transcriber) {
        synchronized(lock) {
            if (session !== s || s.finished) return
        }

        s.callback.beginningOfSpeech()
        val rec = MicRecorder(
            context = this,
            scope = scope,
            decoder = dec,
            onResult = { text ->
                if (text.isBlank()) return@MicRecorder
                deliverResultsAndFinish(s, text)
            },
            onStatus = { /* no-op */ },
            onError = {
                Log.e(TAG, "MicRecorder error: $it")
                deliverErrorAndFinish(s, SpeechRecognizer.ERROR_AUDIO)
            },
            onPartial = { text ->
                synchronized(lock) { if (session !== s || s.finished) return@MicRecorder }
                val b = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                }
                try {
                    s.callback.partialResults(b)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deliver partial results", e)
                }
            }
        )

        synchronized(lock) {
            if (session !== s || s.finished) return
            s.recorder = rec
        }
        rec.start()
    }

    private fun deliverResultsAndFinish(s: Session, text: String) {
        synchronized(lock) {
            if (session !== s || s.finished) return
            s.finished = true
        }

        val b = Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf(text)
            )
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.0f))
        }

        try {
            s.callback.endOfSpeech()
        } catch (_: Exception) {
        }
        try {
            s.callback.results(b)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deliver results", e)
        }

        finishSession(s, null)
    }

    private fun deliverNoMatch(s: Session) {
        synchronized(lock) {
            if (session !== s || s.finished) return
            s.finished = true
        }
        try {
            s.callback.error(SpeechRecognizer.ERROR_NO_MATCH)
        } catch (_: Exception) {
        }
        finishSession(s, null)
    }

    private fun deliverErrorAndFinish(s: Session, code: Int) {
        synchronized(lock) {
            if (session !== s || s.finished) return
            s.finished = true
        }
        try {
            s.callback.error(code)
        } catch (_: Exception) {
        }
        finishSession(s, null)
    }

    private fun finishSession(s: Session, errCode: Int?) {
        synchronized(lock) {
            if (session !== s) return
            s.stopJob?.cancel()
            s.recorder?.stop()
            if (errCode != null && !s.finished) {
                try {
                    s.callback.error(errCode)
                } catch (_: Exception) {
                }
            }
            session = null
        }
    }
}
