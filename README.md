# Flirathon2026 — Whistle On-Device Speech Recognition

**Whistle** is an on-device, offline speech-to-text engine for Android,
exposed as a standard Android `RecognitionService`. It runs fully on-device
(no cloud/network required) using a CPU-based ASR model. This repo ships a
ready-to-install build plus a demo client and integration guide for
third-party developers.

| File | What it is |
|---|---|
| `apks/QnnWhistle-RecognitionService.apk` | The Whistle app — runs the recognition service and the normal app UI. ~34MB, Whistle-only build. |
| `apks/RecognitionConsumer.apk` | A small demo app showing 3 different ways to call Whistle from your own app. |
| `consumer-app-src/` | Full Kotlin source for the demo app above. |
| `whistle-asr-src/` | Full source for the Whistle app itself (models excluded — see `whistle-asr-src/app/src/main/assets/MODELS_README.md`). |
| `INTEGRATION_GUIDE.md` | Code-level guide for developers who want to call Whistle from their own app. |

This device has **no built-in microphone** — audio input requires a
**Bluetooth headset (SCO/HFP)** or a **USB audio device**.

---

## 1. Install Whistle (the RecognitionService)

```bash
adb install -r apks/QnnWhistle-RecognitionService.apk
```

Then **open the Whistle app once** from the launcher (tap its icon). This is
required so you can grant the runtime permission prompts:
- **Microphone** — required to capture audio
- **Notifications** — required for the foreground-service status notification
- **Nearby devices / Bluetooth** (if prompted) — required to detect the
  connected headset


### Re-installing / testing fresh installs

Runtime permissions do **not** survive an uninstall — if you uninstall and
reinstall, re-grant them (or re-open the app and accept the prompts again)
before testing.

### Troubleshooting: consumer app keeps hitting a permission error

**Both apps need Microphone permission** — not just the one calling
`SpeechRecognizer`. Whistle itself records the audio on the client's behalf,
so if Whistle's own Microphone permission was never granted (e.g. it was
installed via `adb install` but never opened/accepted), any client calling
it will fail with `ERROR_INSUFFICIENT_PERMISSIONS` / "caller without
permission RECORD_AUDIO" — **even though the client app shows Microphone as
granted**. Check both:

```bash
adb shell dumpsys package com.example.qnnwhistle       | grep RECORD_AUDIO
adb shell dumpsys package com.example.moonshineconsumer | grep RECORD_AUDIO
```

If Whistle's says `granted=false`, open the Whistle app once and accept the
Microphone prompt (or `adb shell pm grant com.example.qnnwhistle
android.permission.RECORD_AUDIO`).

---

## 2. Connect a Bluetooth headset

1. pair a bluetooth headset as Ixx camera does not have a builtin Mic
---

## 3. Install and try the Consumer (demo) app

```bash
adb install -r apks/RecognitionConsumer.apk
```

Open it once to grant its **Microphone** permission prompt. The app has
buttons demonstrating the 3 integration paths described in
`INTEGRATION_GUIDE.md`:

- **Start Voice Recognition (SpeechRecognizer API)** — standard Android STT
  flow; Whistle is the device's default recognizer so this "just works".
- **Voice via RecognizerIntent** — launches Whistle's recognition Activity
  directly.
- **Transcribe File (broadcast)** — sends a WAV file to Whistle's background
  transcription action and receives the result via broadcast (note: this
  button currently targets an older reference package name — see the
  guide's troubleshooting notes).

With your Bluetooth headset connected, tap **Start Voice Recognition**,
speak, and the transcribed text appears in the app.

---

## 4. Building the consumer app from source

```bash
cd consumer-app-src
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

See `INTEGRATION_GUIDE.md` for the full API surface (actions, extras,
broadcast names) if you want to integrate Whistle into your own app instead.

---

## 5. Building Whistle itself from source

```bash
cd whistle-asr-src
# copy the 3 missing ONNX model files into app/src/main/assets/ first —
# see app/src/main/assets/MODELS_README.md
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---


