#!/usr/bin/env bash
# Installs QnnWhistle, pre-grants its runtime permissions, and launches it
# once so the foreground service / RecognitionService are ready to use
# without the user having to tap anything on-device.
set -euo pipefail

APK="${1:-app-release.apk}"
PKG="com.example.qnnwhistle"

echo "Installing $APK ..."
adb install -r "$APK"

echo "Granting runtime permissions ..."
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell pm grant "$PKG" android.permission.BLUETOOTH_CONNECT || true

echo "Launching once ..."
adb shell am start -n "$PKG/.MainActivity"

echo "Done. $PKG is installed, permitted, and running."
