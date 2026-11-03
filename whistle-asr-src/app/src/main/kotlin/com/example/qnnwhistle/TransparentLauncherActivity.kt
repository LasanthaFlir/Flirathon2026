package com.example.qnnwhistle

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Invisible trampoline activity for headless external callers.
 *
 * Uses the system `Theme.Translucent.NoTitleBar` (declared on the
 * `<activity>` in the manifest) so the window is fully transparent.
 * Extends [Activity] (NOT AppCompatActivity) — AppCompat refuses non-AppCompat
 * themes.
 *
 * Lifecycle behavior (the important part):
 *  - Forwards the inbound action to [TranscriptionService].
 *  - **Stays alive** (still invisible) until the service broadcasts RESULT
 *    or ERROR, OR a hard timeout elapses.
 *  - This keeps the QnnWhistle process in the foreground, so the service
 *    has full Android 12+/14 execution privileges and starts inference
 *    immediately — without our app ever visibly opening.
 *  - On result/error/timeout we [finish] silently.
 */
class TransparentLauncherActivity : Activity() {

    companion object {
        private const val TAG = "TransparentLauncher"
        // Hard cap: never keep the trampoline alive longer than this even if
        // no broadcast arrives. The service still runs to completion in the
        // background regardless.
        private const val MAX_HOLD_MS = 30_000L  // 30 s
        // Mic start: we don't wait for a transcript (mic runs continuously).
        // Just hold long enough for the FGS to enter foreground state.
        private const val MIC_START_HOLD_MS = 1_500L
        // Mic / file stop: nothing to wait for.
        private const val NOOP_HOLD_MS = 200L
    }

    private val main = Handler(Looper.getMainLooper())
    private val finishOnce = Runnable {
        if (!isFinishing) {
            Log.i(TAG, "Trampoline finishing")
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            Log.i(TAG, "Got broadcast: ${i?.action}")
            when (i?.action) {
                TranscriptionService.ACTION_RESULT,
                TranscriptionService.ACTION_ERROR -> {
                    main.removeCallbacks(finishOnce)
                    main.post(finishOnce)
                }
            }
        }
    }
    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register BEFORE firing the service intent so we don't miss a fast
        // result broadcast.
        registerResultReceiver()

        val action = intent?.action
        Log.i(TAG, "onCreate action=$action")
        try {
            when (action) {
                MainActivity.ACTION_START_MIC -> {
                    startMic()
                    // Hold ~1.5s so the FGS reaches foreground state, then leave.
                    main.postDelayed(finishOnce, MIC_START_HOLD_MS)
                }
                MainActivity.ACTION_STOP_MIC -> {
                    stopMic()
                    main.postDelayed(finishOnce, NOOP_HOLD_MS)
                }
                MainActivity.ACTION_TRANSCRIBE_FILE -> {
                    val uri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    if (uri == null) {
                        Log.w(TAG, "TRANSCRIBE_FILE: no URI")
                        main.postDelayed(finishOnce, NOOP_HOLD_MS)
                    } else {
                        transcribeFile(uri)
                        // Wait for the service to broadcast RESULT or ERROR;
                        // hard cap at MAX_HOLD_MS in case the service crashes.
                        main.postDelayed(finishOnce, MAX_HOLD_MS)
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown action: $action")
                    main.postDelayed(finishOnce, NOOP_HOLD_MS)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Trampoline failed", e)
            main.postDelayed(finishOnce, NOOP_HOLD_MS)
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(finishOnce)
        if (receiverRegistered) {
            try { unregisterReceiver(resultReceiver) } catch (_: Exception) {}
            receiverRegistered = false
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

    private fun startMic() {
        val i = Intent(this, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopMic() {
        val i = Intent(this, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_STOP)
        startService(i)
    }

    private fun transcribeFile(uri: Uri) {
        val i = Intent("com.example.qnnwhistle.action.TRANSCRIBE_FILE_BG")
            .setComponent(ComponentName(packageName, TranscriptionService::class.java.name))
            .setData(uri)
            .putExtra(TranscriptionService.EXTRA_URI, uri)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Plain startService — service stays in background mode but our
        // trampoline is foreground (transparent), which is enough for it to
        // run inference under Android 14's FGS rules.
        startService(i)
    }
}
