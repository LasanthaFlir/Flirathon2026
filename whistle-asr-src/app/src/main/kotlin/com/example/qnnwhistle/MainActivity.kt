package com.example.qnnwhistle

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.qnnwhistle.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin UI shell. The decoder lives in [TranscriptionService] and is shared
 * between the mic path (foreground service) and the WAV file path (bound IPC).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** External apps can fire this to start mic transcription headlessly. */
        const val ACTION_START_MIC = "com.example.qnnwhistle.action.ACTIVITY_START_MIC"
        const val ACTION_STOP_MIC  = "com.example.qnnwhistle.action.ACTIVITY_STOP_MIC"
        /** External apps fire this with a URI in EXTRA_STREAM (or intent.data)
         *  to transcribe a WAV file. Result returns via the standard RESULT
         *  broadcast. */
        const val ACTION_TRANSCRIBE_FILE = "com.example.qnnwhistle.action.TRANSCRIBE_FILE"
        /** If true, activity finishes itself once mic has started. */
        const val EXTRA_HEADLESS   = "headless"
        /** If true, render the activity with a fully transparent theme so it
         *  doesn't visually replace the caller while we're processing. */
        const val EXTRA_TRANSPARENT = "transparent"
    }

    private lateinit var binding: ActivityMainBinding
    private var ready = false
    private var micRunning = false
    private var pendingHeadlessStart = false
    private var pendingMicStart = false
    private var transparentMode = false

    private var service: TranscriptionService? = null
    private var bound = false

    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { transcribeFile(it) } }

    private val requestMicPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingMicStart) {
            pendingMicStart = false
            startMicService()
        } else if (!granted) {
            pendingMicStart = false
            toast("Microphone permission required to use mic transcription")
        }
    }

    private val requestNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore result */ }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, b: IBinder) {
            service = (b as TranscriptionService.LocalBinder).getService()
            bound = true
            if (service?.isReady() == true) onReady()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                TranscriptionService.ACTION_READY -> onReady()
                TranscriptionService.ACTION_RESULT -> {
                    val text = intent.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: return
                    val prev = binding.tvResult.text.toString()
                    binding.tvResult.text = if (prev.isEmpty()) text else "$prev $text"
                }
                TranscriptionService.ACTION_STATUS -> {
                    val text = intent.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: return
                    binding.tvStatus.text = text
                }
                TranscriptionService.ACTION_PARTIAL -> {
                    val text = intent.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: return
                    // Live preview only — not appended to tvResult, which holds
                    // finalized text; superseded by the next partial or final result.
                    binding.tvStatus.text = "🗣 $text"
                }
                TranscriptionService.ACTION_ERROR -> {
                    val text = intent.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: return
                    toast(text)
                    onMicStopped()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // If launched with EXTRA_TRANSPARENT=true, switch to a fully see-through
        // theme BEFORE super.onCreate so the system applies it to the window.
        // This lets external apps drive us without their UI being covered.
        transparentMode = intent?.getBooleanExtra(EXTRA_TRANSPARENT, false) == true
        if (transparentMode) {
            setTheme(R.style.Theme_QnnWhistleKotlin_Transparent)
        }
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)

        if (transparentMode) {
            applyStripeWindow()
            // Don't show our buttons/labels — caller wants us invisible.
            supportActionBar?.hide()
            setContentView(android.widget.FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            })
        } else {
            setContentView(binding.root)
        }

        AudioDumper.init(this)

        // Wire up the buttons only when we have a visible UI.
        if (!transparentMode) {
            binding.btnInit.setOnClickListener { initModels() }
            binding.btnPick.setOnClickListener {
                if (!ready) { toast("Init models first"); return@setOnClickListener }
                pickAudio.launch(arrayOf("audio/*", "audio/wav", "audio/x-wav", "*/*"))
            }
            binding.btnMic.setOnClickListener {
                if (micRunning) stopMicService() else checkAndStartMic()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicPerm.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Bind for the lifetime of the activity (NOT the visibility lifecycle),
        // so the file-picker round-trip doesn't unbind the service and break
        // the WAV path while the user is choosing a file.
        bindService(
            Intent(this, TranscriptionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )

        handleLaunchIntent(intent)
    }

    override fun onDestroy() {
        if (bound) {
            try { unbindService(connection) } catch (_: Exception) {}
            bound = false
            service = null
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // We can't re-apply the window theme after onCreate, but we can make
        // our content invisible so the caller stays visually on top while we
        // process. Note: the window itself will not be transparent unless the
        // activity was originally launched with EXTRA_TRANSPARENT=true.
        if (intent.getBooleanExtra(EXTRA_TRANSPARENT, false)) {
            applyStripeWindow()
            supportActionBar?.hide()
            setContentView(android.widget.FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            })
        }
        handleLaunchIntent(intent)
    }

    /**
     * Resize the activity's window to a 1-dp wide vertical stripe on the
     * right edge of the screen. The activity must still exist (we need the
     * lifecycle for cross-package FGS grants and the broadcast pipeline) but
     * we shrink it so the user effectively cannot see it. Even a momentary
     * fill before the theme background takes effect is a 1-dp line, not a
     * full-screen white flash.
     */
    private fun applyStripeWindow() {
        val w = window ?: return
        val px = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val params = w.attributes.apply {
            width = px
            height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            x = 0
            y = 0
            // Don't dim or animate.
            dimAmount = 0f
            flags = flags or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            format = android.graphics.PixelFormat.TRANSLUCENT
        }
        w.attributes = params
        w.setBackgroundDrawableResource(android.R.color.transparent)
    }

    /**
     * Lets external apps drive the mic. They start this activity (so we are
     * briefly foreground, satisfying Android 12+ mic-FGS rules), and we
     * immediately kick off the service. With EXTRA_HEADLESS=true the activity
     * finishes itself once mic is running.
     */
    private fun handleLaunchIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_START_MIC -> {
                pendingHeadlessStart = intent.getBooleanExtra(EXTRA_HEADLESS, true)
                checkAndStartMic()
            }
            ACTION_STOP_MIC -> {
                stopMicService()
                if (intent.getBooleanExtra(EXTRA_HEADLESS, true)) finish()
            }
            ACTION_TRANSCRIBE_FILE -> {
                val uri = intent.data
                    ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri == null) {
                    toast("No URI provided")
                    if (intent.getBooleanExtra(EXTRA_HEADLESS, true)) finish()
                    return
                }
                val finishAfter = intent.getBooleanExtra(EXTRA_HEADLESS, true)
                transcribeFile(uri, finishAfter)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(TranscriptionService.ACTION_RESULT)
            addAction(TranscriptionService.ACTION_STATUS)
            addAction(TranscriptionService.ACTION_ERROR)
            addAction(TranscriptionService.ACTION_READY)
            addAction(TranscriptionService.ACTION_PARTIAL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceReceiver, f)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(serviceReceiver) } catch (_: Exception) {}
    }

    private fun onReady() {
        if (ready) return
        ready = true
        binding.tvStatus.text = getString(R.string.status_ready)
        binding.btnInit.text = getString(R.string.btn_enable_again)
        binding.tvInitSubtitle.visibility = android.view.View.GONE
        binding.btnPick.isEnabled = true
        binding.btnMic.isEnabled = true
    }

    // ── Init via service (decoder loads inside service) ──────────────────

    private fun initModels() {
        binding.tvStatus.text = getString(R.string.status_loading)
        // Send INIT action; bindService above keeps it alive for WAV use.
        val intent = Intent(this, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_INIT)
        startService(intent)
    }

    // ── Mic via foreground service ────────────────────────────────────────

    private fun checkAndStartMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startMicService()
        else {
            // Register a one-shot callback that starts the service on grant
            pendingMicStart = true
            requestMicPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startMicService() {
        micRunning = true
        binding.btnMic.text = getString(R.string.btn_mic_stop)
        binding.btnPick.isEnabled = false
        binding.tvStatus.text = "Starting service…"
        binding.tvResult.text = ""

        val intent = Intent(this, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)

        if (pendingHeadlessStart) {
            pendingHeadlessStart = false
            // Give the FGS a moment to enter foreground state before we leave.
            // Plain finish() pops us off the caller's task and reveals it.
            // Use a real Handler — in transparent mode binding.root is not attached.
            Handler(Looper.getMainLooper()).postDelayed(
                { if (!isFinishing) finish() }, 750
            )
        }
    }

    private fun stopMicService() {
        val intent = Intent(this, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_STOP)
        startService(intent)
        onMicStopped()
    }

    private fun onMicStopped() {
        micRunning = false
        binding.btnMic.text = getString(R.string.btn_mic_start)
        binding.btnPick.isEnabled = ready
        binding.tvStatus.text = getString(R.string.status_ready_short)
    }

    // ── WAV file (transcribe via bound service) ──────────────────────────

    private fun transcribeFile(uri: Uri, finishAfter: Boolean = false) {
        binding.tvStatus.text = "Reading audio…"
        binding.tvResult.text = ""
        lifecycleScope.launch {
            // Wait up to ~2s for the service to (re)bind after the file picker
            // round-trip. The bind is initiated in onCreate so this is normally
            // instant, but on slow devices it can race with the picker return.
            var svc = service
            var waited = 0
            while ((svc == null || !svc.isReady()) && waited < 2000) {
                kotlinx.coroutines.delay(50)
                waited += 50
                svc = service
            }
            if (svc == null || !svc.isReady()) {
                toast("Service not ready — press Init Models first")
                binding.tvStatus.text = "Ready"
                if (finishAfter) finish()
                return@launch
            }
            val text = withContext(Dispatchers.IO) {
                android.util.Log.i("MainActivity", "Reading WAV from: $uri")
                val wavData = WavReader.read(uri, this@MainActivity)
                    ?: return@withContext "Failed to read WAV file"

                android.util.Log.i("MainActivity", "WAV loaded: ${wavData.samples.size} samples, ${wavData.sampleRate}Hz")

                if (wavData.sampleRate != 16000)
                    return@withContext "Need 16 kHz WAV, got ${wavData.sampleRate} Hz"

                val durationSec = wavData.samples.size / 16000
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Transcribing (${durationSec}s audio)…"
                }
                svc.transcribeSamples(wavData.samples)
            }
            binding.tvStatus.text = "Done"
            binding.tvResult.text = text

            // Broadcast so external apps (consumer) receive the file's transcript
            // through the same RESULT channel as mic transcripts. Deliberately NOT
            // sending an internal (own-package) broadcast here: tvResult.text was
            // already set directly above, and serviceReceiver's ACTION_RESULT handler
            // *appends* to the existing text (for incremental mic results) -- self-
            // broadcasting on top of the direct set caused the transcript to be
            // displayed twice ("text text") for every file transcription.
            if (text.isNotBlank()) {
                val out = Intent(TranscriptionService.ACTION_RESULT)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(TranscriptionService.EXTRA_TEXT, text)
                sendBroadcast(Intent(out).setPackage(null))          // external (any listener)
                // Also explicitly target the caller, in case package-visibility
                // filtering otherwise drops the broadcast.
                callingActivity?.packageName?.let {
                    sendBroadcast(Intent(out).setPackage(it))
                }
                referrer?.host?.let {
                    sendBroadcast(Intent(out).setPackage(it))
                }
                android.util.Log.i("MainActivity",
                    "Broadcast RESULT to all + caller=${callingActivity?.packageName} referrer=${referrer?.host}")
            }
            if (finishAfter) {
                // We share the caller's task (taskAffinity=""), so a plain
                // finish() pops only our activity and reveals the caller
                // underneath. Don't use moveTaskToBack/finishAndRemoveTask —
                // those would send the *whole* shared task away.
                // NOTE: in transparent mode binding.root is NOT attached to
                // the window, so binding.root.postDelayed() never fires.
                // Use a real Handler instead.
                Handler(Looper.getMainLooper()).postDelayed(
                    { if (!isFinishing) finish() }, 500
                )
            }
        }
    }

    /** Currently unused — kept for callers that don't share our task (e.g.
     *  third-party launchers passing FLAG_ACTIVITY_NEW_TASK). With our default
     *  empty taskAffinity, plain finish() is enough. */
    @Suppress("unused")
    private fun bringCallerToForeground() {
        val callerPkg = callingActivity?.packageName ?: referrer?.host
        if (callerPkg != null && callerPkg != packageName) {
            val launch = packageManager.getLaunchIntentForPackage(callerPkg) ?: return
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            try { startActivity(launch) } catch (_: Exception) {}
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        } else super.onOptionsItemSelected(item)
    }
}
