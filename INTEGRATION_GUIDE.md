# Integrating with Whistle's RecognitionService

> **APKs in this repo:** `apks/QnnWhistle-RecognitionService.apk` is a
> trimmed, Whistle-only build (~34MB) — it omits the optional Moonshine v2
> ONNX assets (~319MB) bundled in the full dev build, since Whistle is the
> only actively-supported engine. `apks/RecognitionConsumer.apk` is the demo
> client referenced throughout this guide. Install with
> `adb install -r apks/<name>.apk`, open each app once to grant the
> Microphone/Notifications permission prompts, then follow the paths below.

This guide shows third-party apps how to get speech-to-text from Whistle
(`com.example.qnnwhistle`) on this device. There are **three independent
ways** to call it, all demonstrated in the bundled **Recognition Consumer**
app (`QnnMoonshineKotlin/consumer-app/`). Pick whichever fits your use case.

Prerequisites for any of these:
- Whistle (`com.example.qnnwhistle`) must be installed on the device.
- Your app needs `android.permission.RECORD_AUDIO` in its manifest, and the
  user must grant it at runtime (mic audio is captured in *your app's*
  process context for paths 1 and 2 below, so Whistle can't request it for
  you).
- No network/cloud — everything runs on-device.

---

## Path 1 — Standard Android `SpeechRecognizer` API (recommended)

This is the normal Android speech API. It works as long as Whistle is set
as the device's default speech recognizer (`Settings.Secure` /
`voice_interaction_service`), which it already is on this image.

```kotlin
val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
// IMPORTANT: do NOT pass an explicit ComponentName (e.g.
// createSpeechRecognizer(context, ComponentName(...))). On Android 12+ that
// bypasses AttributionSource forwarding for non-default recognizers and the
// bind fails with ERROR_INSUFFICIENT_PERMISSIONS (error 9), even though you
// hold RECORD_AUDIO. Let it resolve to the system default instead.

recognizer.setRecognitionListener(object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) { /* mic is live */ }
    override fun onBeginningOfSpeech() { /* user started talking */ }
    override fun onEndOfSpeech() { /* now transcribing */ }
    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        // ... use `text` ...
    }
    override fun onError(error: Int) {
        // See SpeechRecognizer.ERROR_* constants. If you get
        // ERROR_INSUFFICIENT_PERMISSIONS even though checkSelfPermission()
        // says GRANTED, the mic app-op can be stuck (common after an adb
        // `pm grant`, which skips the normal consent flow) — send the user to
        // Settings > Apps > <your app> > Permissions > Microphone to re-toggle it.
    }
    // onRmsChanged / onBufferReceived / onPartialResults / onEvent: optional
})

val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
}
recognizer.startListening(intent)
// ... later ...
recognizer.stopListening()   // or recognizer.cancel()
recognizer.destroy()         // always get a fresh instance after onError()
```

Full working reference: `MainActivity.ensureRecognizer()` /
`startRecognizerSession()` / `stopRecognizerSession()` in the consumer app
(`consumer-app/app/src/main/kotlin/com/example/moonshineconsumer/MainActivity.kt`).

**Use this when:** you want a normal mic-driven dictation experience (push a
button, user speaks, you get text back) and don't need a visible
confirmation UI from the recognizer.

---

## Path 2 — `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` as an Activity

This is the classic "voice search" launcher intent. Unlike Path 1, it's
**Activity-based**, so it doesn't cross the AttributionSource boundary that
causes the Android-14 error-9 bug — any app can use it, regardless of
default-recognizer binding rules. Whistle exports a `VoiceRecognitionActivity`
that handles it.

```kotlin
private val recognizerLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        // ... use `text` ...
    }
}

val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now…")
}
try {
    recognizerLauncher.launch(intent)
} catch (e: ActivityNotFoundException) {
    // No app on the device handles ACTION_RECOGNIZE_SPEECH.
}
```

Full working reference: `MainActivity.launchRecognizerIntent()` +
`recognizerIntentLauncher` in the consumer app.

