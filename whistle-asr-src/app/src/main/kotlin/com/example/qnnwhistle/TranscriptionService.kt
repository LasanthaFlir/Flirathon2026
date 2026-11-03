package com.example.qnnwhistle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the [WhistleDecoder] and runs continuous mic
 * transcription. Controlled via Intent actions:
 *
 *   ACTION_INIT    — load model only (no mic). Service runs as a normal
 *                    (non-foreground) service kept alive by bound clients.
 *   ACTION_START   — load model if needed AND begin mic listening (foreground).
 *   ACTION_STOP    — stop mic listening; foreground notification removed.
 *
 * Bound clients (e.g. [MainActivity]) get a [LocalBinder] that exposes the
 * decoder via [transcribeSamples] for the WAV-file path.
 *
 * Broadcasts:
 *   ACTION_READY  — decoder loaded, ready for mic or transcribe calls
 *   ACTION_RESULT — final transcript text (extra [EXTRA_TEXT])
 *   ACTION_STATUS — UI-friendly status string (extra [EXTRA_TEXT])
 *   ACTION_ERROR  — fatal error (extra [EXTRA_TEXT])
 *
 * External apps / tasker / shell can trigger mic listening with:
 *   Intent("com.example.qnnwhistle.action.START_LISTENING")
 *      .setPackage("com.example.qnnwhistle")
 *   ContextCompat.startForegroundService(context, intent)
 */
@android.annotation.SuppressLint("MissingPermission")
class TranscriptionService : Service() {

