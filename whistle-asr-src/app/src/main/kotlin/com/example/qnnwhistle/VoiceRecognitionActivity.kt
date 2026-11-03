package com.example.qnnwhistle

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Public, IME-agnostic entry point for Whistle voice recognition via the
 * standard [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] intent.
 *
 * Usage from any other app:
 *
 *     val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
 *     // Optional extras: EXTRA_LANGUAGE_MODEL, EXTRA_PROMPT, etc.
 *     startActivityForResult(i, REQ_VOICE)
 *
 *     // onActivityResult:
 *     val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
 *     val text = results?.firstOrNull().orEmpty()
 *
 * Why this exists in addition to [WhistleRecognitionService]:
 *  - On stock AOSP 14 the `SpeechRecognizer` (binder) path rejects 3rd-party
 *    callers with `ERROR_INSUFFICIENT_PERMISSIONS` because the framework's
 *    `AttributionSource.isTrusted()` guard refuses tokens that have been
 *    re-wrapped by `system_server` for non-system recognizers.
 *  - This Activity-based API runs the recording in our own process, so no
 *    cross-process AttributionSource forwarding is involved → it just works,
 *    from any caller (system or not), with the same Whistle pipeline.
 *
 * Behaviour:
 *  - Window is fully transparent (system Theme.Translucent.NoTitleBar).
 *  - Mic permission is enforced on **us** (the recognizer). If we don't have
 *    RECORD_AUDIO we return RESULT_CANCELED with EXTRA_ERROR_CODE =
 *    ERROR_INSUFFICIENT_PERMISSIONS.
 *  - The audio capture + transcription is delegated to the existing
 *    [TranscriptionService]; we just listen for its RESULT / ERROR broadcasts
 *    and turn them into the standard `EXTRA_RESULTS` array on the activity
 *    result.
 *  - We auto-stop the mic after the first non-empty result is produced.
 */
class VoiceRecognitionActivity : Activity() {

    companion object {
        private const val TAG = "VoiceRecognitionAct"

        // How long we'll keep listening for speech / waiting for a transcript
        // before bailing out with RESULT_CANCELED. Mirrors what a typical
        // RecognizerIntent UI does (~30 s overall budget).
        private const val MAX_HOLD_MS = 30_000L
    }

    private val main = Handler(Looper.getMainLooper())

    private var receiverRegistered = false
    private var done = false

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (done) return
            val text = i?.getStringExtra(TranscriptionService.EXTRA_TEXT).orEmpty()
            Log.i(TAG, "rx ${i?.action} text='${text.take(80)}'")
            when (i?.action) {
                TranscriptionService.ACTION_RESULT -> {
                    if (text.isNotBlank()) finishWithResult(text)
                    // empty result = silence segment; keep listening
                }
                TranscriptionService.ACTION_ERROR -> {
                    finishWithError(android.speech.SpeechRecognizer.ERROR_CLIENT, text)
                }
            }
        }
    }

    private val timeoutRunnable = Runnable {
        if (!done) {
            Log.w(TAG, "Timeout reached without a non-empty result")
            finishWithError(
                android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                "No speech detected"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Validate intent action (we're only registered for this one anyway).
        val action = intent?.action
        if (action != RecognizerIntent.ACTION_RECOGNIZE_SPEECH &&
            action != RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE &&
            action != RecognizerIntent.ACTION_WEB_SEARCH
        ) {
            Log.w(TAG, "Unexpected action: $action")
            finishWithError(
                android.speech.SpeechRecognizer.ERROR_CLIENT,
                "Unsupported action: $action"
            )
            return
        }

        // Check our own mic permission - we're the one that will record audio.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO not granted to QnnWhistle itself")
            finishWithError(
                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                "Microphone permission denied for recognizer"
            )
            return
        }

        registerResultReceiver()

        // Kick off mic capture via TranscriptionService (foreground service).
        val svc = Intent(this, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_START)
        ContextCompat.startForegroundService(this, svc)

        // Overall timeout. The TranscriptionService keeps producing RESULT
        // broadcasts every time VAD closes a segment; we finish with the first
        // non-empty one.
        main.postDelayed(timeoutRunnable, MAX_HOLD_MS)
    }

    override fun onDestroy() {
        main.removeCallbacks(timeoutRunnable)
        if (receiverRegistered) {
            try { unregisterReceiver(resultReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }

        // Always stop the mic on the way out so we don't leave it hot for the
        // next session.
        try {
            startService(
                Intent(this, TranscriptionService::class.java)
                    .setAction(TranscriptionService.ACTION_STOP)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop TranscriptionService", e)
        }

        super.onDestroy()
    }

    private fun registerResultReceiver() {
        val f = IntentFilter().apply {
            addAction(TranscriptionService.ACTION_RESULT)
            addAction(TranscriptionService.ACTION_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(resultReceiver, f)
        }
        receiverRegistered = true
    }

    private fun finishWithResult(text: String) {
        if (done) return
        done = true
        val data = Intent().apply {
            // Standard RecognizerIntent contract:
            //   EXTRA_RESULTS         = ArrayList<String>
            //   EXTRA_CONFIDENCE_SCORES = float[]
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS,
                arrayListOf(text)
            )
            putExtra(
                RecognizerIntent.EXTRA_CONFIDENCE_SCORES,
                floatArrayOf(1.0f)
            )
        }
        setResult(RESULT_OK, data)
        Log.i(TAG, "Returning OK: '$text'")
        finish()
        overridePendingTransition(0, 0)
    }

    private fun finishWithError(code: Int, message: String?) {
        if (done) return
        done = true
        val data = Intent().apply {
            // Not all callers expect these, but RecognitionService / Google
            // Voice IME do read EXTRA_ERROR_CODE on RESULT_CANCELED.
            putExtra("android.speech.extra.ERROR_CODE", code)
            if (message != null) putExtra("android.speech.extra.ERROR_MESSAGE", message)
        }
        setResult(RESULT_CANCELED, data)
        Log.w(TAG, "Returning CANCELED: code=$code msg='$message'")
        finish()
        overridePendingTransition(0, 0)
    }
}
