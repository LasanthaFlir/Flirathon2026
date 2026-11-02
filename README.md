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

Android does not auto-launch apps right after install, so this one manual
open is unavoidable on a plain `adb install` — after that first launch,
Whistle runs its recognition service in the background whenever another
app (or the device's default assistant/mic key) requests it.

> Tip: if you have adb access and want to skip the manual tap, you can
> pre-grant permissions instead:
> ```bash
> adb shell pm grant com.example.qnnwhistle android.permission.RECORD_AUDIO
> adb shell pm grant com.example.qnnwhistle android.permission.POST_NOTIFICATIONS
> adb shell pm grant com.example.qnnwhistle android.permission.BLUETOOTH_CONNECT
> adb shell am start -n com.example.qnnwhistle/.MainActivity
> ```

### Re-installing / testing fresh installs

Runtime permissions do **not** survive an uninstall — if you uninstall and
reinstall, re-grant them (or re-open the app and accept the prompts again)
before testing.

---

## 2. Connect a Bluetooth headset

1. On the device, go to **Settings → Bluetooth** and pair your headset
   (standard HFP/SCO headset — the kind used for phone calls, not an
   A2DP-only music speaker).
2. Once paired and connected, open Whistle — it automatically detects the
   Bluetooth SCO audio path and routes the mic through it. No extra
   in-app Bluetooth setting is needed.
3. If you see `"Connect a Bluetooth or USB audio device"` or
   `"Failed to activate Bluetooth audio. Try reconnecting headset"`:
   - Make sure the headset is actually **connected** (not just paired) —
     check the Bluetooth icon/status in the system notification shade.
   - Toggle Bluetooth off/on, or disconnect and reconnect the headset,
     then reopen Whistle.
   - A USB audio adapter/headset works as a fallback if you don't have a
     Bluetooth headset handy.

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

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Microphone permission" error even though it looks granted | Permission was likely reset by an uninstall/reinstall — re-grant it (see step 1) and reopen the app. |
| `ERROR_INSUFFICIENT_PERMISSIONS` (error 9) from `SpeechRecognizer` | Don't construct `SpeechRecognizer` with an explicit `ComponentName`; let it resolve to the system default (Whistle). |
| No audio captured / "No supported audio input found" | No Bluetooth SCO headset or USB audio device connected — see step 2. |
| App doesn't start automatically after install | Expected Android behavior — open it once manually, or use the `adb` pre-grant commands above. |
