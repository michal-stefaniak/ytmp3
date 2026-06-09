#!/usr/bin/env bash
set -e

export ANDROID_HOME=/home/pc-linux/android-sdk
export PATH=$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin

APK="app/build/outputs/apk/debug/app-debug.apk"

echo "Starting emulator..."
emulator -avd ytmp3_test -no-snapshot-load &
EMULATOR_PID=$!

echo "Waiting for device to boot (can take 1-2 min)..."
adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    sleep 3
    echo "  still booting..."
done

echo "Installing APK..."
adb install -r "$APK"

echo "Launching app..."
adb shell am start -n com.ytmp3/.MainActivity

echo ""
echo "App running. Files saved to: /sdcard/Android/data/com.ytmp3/files/Music/"
echo "Check files: adb shell ls /sdcard/Android/data/com.ytmp3/files/Music/"
echo ""
echo "Press Ctrl+C to stop watching (emulator keeps running)"
wait $EMULATOR_PID