    companion object {
        private const val TAG = "TranscriptionService"

        const val ACTION_START = "com.example.qnnwhistle.action.START_LISTENING"
        const val ACTION_STOP  = "com.example.qnnwhistle.action.STOP_LISTENING"
        const val ACTION_INIT  = "com.example.qnnwhistle.action.INIT"
        /** Background file transcription. URI in intent.data, [EXTRA_URI],
         *  or [Intent.EXTRA_STREAM]. Service briefly enters foreground with
         *  dataSync type, runs decode, broadcasts RESULT, then leaves
         *  foreground. */
        const val ACTION_TRANSCRIBE_FILE = "com.example.qnnwhistle.action.TRANSCRIBE_FILE_BG"
        const val EXTRA_URI = "uri"

        const val ACTION_RESULT = "com.example.qnnwhistle.RESULT"
        const val ACTION_STATUS = "com.example.qnnwhistle.STATUS"
        const val ACTION_ERROR  = "com.example.qnnwhistle.ERROR"
        const val ACTION_READY  = "com.example.qnnwhistle.READY"
        /** Live, in-progress transcript while the user is still talking (extra [EXTRA_TEXT]).
         *  Superseded by the next ACTION_PARTIAL or the final ACTION_RESULT — don't append. */
        const val ACTION_PARTIAL = "com.example.qnnwhistle.PARTIAL"
        const val EXTRA_TEXT    = "text"

        private const val CHANNEL_ID = "qnn_whistle_transcribe"
        private const val NOTIF_ID   = 0x7757
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var initJob: Job? = null
    private var decoder: Transcriber? = null
    private var micRecorder: MicRecorder? = null
    private var listening = false

    /** Binder for in-process clients (e.g. [MainActivity] for the WAV path). */
    inner class LocalBinder : Binder() {
        fun getService(): TranscriptionService = this@TranscriptionService
    }
    private val localBinder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = localBinder

    /** True once the decoder is loaded. */
    fun isReady(): Boolean = decoder != null

    private fun createDecoder(): Transcriber = when (Prefs.getEngine(this)) {
        Prefs.ENGINE_MOONSHINE_V2 -> MoonshineV2Decoder(this)
        else -> WhistleDecoder(this)
    }

    /**
     * Run inference on a PCM16 mono 16 kHz buffer. Blocking — call from a
     * background thread/coroutine. Returns "" if not yet initialised.
     */
    fun transcribeSamples(samples: ShortArray): String =
        decoder?.transcribe(samples) ?: ""

    /** Headless WAV file → text. Loads decoder if needed, reads URI, decodes,
     *  broadcasts RESULT (or ERROR). Service does NOT enter foreground. */
    private fun transcribeUriInBackground(uri: android.net.Uri) {
        scope.launch {
            broadcastStatus("Loading file…")
            // Make sure decoder is ready first.
            if (decoder == null) {
                val err = withContext(Dispatchers.IO) {
                    try { decoder = createDecoder(); null }
                    catch (e: Exception) { e.message ?: "init failed" }
                }
                if (err != null) { broadcastError("Init error: $err"); return@launch }
                broadcastReady()
            }
            val text = withContext(Dispatchers.IO) {
                try {
                    val wav = WavReader.read(uri, this@TranscriptionService)
                        ?: return@withContext "Failed to read WAV"
                    if (wav.sampleRate != 16_000)
                        return@withContext "Need 16 kHz WAV, got ${wav.sampleRate} Hz"
                    Log.i(TAG, "File transcribe: uri=$uri samples=${wav.samples.size}")
                    decoder!!.transcribe(wav.samples)
                } catch (e: Exception) {
                    Log.e(TAG, "File transcribe failed", e)
                    "Error: ${e.message}"
                }
            }
            if (text.startsWith("Error") || text.startsWith("Failed") || text.startsWith("Need")) {
                broadcastError(text)
            } else if (text.isNotBlank()) {
                broadcastResult(text)
                broadcastStatus("Done")
            } else {
                broadcastStatus("Empty result")
            }
            // Leave foreground state if we ever entered it (no-op otherwise),
            // and stop the service so the process can exit when no clients
            // are bound.
            if (!listening) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AudioDumper.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                // Service will fully stop once all clients unbind.
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_INIT -> {
                ensureInit(startListeningAfter = false)
            }
            ACTION_TRANSCRIBE_FILE -> {
                val uri: android.net.Uri? = intent.data
                    ?: intent.getParcelableExtra(EXTRA_URI)
                    ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (uri == null) {
                    broadcastError("TRANSCRIBE_FILE: no URI provided")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // NO startForeground here — file decode is short (< 10s
                // typical) and Android 14's `dataSync` FGS type requires the
                // caller to itself hold a dataSync FGS grant, which our
                // trampoline activity doesn't have. A plain started service
                // gets enough background-execution window from the
                // foreground caller's startService() call.
                Log.i(TAG, "TRANSCRIBE_FILE: uri=$uri (background mode)")
                transcribeUriInBackground(uri)
            }
            ACTION_START, null -> {
                if (!hasMicPermission()) {
                    Log.e(TAG, "RECORD_AUDIO not granted — refusing to start mic service")
                    broadcastError("Microphone permission not granted")
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startForegroundCompat("Loading Whistle…")
                } catch (e: Exception) {
                    Log.e(TAG, "startForeground failed", e)
                    broadcastError("Cannot start mic service: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureInit(startListeningAfter = true)
            }
            else -> {
                if (!hasMicPermission()) {
                    broadcastError("Microphone permission not granted")
                    stopSelf()
                    return START_NOT_STICKY
                }
                try { startForegroundCompat("Loading Whistle…") }
                catch (e: Exception) {
                    broadcastError("Cannot start mic service: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureInit(startListeningAfter = true)
            }
        }
        return START_STICKY
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureInit(startListeningAfter: Boolean) {
        if (decoder != null) {
            broadcastReady()
            if (startListeningAfter && !listening) startListening()
            return
        }
        if (initJob?.isActive == true) return

        initJob = scope.launch {
            broadcastStatus("Loading model…")
            val err = withContext(Dispatchers.IO) {
                try { decoder = createDecoder(); null }
                catch (e: Exception) { e.message ?: "init failed" }
            }
            if (err != null) {
                Log.e(TAG, "Init error: $err")
                broadcastError("Init error: $err")
                stopSelf()
                return@launch
            }
            broadcastReady()
            broadcastStatus("Ready")
            if (startListeningAfter) startListening()
        }
    }

    private fun startListening() {
        if (listening) return
        val dec = decoder ?: return
        listening = true
        updateNotification("Listening…")
        broadcastStatus("Listening…")

        micRecorder = MicRecorder(
            context = this,
            scope = scope,
            decoder = dec,
            onResult = { text ->
                Log.i(TAG, "Result: $text")
                updateNotification("Last: ${text.take(40)}")
                broadcastResult(text)
            },
            onStatus = { broadcastStatus(it) },
            onError  = { msg ->
                broadcastError(msg)
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
            },
            onPartial = { text ->
                Log.d(TAG, "Partial: $text")
                broadcastPartial(text)
            }
        )
        micRecorder!!.start()
    }

    private fun stopListening() {
        listening = false
        micRecorder?.stop()
        micRecorder = null
    }

    override fun onDestroy() {
        stopListening()
        decoder?.close()
        decoder = null
        scope.cancel()
        super.onDestroy()
    }

    // ── Notification / foreground helpers ───────────────────────────────────

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Speech Transcription",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, TranscriptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("QnnWhistle")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun startForegroundCompat(
        text: String,
        type: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    ) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    // ── Broadcast helpers ───────────────────────────────────────────────────

    /**
     * Sends an event as TWO broadcasts:
     *  1. Internal (setPackage = us)  — for [MainActivity]'s receiver.
     *  2. External (no package)       — so any other installed app that
     *     registered a receiver for this action can pick it up.
     */
    private fun broadcastBoth(action: String, text: String? = null) {
        val mk = {
            Intent(action).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                if (text != null) putExtra(EXTRA_TEXT, text)
            }
        }
        sendBroadcast(mk().setPackage(packageName))   // internal
        sendBroadcast(mk())                            // external
    }

    private fun broadcastResult(text: String) = broadcastBoth(ACTION_RESULT, text)
    private fun broadcastStatus(text: String) = broadcastBoth(ACTION_STATUS, text)
    private fun broadcastError(text: String)  = broadcastBoth(ACTION_ERROR, text)
    private fun broadcastPartial(text: String) = broadcastBoth(ACTION_PARTIAL, text)
    private fun broadcastReady()              = broadcastBoth(ACTION_READY)
}
