package com.example.moonshineconsumer

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

private const val TAG = "MoonshineConsumer"

class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var recogButton: Button
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private var recogRunning = false
    private var pendingRecognizerStart = false
    // Start index in `log`'s text of the current in-progress partial-result
    // line, so each new partial update replaces it in place instead of
    // appending a new line every ~1s (which otherwise reprints the whole
    // in-progress sentence over and over as it grows).
    private var partialLineStart = -1
    private var recognizer: SpeechRecognizer? = null
    private var pulseAnimators: List<ObjectAnimator> = emptyList()

    private var currentToast: Toast? = null

    /** Visual states for the status dot/label, driven by all three test paths. */
    private enum class RecState(val color: Int, val pulseMs: Long) {
        IDLE(0xFF9E9E9E.toInt(), 0),
        CALIBRATING(0xFFFBC02D.toInt(), 900),
        LISTENING(0xFF2196F3.toInt(), 700),
        PROCESSING(0xFFFF9800.toInt(), 350),
        ERROR(0xFFE53935.toInt(), 0)
    }

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val text = i.getStringExtra("text") ?: ""
            Log.d(TAG, "rx onReceive action=${i.action} text='${text.take(120)}'")
            val tag = when (i.action) {
                "com.example.qnnmoonshine.RESULT" -> "📝"
                "com.example.qnnmoonshine.STATUS" -> "ℹ️ "
                "com.example.qnnmoonshine.ERROR"  -> "⚠️ "
                "com.example.qnnmoonshine.READY"  -> "✅ ready"
                else -> "?"
            }
            appendLog("$tag $text\n")
            when (i.action) {
                "com.example.qnnmoonshine.STATUS" -> {
                    if (text.contains("Listening", ignoreCase = true)) {
                        setState(RecState.LISTENING, "🎙 Listening — speak now")
                    } else if (text.contains("Calibrating", ignoreCase = true)) {
                        setState(RecState.CALIBRATING, "🔧 Calibrating mic…")
                    } else if (text.contains("Transcribing", ignoreCase = true)) {
                        setState(RecState.PROCESSING, "⏳ Transcribing…")
                    }
                }
                "com.example.qnnmoonshine.ERROR" -> {
                    setState(RecState.ERROR, "⚠️ $text")
                }
                "com.example.qnnmoonshine.RESULT" -> setState(RecState.IDLE, "Idle")
            }
            if (i.action == "com.example.qnnmoonshine.RESULT") {
                currentToast?.cancel()
                currentToast = Toast.makeText(
                    this@MainActivity,
                    "📝  ${text.ifBlank { "(empty)" }}",
                    Toast.LENGTH_LONG
                ).also { it.show() }
            }
        }
    }

    private val pickWav = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.d(TAG, "pickWav result uri=$uri")
        if (uri == null) {
            appendLog("⚠️  No file picked\n")
        } else {
            sendFile(uri)
        }
    }

    // ---- Path 2: standard RecognizerIntent.ACTION_RECOGNIZE_SPEECH ----
    // Unlike SpeechRecognizer.startListening(), this Activity-based API does
    // not cross AttributionSource boundaries, so it is not affected by the
    // Android-14 "error 9 for 3rd-party callers" limitation. It works against
    // any installed handler of ACTION_RECOGNIZE_SPEECH — Moonshine now
    // exports a VoiceRecognitionActivity for exactly this purpose.
    private val recognizerIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                val results = data?.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS
                )
                val text = results?.firstOrNull().orEmpty()
                appendLog("📝 Intent result: ${if (text.isBlank()) "(empty)" else text}\n")
                setState(RecState.IDLE, "Idle")
            }
            Activity.RESULT_CANCELED -> {
                val code = data?.getIntExtra("android.speech.extra.ERROR_CODE", -1) ?: -1
                val msg = data?.getStringExtra("android.speech.extra.ERROR_MESSAGE")
                appendLog("⚠️  Intent canceled (code=$code msg=$msg)\n")
                setState(RecState.ERROR, "⚠️ $msg")
            }
            else -> {
                appendLog("⚠️  Intent unknown result=${result.resultCode}\n")
                setState(RecState.IDLE, "Idle")
            }
        }
    }

    private fun launchRecognizerIntent() {
        appendLog("🎤 Launching RecognizerIntent.ACTION_RECOGNIZE_SPEECH\n")
        setState(RecState.LISTENING, "🎙 Listening — speak now")
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now…")
        }
        try {
            recognizerIntentLauncher.launch(i)
        } catch (e: ActivityNotFoundException) {
            appendLog("⚠️  No activity handles ACTION_RECOGNIZE_SPEECH: ${e.message}\n")
            setState(RecState.ERROR, "⚠️ No handler found")
        }
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            appendLog("✅ Microphone permission granted\n")
            if (pendingRecognizerStart) {
                pendingRecognizerStart = false
                startRecognizerSession()
            }
        } else {
            pendingRecognizerStart = false
            val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            val msg = if (canAskAgain) {
                "⚠️  Microphone permission denied\n"
            } else {
                "⚠️  Microphone permission denied permanently. Enable it in App Settings.\n"
            }
            appendLog(msg)
            Toast.makeText(this, "Microphone permission is required to start mic", Toast.LENGTH_SHORT).show()
            setRecognizerRunning(false)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        log = TextView(this).apply {
            setPadding(24, 24, 24, 24)
            // Monospace + selectable makes it easy to copy a transcript out of
            // the log when troubleshooting.
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            // We use the surrounding ScrollView to scroll, but enabling vertical
            // scrollbars on the TextView still draws a thumb hint.
            isVerticalScrollBarEnabled = true
        }
        logScroll = ScrollView(this).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            // Always keep the *latest* line in view: when content height changes
            // (TextView re-laid-out after a log line is appended) we scroll to
            // the bottom. This is much more reliable than calling fullScroll()
            // from appendLog(), because addOnLayoutChangeListener fires *after*
            // the new measure/layout pass has happened.
            addView(log)
            addOnLayoutChangeListener { v, _, _, _, bottom, _, _, _, oldBottom ->
                if (bottom != oldBottom) {
                    // Use a post so the new TextView height is fully resolved
                    // before we ask the ScrollView to jump.
                    post {
                        val sv = v as ScrollView
                        sv.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        recogButton = Button(this).apply {
            setOnClickListener { toggleRecognitionService() }
        }
        setRecognizerRunning(false)
        val pick  = Button(this).apply {
            text = "Pick WAV File"
            setOnClickListener {
                pickWav.launch(arrayOf("audio/*", "audio/wav", "audio/x-wav", "*/*"))
            }
        }
        val intentButton = Button(this).apply {
            text = "Voice via RecognizerIntent"
            setOnClickListener { launchRecognizerIntent() }
        }
        val clearButton = Button(this).apply {
            text = "Clear Log"
            setOnClickListener {
                log.text = ""
                partialLineStart = -1
            }
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(36, 36).apply {
                marginEnd = 24
            }
        }
        statusText = TextView(this).apply {
            text = "Idle"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
        }
        val statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 16)
            addView(statusDot); addView(statusText)
        }
        setState(RecState.IDLE, "Idle")
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusBar)
            addView(recogButton); addView(intentButton); addView(pick); addView(clearButton); addView(logScroll)
        })

        // Register for the lifetime of the activity (NOT the visibility lifecycle),
        // Otherwise, picking a WAV file (which puts us in onStop) means we miss
        // the single RESULT broadcast that comes back from QnnMoonshine.
        val f = IntentFilter().apply {
            addAction("com.example.qnnmoonshine.RESULT")
            addAction("com.example.qnnmoonshine.STATUS")
            addAction("com.example.qnnmoonshine.ERROR")
            addAction("com.example.qnnmoonshine.READY")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(rx, f)
    }

    // Shows a dialog pointing the user straight at this app's permission page.
    // Needed because ERROR_INSUFFICIENT_PERMISSIONS can fire even when
    // checkSelfPermission() says GRANTED — the mic app-op can be stuck in a
    // restricted mode that only the Settings UI (not our in-app dialog) clears.
    private fun offerOpenAppSettings() {
        AlertDialog.Builder(this)
            .setTitle("Microphone permission needed")
            .setMessage(
                "Speech recognition needs microphone access, but it isn't fully " +
                    "allowed for this app. Open Settings and allow Microphone, then try again."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleRecognitionService() {
        if (recogRunning) {
            stopRecognizerSession()
            return
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMicPermission) {
            startRecognizerSession()
        } else {
            pendingRecognizerStart = true
            appendLog("🔐 Requesting microphone permission for RecognitionService\n")
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true

        // IMPORTANT: do NOT pass an explicit ComponentName here on Android 12+.
        //
        // SpeechRecognizer.createSpeechRecognizer(ctx, component) makes the system
        // bypass the IRecognitionServiceManager AttributionSource forwarding for
        // *non-default* recognizers, so the platform's RecognitionService superclass
        // sees system_server (uid 1000) as the caller instead of us, and rejects the
        // bind with ERROR_INSUFFICIENT_PERMISSIONS (error 9) - even though we hold
        // RECORD_AUDIO. The fix is to rely on the system default recognizer (which
        // is set to MoonshineRecognitionService via Settings.Secure on this image),
        // and let the manager service propagate our AttributionSource correctly.
        return try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        appendLog("🎧 RecognitionService ready\n")
                        setState(RecState.LISTENING, "🎙 Listening — speak now")
                    }
                    override fun onBeginningOfSpeech() {
                        appendLog("🗣️  Speech started\n")
                        setState(RecState.LISTENING, "🗣 Hearing you…")
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        appendLog("🔇 Speech ended\n")
                        setState(RecState.PROCESSING, "⏳ Processing…")
                    }
                    override fun onError(error: Int) {
                        appendLog("⚠️  RecognitionService error=$error (${speechErrorName(error)})\n")
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            // checkSelfPermission can report GRANTED while the mic app-op is
                            // still stuck in a restricted state (e.g. left over from an adb
                            // `pm grant`, which does not go through the normal consent flow).
                            // The runtime permission dialog won't reappear in that case, so
                            // send the user to the system Settings screen to toggle it.
                            appendLog("   Opening app settings so you can re-allow the microphone…\n")
                            offerOpenAppSettings()
                        }
                        // The underlying binder connection to the recognition service is
                        // dead after most errors (especially INSUFFICIENT_PERMISSIONS and
                        // SERVER_DISCONNECTED). Reusing the same SpeechRecognizer instance
                        // on the next attempt fails instantly, client-side, without ever
                        // re-contacting the service — so force a fresh instance next time.
                        try { recognizer?.destroy() } catch (_: Exception) {}
                        recognizer = null
                        setRecognizerRunning(false)
                        setState(RecState.ERROR, "⚠️ ${speechErrorName(error)}")
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        appendLog("📝 RS result: ${if (text.isBlank()) "(empty)" else text}\n")
                        setRecognizerRunning(false)
                        setState(RecState.IDLE, "Idle")
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        if (partial.isNotBlank()) updatePartialLog("… RS partial: $partial\n")
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            true
        } catch (e: Exception) {
            appendLog("⚠️  Could not create recognizer: ${e.message}\n")
            false
        }
    }

    private fun startRecognizerSession() {
        if (!ensureRecognizer()) return

        val selfGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val moonshineGranted = packageManager.checkPermission(
            Manifest.permission.RECORD_AUDIO,
            "com.example.qnnmoonshine"
        ) == PackageManager.PERMISSION_GRANTED
        appendLog("ℹ️  Preflight mic perms: consumer=$selfGranted moonshine=$moonshineGranted\n")

        if (!selfGranted) {
            pendingRecognizerStart = true
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Whistle now emits live partial results every ~1s while the user is
            // still talking (see MicRecorder's partial-decode loop) — request them
            // so onPartialResults() below actually gets called.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            recognizer?.cancel()
            recognizer?.startListening(intent)
            setRecognizerRunning(true)
            appendLog("🎤 RecognitionService start requested\n")
        } catch (e: Exception) {
            setRecognizerRunning(false)
            appendLog("⚠️  startListening failed: ${e.message}\n")
        }
    }

    private fun speechErrorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
        else -> "UNKNOWN"
    }

    private fun stopRecognizerSession() {
        try {
            recognizer?.stopListening()
            appendLog("⏹️  RecognitionService stop requested\n")
        } catch (e: Exception) {
            appendLog("⚠️  stopListening failed: ${e.message}\n")
        }
        setRecognizerRunning(false)
        setState(RecState.IDLE, "Idle")
    }

    private fun appendLog(line: String) {
        // Any non-partial log line finalizes whatever partial preview was
        // in progress — the next partial starts a fresh line after this one.
        partialLineStart = -1
        appendLogRaw(line)
    }

    /** Updates the live in-progress partial-result line in place instead of
     *  appending a new one, so the log shows a single growing sentence while
     *  the user is talking rather than reprinting it every ~1s. */
    private fun updatePartialLog(line: String) {
        val current = log.text
        if (partialLineStart in 0..current.length) {
            log.text = current.substring(0, partialLineStart)
        } else {
            partialLineStart = current.length
        }
        appendLogRaw(line)
    }

    private fun appendLogRaw(line: String) {
        // Keep memory bounded — drop the oldest half of the buffer once we go
        // over MAX_LOG_CHARS. Without this a long session would slowly grow
        // unbounded.
        val current = log.text
        if (current.length + line.length > MAX_LOG_CHARS) {
            val keepFrom = (current.length + line.length) - (MAX_LOG_CHARS / 2)
            log.text = current.substring(keepFrom.coerceAtMost(current.length))
            partialLineStart = -1   // buffer was trimmed — partial-line index no longer valid
        }
        log.append(line)
        // Auto-scrolling is handled by the ScrollView's layout-change listener
        // (set up in onCreate), which fires *after* the TextView has been
        // re-measured. That's more reliable than calling fullScroll() here.
    }

    private companion object {
        private const val MAX_LOG_CHARS = 16_000
    }

    private fun setRecognizerRunning(running: Boolean) {
        recogRunning = running
        if (::recogButton.isInitialized) {
            recogButton.text = if (running) "Stop RecognitionService" else "Start RecognitionService"
        }
    }

    /** Updates the status dot color/label and (re)starts its pulse animation for the given state. */
    private fun setState(state: RecState, label: String) {
        if (!::statusDot.isInitialized) return
        pulseAnimators.forEach { it.cancel() }
        pulseAnimators = emptyList()
        statusDot.scaleX = 1f; statusDot.scaleY = 1f; statusDot.alpha = 1f
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(state.color)
        }
        statusText.text = label

        if (state.pulseMs > 0) {
            val scaleX = ObjectAnimator.ofFloat(statusDot, View.SCALE_X, 1f, 1.6f).apply {
                duration = state.pulseMs; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            }
            val scaleY = ObjectAnimator.ofFloat(statusDot, View.SCALE_Y, 1f, 1.6f).apply {
                duration = state.pulseMs; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            }
            val alpha = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.4f).apply {
                duration = state.pulseMs; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            }
            pulseAnimators = listOf(scaleX, scaleY, alpha)
            pulseAnimators.forEach { it.interpolator = DecelerateInterpolator(); it.start() }
        } else if (state == RecState.ERROR) {
            // Brief flash back to idle after showing the error color.
            statusDot.postDelayed({ setState(RecState.IDLE, "Idle") }, 2000)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(rx) } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        currentToast?.cancel(); currentToast = null
        super.onDestroy()
    }

    /** Hand the picked WAV to QnnMoonshine via its MainActivity trampoline.
     *  We can NOT call startForegroundService() across packages on Android 12+:
     *  the QnnMoonshine process is in the background when its service is
     *  created, so startForeground() is rejected with
     *  "mAllowStartForeground false". Instead we launch QnnMoonshine's
     *  (headless) MainActivity, which is then in the foreground and can
     *  legally start its own foreground service. */
    private fun sendFile(uri: Uri) {
        Log.d(TAG, "sendFile() uri=$uri scheme=${uri.scheme} authority=${uri.authority}")
        appendLog("⏳ Sending file...\n")

        // Probe: can WE open the URI? If not, QnnMoonshine certainly can't.
        try {
            contentResolver.openInputStream(uri).use { ins ->
                val avail = ins?.available() ?: -1
                Log.d(TAG, "openInputStream OK, available=$avail")
            }
        } catch (e: Exception) {
            Log.e(TAG, "openInputStream FAILED", e)
            appendLog("⚠️  Cannot open file: ${e.message}\n")
            return
        }

        val pkg = "com.example.qnnmoonshine"
        val cls = "com.example.qnnmoonshine.TransparentLauncherActivity"
        try {
            val info = packageManager.getPackageInfo(pkg, 0)
            Log.d(TAG, "QnnMoonshine installed: ${info.versionName} (${info.longVersionCode})")
        } catch (e: Exception) {
            Log.e(TAG, "QnnMoonshine NOT installed", e)
            appendLog("⚠️  QnnMoonshine not installed\n")
            return
        }

        try {
            grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Log.d(TAG, "grantUriPermission OK for $pkg")
        } catch (e: Exception) {
            Log.w(TAG, "grantUriPermission failed", e)
        }

        val i = Intent("com.example.qnnmoonshine.action.TRANSCRIBE_FILE")
            .setComponent(ComponentName(pkg, cls))
            .setData(uri)
            .putExtra("uri", uri)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra("transparent", true)
            .putExtra("headless", true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val resolved = packageManager.resolveActivity(i, 0)
        Log.d(TAG, "resolveActivity -> $resolved")
        if (resolved == null) {
            appendLog("⚠️  Target activity not found\n")
            return
        }

        try {
            startActivity(i)
            Log.d(TAG, "startActivity dispatched OK")
        } catch (e: Exception) {
            Log.e(TAG, "startActivity FAILED", e)
            appendLog("⚠️  ${e.message}\n")
        }
    }
}
