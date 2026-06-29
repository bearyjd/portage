#!/usr/bin/env bash
set -euo pipefail

serial="${ANDROID_SERIAL:-$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')}"
test -n "$serial" || { echo "No authorized Android device attached" >&2; exit 1; }

pkg="com.ventouxlabs.portage.recv"
role="android.app.role.SMS"
prior="$(adb -s "$serial" shell cmd role get-role-holders "$role" | tr -d '\r' | head -1)"

restore_role() {
  if test -n "$prior" && test "$prior" != "$pkg"; then
    adb -s "$serial" shell cmd role add-role-holder "$role" "$prior" >/dev/null || true
  elif test -z "$prior"; then
    adb -s "$serial" shell cmd role remove-role-holder "$role" "$pkg" >/dev/null || true
  fi
  adb -s "$serial" uninstall "$pkg.test" >/dev/null 2>&1 || true
}
trap restore_role EXIT

export ANDROID_SERIAL="$serial"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/portage-gradle}"

./gradlew :app-recv:assembleDegoogleDebug :app-recv:assembleDegoogleDebugAndroidTest --no-daemon
adb -s "$serial" install -r app-recv/build/outputs/apk/degoogle/debug/app-recv-degoogle-debug.apk >/dev/null
test_apk="$(find app-recv/build/outputs/apk/androidTest/degoogle/debug -name '*.apk' -type f | head -1)"
test -f "$test_apk" || { echo "Instrumentation APK not found" >&2; exit 1; }
adb -s "$serial" install -r "$test_apk" >/dev/null
adb -s "$serial" shell cmd role add-role-holder "$role" "$pkg" >/dev/null
result="$(adb -s "$serial" shell am instrument -w \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner")"
printf '%s\n' "$result"
printf '%s\n' "$result" | grep -q '^OK ('
