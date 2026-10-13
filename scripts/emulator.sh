#!/usr/bin/env bash
# Starts the dev emulator and waits for boot. Spotify can't log in on an emulator
# (Play Integrity), so use it for screens and previews that don't need App Remote.
#   scripts/emulator.sh            windowed
#   scripts/emulator.sh --headless
# SwiftShader rendering: hardware GPU mode can render black.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD="${AVD:-Pixel_10_Pro}"

args=(-avd "$AVD" -gpu swiftshader_indirect -no-audio -no-boot-anim -no-snapshot-save)
[ "${1:-}" = "--headless" ] && args+=(-no-window)

if "$ADB" devices | grep -q 'emulator.*device$'; then
  echo "An emulator is already running"
else
  "$EMULATOR" "${args[@]}" > "${TMPDIR:-/tmp}/remastered-emulator.log" 2>&1 &
fi

"$ADB" -e wait-for-device
until [ "$("$ADB" -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
echo "Booted: Android $("$ADB" -e shell getprop ro.build.version.release | tr -d '\r')"
