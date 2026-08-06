#!/usr/bin/env bash
set -euo pipefail

# Device-only provider contract tests. Destructive-but-self-cleaning, NOT idempotent
# standalone — always run through this script, never `am instrument` by hand.
#
# Usage:
#   scripts/device-contract.sh                      # whole suite
#   scripts/device-contract.sh '<class>#<method>'   # ONE test (see BLAST RADIUS below)
#
# BLAST RADIUS. The full suite writes contact / call-log / SMS fixtures to the attached
# device and TAKES THE DEFAULT-SMS ROLE for the duration. That is fine on a scratch phone
# and rude on someone's daily driver. Passing a filter runs only what you name and, when
# the filter needs no SMS, skips the role handoff entirely. Example (#163, needs no SMS):
#
#   scripts/device-contract.sh \
#     'com.ventouxlabs.portage.recv.ProviderDeviceContractTest#calendarCreatesAccountLessLocalCalendarAndAcceptsEvents'

filter="${1:-}"

serial="${ANDROID_SERIAL:-$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')}"
test -n "$serial" || { echo "No authorized Android device attached" >&2; exit 1; }

pkg="com.ventouxlabs.portage.recv"
role="android.app.role.SMS"

# Only take the SMS role when a test actually needs it. A filtered run that never touches
# SMS must not make portage the user's texting app just to check a calendar insert.
needs_sms=1
if test -n "$filter" && ! printf '%s' "$filter" | grep -qi 'sms'; then
  needs_sms=0
fi

prior=""
if test "$needs_sms" = "1"; then
  prior="$(adb -s "$serial" shell cmd role get-role-holders "$role" | tr -d '\r' | head -1)"
fi

# The calendar contract test (#163) runs under the APP's own permissions, not an adopted
# shell identity — adopting shell would prove the provider accepts the insert from shell,
# which is not the production caller. Grant here, and revoke on exit ONLY if we were the
# ones who granted, so a device that already held them is left exactly as found.
granted_calendar=""
for perm in android.permission.READ_CALENDAR android.permission.WRITE_CALENDAR; do
  if ! adb -s "$serial" shell dumpsys package "$pkg" 2>/dev/null | tr -d '\r' |
       grep -q "$perm: granted=true"; then
    granted_calendar="$granted_calendar $perm"
  fi
done

restore_device() {
  if test "$needs_sms" = "1"; then
    if test -n "$prior" && test "$prior" != "$pkg"; then
      adb -s "$serial" shell cmd role add-role-holder "$role" "$prior" >/dev/null || true
    elif test -z "$prior"; then
      adb -s "$serial" shell cmd role remove-role-holder "$role" "$pkg" >/dev/null || true
    fi
  fi
  for perm in $granted_calendar; do
    adb -s "$serial" shell pm revoke "$pkg" "$perm" >/dev/null 2>&1 || true
  done
  adb -s "$serial" uninstall "$pkg.test" >/dev/null 2>&1 || true
}
trap restore_device EXIT

export ANDROID_SERIAL="$serial"
# Do NOT force an isolated GRADLE_USER_HOME. minSdk/compileSdk need a JDK 17 toolchain, and on a
# machine where 17 exists only as Gradle's own auto-provisioned JDK (~/.gradle/jdks — common when
# the system JDK is 21), a scratch gradle home cannot see it AND has no download repositories
# configured, so the build dies at "Cannot find a Java installation ... languageVersion=17" before
# a single test runs. --no-daemon already gives the isolation the scratch dir was reaching for.
# Still overridable for CI, which sets its own.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

./gradlew :app-recv:assembleDegoogleDebug :app-recv:assembleDegoogleDebugAndroidTest --no-daemon
adb -s "$serial" install -r app-recv/build/outputs/apk/degoogle/debug/app-recv-degoogle-debug.apk >/dev/null
test_apk="$(find app-recv/build/outputs/apk/androidTest/degoogle/debug -name '*.apk' -type f | head -1)"
test -f "$test_apk" || { echo "Instrumentation APK not found" >&2; exit 1; }
adb -s "$serial" install -r "$test_apk" >/dev/null

for perm in $granted_calendar; do
  adb -s "$serial" shell pm grant "$pkg" "$perm" >/dev/null
done
if test "$needs_sms" = "1"; then
  adb -s "$serial" shell cmd role add-role-holder "$role" "$pkg" >/dev/null
fi

if test -n "$filter"; then
  result="$(adb -s "$serial" shell am instrument -w \
    -e class "$filter" \
    "$pkg.test/androidx.test.runner.AndroidJUnitRunner")"
else
  result="$(adb -s "$serial" shell am instrument -w \
    "$pkg.test/androidx.test.runner.AndroidJUnitRunner")"
fi
printf '%s\n' "$result"
printf '%s\n' "$result" | grep -q '^OK ('
