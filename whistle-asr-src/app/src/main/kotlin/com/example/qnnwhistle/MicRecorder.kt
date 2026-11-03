package com.example.qnnwhistle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

/**
 * Continuous mic capture with energy-based VAD.
 *
 * Only Bluetooth SCO and USB audio input are supported (device has no built-in mic).
 * If neither is connected, [start] calls [onError] and does nothing.
 *
 * For Bluetooth SCO: starts SCO on AudioManager and waits for connection before
 * opening AudioRecord, then stops SCO on release.
 *
 * Architecture:
 *   Recording coroutine (IO) → Channel<ShortArray> → Inference coroutine (IO)
 *
 * VAD state machine per 100ms frame:
 *   IDLE     → SPEAKING  : RMS ≥ ENERGY_THRESHOLD
 *   SPEAKING → IDLE      : RMS < ENERGY_THRESHOLD for SILENCE_FRAMES consecutive frames
 *   SPEAKING             : force-flush if segment reaches MAX_FRAMES (6s)
 */
class MicRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val decoder: Transcriber,
    private val onResult: (String) -> Unit,   // called on Main thread — final text for a finished utterance
    private val onStatus: (String) -> Unit,   // called on Main thread
    private val onError:  (String) -> Unit,   // called on Main thread
    private val onPartial: (String) -> Unit = {}  // called on Main thread — live, in-progress text while still speaking
) {
    companion object {
        private const val TAG               = "MicRecorder"
        private const val SAMPLE_RATE       = 16_000
        private const val FRAME_SAMPLES     = 1_600          // 100ms per VAD frame
        private const val PREROLL_FRAMES    = 3              // 300ms pre-roll before speech onset
        // 1200ms silence to end an utterance. 8 frames (800ms) was tried before and
        // cut off trailing words too eagerly; 20 (2000ms) felt sluggish to the user.
        // 12 is a middle ground — tune further if either symptom reappears.
        private const val SILENCE_FRAMES    = 12
        private const val MAX_FRAMES        = 60             // 6s hard cap (matches QNN encoder)
        private const val MIN_SPEECH_FRAMES = 5              // ignore <500ms noise bursts
        private const val SCO_TIMEOUT_MS    = 10_000L
        private const val CALIBRATION_FRAMES = 20            // 2s to capture ambient noise spikes
        // How often (in VAD frames) to re-decode the in-progress utterance and
        // surface a live partial result while the user is still talking.
        private const val PARTIAL_INTERVAL_FRAMES = 10        // ~1000ms
        private const val PARTIAL_MIN_SPEECH_FRAMES = MIN_SPEECH_FRAMES
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val segmentCh = Channel<ShortArray>(capacity = 4)

    // The QNN/ONNX decoder is not safe for concurrent invocation (single inference
    // context) — live partial decodes and the final end-of-utterance decode must
    // never run at the same time. The final decode (via segmentCh/inferJob) always
    // takes the lock; partial decodes use tryLock so they just skip a cycle instead
    // of queuing up and falling behind if the final decode is already running.
    private val decodeMutex = Mutex()

    private var recordJob: Job? = null
    private var inferJob: Job? = null
    private var partialJob: Job? = null
    private var usingBtSco = false

    // Sensitivity-driven VAD thresholds (read from Prefs at construction).
    private val energyFloor: Float
    private val speechMultiplier: Float

    init {
        val (floor, mult) = Prefs.vadParams(context)
        energyFloor = floor
        speechMultiplier = mult
        Log.i(TAG, "VAD params: sensitivity=${Prefs.getSensitivity(context)} " +
                "floor=$energyFloor multiplier=$speechMultiplier")
    }

    fun start() {
        check(recordJob == null) { "MicRecorder already started" }

        inferJob = scope.launch(Dispatchers.IO) {
            for (segment in segmentCh) {
                Log.i(TAG, "Inference: got segment ${segment.size} samples")
                withContext(Dispatchers.Main) {
                    onStatus("Transcribing (${segment.size / SAMPLE_RATE}s)…")
                }
                // Final decode always wins over an in-flight partial: cancel any
                // partial job and wait for the lock rather than tryLock-skipping,
                // since dropping the final result is not acceptable.
                partialJob?.cancel()
                val text = try {
                    decodeMutex.withLock { decoder.transcribe(segment) }
                } catch (e: Exception) {
                    Log.e(TAG, "Transcription error", e)
                    "(error)"
                }
                Log.i(TAG, "Inference result: '$text'")
                withContext(Dispatchers.Main) {
                    if (text.isNotBlank()) onResult(text)
                    onStatus("Listening…")
                }
            }
        }

        recordJob = scope.launch(Dispatchers.IO) {
            try {
                val inputDevice = findSupportedInputDevice()
                if (inputDevice == null) {
                    withContext(Dispatchers.Main) {
                        onError("No supported audio input found.\nConnect a Bluetooth or USB audio device.")
                    }
                    segmentCh.close()
                    return@launch
                }

                val isBt = inputDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                if (isBt) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // API 31+: use availableCommunicationDevices to find the BT SCO device.
                        @Suppress("UNCHECKED_CAST")
                        val btCommDevice = audioManager.availableCommunicationDevices
                            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

                        if (btCommDevice == null) {
                            withContext(Dispatchers.Main) {
                                onError("Bluetooth SCO device not available for communication. Try reconnecting headset.")
                            }
                            segmentCh.close()
                            return@launch
                        }

                        Log.i(TAG, "BT comm device: ${btCommDevice.productName} portId=${btCommDevice.id}")
                        @Suppress("UNCHECKED_CAST")
                        val ok = audioManager.setCommunicationDevice(btCommDevice)
                        if (ok) {
                            usingBtSco = true
                            Log.i(TAG, "BT SCO set via setCommunicationDevice, waiting for SCO audio to connect")
                            // HFP handshake duration varies by headset (observed ~1.2s on some
                            // units); wait for the actual SCO_AUDIO_STATE_CONNECTED broadcast
                            // instead of a fixed guess-delay, so we don't race AudioRecord.create()
                            // against AudioFlinger before the link is really up.
                            val connected = waitForScoConnected(SCO_TIMEOUT_MS)
                            if (!connected) {
                                Log.w(TAG, "SCO audio did not report CONNECTED within ${SCO_TIMEOUT_MS}ms, proceeding anyway")
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                onError("Failed to activate Bluetooth audio. Try reconnecting headset.")
                            }
                            segmentCh.close()
                            return@launch
                        }
                    } else {
                        // API 29-30: use legacy startBluetoothSco
                        @Suppress("DEPRECATION")
                        audioManager.startBluetoothSco()
                        usingBtSco = true
                        Log.i(TAG, "BT SCO started (legacy API), waiting for SCO audio to connect")
                        waitForScoConnected(SCO_TIMEOUT_MS)
                    }
                }

                runRecordLoop(inputDevice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                withContext(Dispatchers.Main) { onError("Mic error: ${e.message}") }
            } finally {
                if (usingBtSco) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        @Suppress("UNCHECKED_CAST")
                        audioManager.clearCommunicationDevice()
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.stopBluetoothSco()
                    }
                    audioManager.mode = AudioManager.MODE_NORMAL
                    usingBtSco = false
                    Log.i(TAG, "BT communication device cleared, mode=NORMAL")
                }
                segmentCh.close()
            }
        }
    }

    fun stop() {
        recordJob?.cancel()
        recordJob = null
        inferJob = null
    }

    /**
     * Suspends until AudioManager reports SCO_AUDIO_STATE_CONNECTED (actual HFP audio
     * link up), or returns false after [timeoutMs]. Avoids racing AudioRecord.create()
     * against the HFP handshake, whose duration varies by headset (observed to take
     * up to ~1.2s on some units — longer than a fixed guess-delay can reliably cover).
     */
    private suspend fun waitForScoConnected(timeoutMs: Long): Boolean {
        if (audioManager.isBluetoothScoOn) return true
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                lateinit var receiver: BroadcastReceiver
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        Log.i(TAG, "SCO audio state update: $state")
                        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED && cont.isActive) {
                            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                            cont.resume(true) {}
                        }
                    }
                }
                val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(receiver, filter)
                }
                cont.invokeOnCancellation {
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                }
            }
        } ?: false
    }

    /**
     * Returns the best available input device: BT SCO preferred, then USB.
     * Returns null if neither is present.
     */
    private fun findSupportedInputDevice(): AudioDeviceInfo? {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        Log.i(TAG, "Input devices: ${inputs.map { "${it.productName}(type=${it.type})" }}")

        val usbTypes = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        )
        return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: inputs.firstOrNull { it.type in usbTypes }
    }

    private suspend fun runRecordLoop(preferredDevice: AudioDeviceInfo) {
        val audioSource = if (usingBtSco)
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else
            MediaRecorder.AudioSource.VOICE_RECOGNITION

        // BT SCO may only support 8kHz (narrowband) or 16kHz (wideband). Probe both.
        val candidateRates = if (usingBtSco) intArrayOf(16_000, 8_000) else intArrayOf(SAMPLE_RATE)
        var captureRate = -1
        var ar: AudioRecord? = null
        try {
            for (rate in candidateRates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) { Log.i(TAG, "getMinBufferSize failed for ${rate}Hz"); continue }
                val ringBuf = maxOf(minBuf, rate * 2)
                @Suppress("DEPRECATION")
                val candidate = AudioRecord(
                    audioSource, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, ringBuf)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    ar = candidate
                    captureRate = rate
                    break
                } else {
                    candidate.release()
                    Log.i(TAG, "AudioRecord init failed at ${rate}Hz, trying next")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission denied", e)
            withContext(Dispatchers.Main) {
                onError("Microphone permission denied. Please enable it in app settings.")
            }
            segmentCh.close()
            return
        }
        requireNotNull(ar) { "AudioRecord init failed at all rates for ${preferredDevice.productName}" }

        // For USB: pin to the specific device.
        // For BT SCO: also set preferred device to the BT input explicitly.
        ar.setPreferredDevice(preferredDevice)

        ar.startRecording()
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "AudioRecord.startRecording() failed — recordingState=${ar.recordingState}")
            withContext(Dispatchers.Main) {
                onError("Mic could not start. App may be backgrounded — open the app once, then try again.")
            }
            ar.release()
            segmentCh.close()
            return
        }
        Log.i(TAG, "Recording on '${preferredDevice.productName}' at ${captureRate}Hz state=${ar.recordingState}")

        try {
            // Capture frames match the actual capture rate (100ms per frame).
            val captureFrameSamples = captureRate / 10
            val frame = ShortArray(captureFrameSamples)
            var framePos = 0
            val preroll = ArrayDeque<ShortArray>(PREROLL_FRAMES + 1)
            val speech  = ArrayList<ShortArray>(MAX_FRAMES)
            var silenceCount = 0
            var speaking = false
            var framesSincePartial = 0

            // Calibrate noise floor over CALIBRATION_FRAMES (2s). We use a high
            // percentile (not the raw max) of the per-frame RMS values: a single
            // loud transient during calibration (e.g. a BT SCO connect pop/click)
            // can otherwise skew the max to several times the real ambient noise,
            // pushing the speech threshold so high that normal speech never
            // crosses it and the session appears to hang forever "listening".
            // The 90th percentile still captures steady ambient noise spikes
            // while ignoring a single one-off outlier frame.
            var calibFrames = 0
            val calibEnergies = FloatArray(CALIBRATION_FRAMES)
            var threshold = energyFloor
            withContext(Dispatchers.Main) { onStatus("Calibrating mic (2s)…") }

            while (currentCoroutineContext().isActive) {
                val n = ar.read(frame, framePos, captureFrameSamples - framePos, AudioRecord.READ_BLOCKING)
                if (n < 0) { Log.e(TAG, "AudioRecord.read error: $n"); break }
                framePos += n
                if (framePos < captureFrameSamples) continue
                framePos = 0

                // Resample 8kHz → 16kHz by linear interpolation if needed.
                val modelFrame = if (captureRate == SAMPLE_RATE) frame
                                 else upsample2x(frame)

                val energy = rms(modelFrame)

                if (calibFrames < CALIBRATION_FRAMES) {
                    calibEnergies[calibFrames] = energy
                    calibFrames++
                    if (calibFrames == CALIBRATION_FRAMES) {
                        calibEnergies.sort()
                        val p90Index = ((CALIBRATION_FRAMES - 1) * 0.9f).toInt()
                        val noiseCeiling = calibEnergies[p90Index]
                        threshold = maxOf(energyFloor, noiseCeiling * speechMultiplier)
                        Log.i(TAG, "Noise p90=$noiseCeiling (max=${calibEnergies.last()})  speech threshold=$threshold")
                        withContext(Dispatchers.Main) { onStatus("Listening…") }
                    }
                    continue   // don't start VAD until calibration is done
                }

                val voiced = energy >= threshold

                if (!speaking) {
                    if (preroll.size >= PREROLL_FRAMES) preroll.removeFirst()
                    preroll.addLast(modelFrame.copyOf())
                    if (voiced) {
                        speaking = true
                        silenceCount = 0
                        framesSincePartial = 0
                        speech.addAll(preroll)
                        preroll.clear()
                        Log.i(TAG, ">>> SPEECH ONSET: energy=${"%.1f".format(energy)} threshold=${"%.1f".format(threshold)} (preroll=${speech.size} frames)")
                    }
                } else {
                    speech.add(modelFrame.copyOf())
                    if (!voiced) {
                        silenceCount++
                        Log.d(TAG, "Silence frame $silenceCount/$SILENCE_FRAMES: energy=${"%.1f".format(energy)}")
                        if (silenceCount >= SILENCE_FRAMES) {
                            Log.i(TAG, "<<< SPEECH END: ${speech.size} frames (${"%.2f".format(speech.size * 100f / SAMPLE_RATE)}s)")
                            flushSegment(speech)
                            speech.clear()
                            speaking = false
                            silenceCount = 0
                        }
                    } else {
                        if (silenceCount > 0) {
                            Log.d(TAG, "Silence broken by speech: energy=${"%.1f".format(energy)}")
                        }
                        silenceCount = 0
                    }
                    if (speech.size >= MAX_FRAMES) {
                        Log.i(TAG, "Max segment reached (${speech.size} frames) — flushing")
                        flushSegment(speech)
                        speech.clear()
                        silenceCount = 0
                    }

                    // Live partial: re-decode the in-progress utterance every
                    // ~1s so the caller can show updating text while the user
                    // is still talking, without waiting for the end-of-speech
                    // silence timeout. Skipped (not queued) if a decode is
                    // already in flight, so partials never pile up or race
                    // the eventual final decode.
                    if (speaking) {
                        framesSincePartial++
                        if (framesSincePartial >= PARTIAL_INTERVAL_FRAMES &&
                            speech.size >= PARTIAL_MIN_SPEECH_FRAMES &&
                            partialJob?.isActive != true
                        ) {
                            framesSincePartial = 0
                            val snapshot = concatFrames(speech)
                            partialJob = scope.launch(Dispatchers.IO) {
                                if (decodeMutex.tryLock()) {
                                    try {
                                        val text = try {
                                            decoder.transcribe(snapshot)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Partial transcription error", e)
                                            null
                                        }
                                        if (!text.isNullOrBlank()) {
                                            withContext(Dispatchers.Main) { onPartial(text) }
                                        }
                                    } finally {
                                        decodeMutex.unlock()
                                    }
                                } else {
                                    Log.d(TAG, "Skipping partial decode — final decode in progress")
                                }
                            }
                        }
                    }
                }
            }

            if (speaking && speech.size >= MIN_SPEECH_FRAMES) flushSegment(speech)

        } finally {
            ar.stop()
            ar.release()
            Log.i(TAG, "AudioRecord released")
        }
    }

    /** Flattens a snapshot of VAD frames into one contiguous PCM buffer, without
     *  mutating the source list — used both for the final flush and for
     *  live partial decodes mid-utterance. */
    private fun concatFrames(frames: List<ShortArray>): ShortArray {
        val out = ShortArray(frames.sumOf { it.size })
        var pos = 0
        for (f in frames) { f.copyInto(out, pos); pos += f.size }
        return out
    }

    private suspend fun flushSegment(frames: List<ShortArray>) {
        if (frames.size < MIN_SPEECH_FRAMES) return
        val out = concatFrames(frames)
        
        val durationSec = out.size / 16000.0  // Always 16kHz after upsampling
        Log.i(TAG, "Sending segment: ${frames.size} frames, ${out.size} samples = ${String.format("%.2f", durationSec)}s")
        
        // Diagnostic: verify samples are not all zeros
        val samplesMin = out.minOrNull() ?: 0
        val samplesMax = out.maxOrNull() ?: 0
        val samplesAbsMax = maxOf(kotlin.math.abs(samplesMin.toInt()), kotlin.math.abs(samplesMax.toInt()))
        Log.i(TAG, "  Sample range: [$samplesMin, $samplesMax] absMax=$samplesAbsMax")
        
        if (samplesAbsMax < 10) {
            Log.w(TAG, "  ⚠️ WARNING: All samples are near-zero! This will decode as garbage")
        }
        
        // CRITICAL: Save the exact audio being sent to inference for debugging
        AudioDumper.savePcm(out, "mic_segment_${System.currentTimeMillis()}")
        
        segmentCh.send(out)
        Log.i(TAG, "Segment sent to inference queue")
    }

    /** Linear interpolation 2× upsample (8kHz → 16kHz). */
    private fun upsample2x(src: ShortArray): ShortArray {
        val dst = ShortArray(src.size * 2)
        for (i in src.indices) {
            dst[i * 2] = src[i]
            val next = if (i + 1 < src.size) src[i + 1] else src[i]
            dst[i * 2 + 1] = ((src[i].toInt() + next.toInt()) / 2).toShort()
        }
        return dst
    }

    private fun rms(buf: ShortArray): Float {
        var sum = 0.0
        for (s in buf) sum += s.toLong() * s
        return sqrt(sum / buf.size).toFloat()
    }
}