**Use this when:** Path 1 misbehaves on your target Android version/OEM, or
you want a simple "fire an intent, get an Activity result" integration with
no listener/lifecycle bookkeeping.

---

## Path 3 — Direct broadcast intents to `TranscriptionService` (advanced)

For apps that want low-level control (e.g. transcribing a pre-recorded WAV
file in the background, with no UI at all), you can address Whistle's
`TranscriptionService` directly. This requires your manifest to declare:

```xml
<queries>
    <package android:name="com.example.qnnwhistle" />
</queries>
```

**Actions you can send** (`Intent(ACTION).setPackage("com.example.qnnwhistle")`,
then `context.startForegroundService(intent)` or `startService(intent)`):

| Action | Purpose |
|---|---|
| `com.example.qnnwhistle.action.START_LISTENING` | Load the model (if needed) and start live mic listening |
| `com.example.qnnwhistle.action.STOP_LISTENING` | Stop mic listening |
| `com.example.qnnwhistle.action.TRANSCRIBE_FILE_BG` | Transcribe a WAV file in the background. Put the file `Uri` in `intent.data` (or extra key `"uri"`) |

**Broadcasts Whistle sends back** (register a `BroadcastReceiver` for these):

| Action | Meaning | Extra |
|---|---|---|
| `com.example.qnnwhistle.READY` | Decoder loaded, ready for mic/file calls | — |
| `com.example.qnnwhistle.STATUS` | UI-friendly status string (e.g. "Listening…", "Transcribing…") | `"text"` (String) |
| `com.example.qnnwhistle.RESULT` | Final transcript text | `"text"` (String) |
| `com.example.qnnwhistle.ERROR` | Fatal error message | `"text"` (String) |

Example — transcribe a WAV file in the background:

```kotlin
val filter = IntentFilter().apply {
    addAction("com.example.qnnwhistle.RESULT")
    addAction("com.example.qnnwhistle.STATUS")
    addAction("com.example.qnnwhistle.ERROR")
}
val receiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val text = i.getStringExtra("text") ?: ""
        when (i.action) {
            "com.example.qnnwhistle.RESULT" -> { /* final transcript in `text` */ }
            "com.example.qnnwhistle.ERROR"  -> { /* handle failure */ }
        }
    }
}
registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) // API 33+

val i = Intent("com.example.qnnwhistle.action.TRANSCRIBE_FILE_BG").apply {
    setPackage("com.example.qnnwhistle")
    data = wavFileUri   // content:// or file:// Uri to a 16kHz mono WAV
}
startForegroundService(i)
```

> **Note:** this path is intended for a caller that's already in the
> foreground (e.g. the user just picked a file from your UI). If you invoke
> it via `adb shell am start-foreground-service` directly (no real foreground
> caller), Android enforces a startup deadline and the service may be killed
> with `ForegroundServiceDidNotStartInTimeException` before it finishes —
> that's an artifact of how `adb` invokes it, not a bug in normal usage.

The consumer app's own "Pick WAV File" button (`sendFile()` in
`MainActivity.kt`) demonstrates this pattern, though it currently targets the
older `com.example.qnnmoonshine` package — treat it as a reference for the
intent shape, not a working example against Whistle as-is.

---

## Troubleshooting

- **"Microphone permission not granted" even after granting it in
  Settings**: the app may need re-granting after a fresh install/reinstall —
  runtime grants don't survive uninstall. Open the app once and re-allow
  the permission prompt.
- **`ERROR_INSUFFICIENT_PERMISSIONS` (error 9) from `SpeechRecognizer`**: see
  the comment in Path 1 above — either an AttributionSource/default-recognizer
  binding issue, or a stuck mic app-op that needs a manual Settings toggle.
- **Empty/looping/garbled transcripts for domain-specific words**: known
  limitation of the on-device model's vocabulary, not a pipeline bug — see
  the audio-diagnosis notes in this project's session history.
