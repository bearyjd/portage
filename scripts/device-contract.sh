#!/usr/bin/env bash
set -euo pipefail

# Device-only provider contract tests. Destructive-but-self-cleaning, NOT idempotent
# standalone — always run through this script, never `am instrument` by hand.
#
# "Self-cleaning" means it removes what it CREATED. It does NOT restore the device to its exact
# prior state, and the difference matters on a phone someone actually uses:
#   - `pm revoke` leaves a permission explicitly DENIED, not "never requested". If the owner had
#     never been asked for calendar access, they end up denied — which silently degrades portage's
#     own next calendar import until they re-grant it in-app.
#   - The default-SMS role is handed back to the prior holder, but the handover is not atomic.
#   - A run killed hard (SIGKILL, yanked cable) skips the trap entirely.
# Prefer a scratch device. On a daily driver, use a `#method` filter and read the list below.
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

# The filter is concatenated by `adb shell` and handed to /system/bin/sh ON THE DEVICE with no
# quoting, so `;`, backticks and $(...) in it would execute as SHELL UID. Self-inflicted via own
# argv rather than a privilege-boundary breach, but shell uid is the exact thing ADR-003 exists to
# control, so refuse anything that is not a plain JUnit class#method selector.
if test -n "$filter" && ! printf '%s' "$filter" | grep -qE '^[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?(,[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?)*$'; then
  echo "Refusing filter '$filter': expected <class>[#<method>][,<class>[#<method>]...]" >&2
  exit 1
fi

serial="${ANDROID_SERIAL:-$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')}"
test -n "$serial" || { echo "No authorized Android device attached" >&2; exit 1; }

pkg="com.ventouxlabs.portage.recv"
role="android.app.role.SMS"

# Permission state is PER USER, and `pm grant`/`pm revoke` default to the calling user. GrapheneOS
# actively promotes secondary profiles, so a device commonly prints a User 0 AND a User 10 block —
# an unscoped grep matches either, which is how a grant held only by user 10 could read as "already
# granted" for user 0, skip the grant, and leave the test silently assumeTrue-skipped.
user="$(adb -s "$serial" shell am get-current-user 2>/dev/null | tr -d '\r' | tr -cd '0-9')"
test -n "$user" || { echo "Could not determine current user via am get-current-user" >&2; exit 1; }

# Is $2 granted to $pkg FOR $user? Fails CLOSED: any adb error, or output that doesn't contain the
# user's block at all, is an error rather than "not granted" — otherwise a transient USB hiccup
# reads as "not granted", we grant, and the EXIT trap then REVOKES a permission the user really
# had. Same fail-open shape CLAUDE.md documents for the boundary gate.
perm_granted_for_user() {
  local perm="$1" out block
  out="$(adb -s "$serial" shell dumpsys package "$pkg" 2>/dev/null | tr -d '\r')" || {
    echo "adb dumpsys failed while reading permissions" >&2; exit 1; }
  test -n "$out" || { echo "empty dumpsys output for $pkg" >&2; exit 1; }
  # Prefix match, NOT equality: the header line carries trailing fields
  # ("    User 0: ceDataInode=... installed=true ..."), so an exact compare silently selects the
  # wrong block, finds no grant, and we would grant-then-REVOKE a permission the user really had.
  block="$(printf '%s\n' "$out" | awk -v u="User $user:" '
    $0 ~ ("^[[:space:]]+" u "([[:space:]]|$)") {inblock=1; next}
    /^[[:space:]]+User [0-9]+:([[:space:]]|$)/ {inblock=0}
    inblock {print}')"
  test -n "$block" || { echo "no 'User $user:' block for $pkg in dumpsys" >&2; exit 1; }
  # Without this the parse could degrade to a block that simply has no permissions section, which
  # would read as "not granted" — the same fail-open by a different route.
  printf '%s\n' "$block" | grep -q 'runtime permissions:' || {
    echo "no 'runtime permissions:' section for user $user — refusing to guess" >&2; exit 1; }
  printf '%s\n' "$block" | grep -q "$perm: granted=true"
}

# Only take the SMS role when a test actually needs it — a filtered run that never touches SMS must
# not make portage the user's texting app just to check a calendar insert. FAIL CLOSED: only a
# filter naming a specific #method may skip it. A class-only filter (the obvious thing to type to
# run the whole class) still runs the SMS test, which would then assumeTrue-skip while the run
# reported OK — silent coverage loss.
needs_sms=1
if test -n "$filter" &&
   printf '%s' "$filter" | grep -q '#' &&
   ! printf '%s' "$filter" | grep -qi 'sms'; then
  needs_sms=0
fi

prior=""
if test "$needs_sms" = "1"; then
  prior="$(adb -s "$serial" shell cmd role get-role-holders --user "$user" "$role" | tr -d '\r' | head -1)"
fi

# The calendar contract test (#163) runs under the APP's own permissions, not an adopted shell
# identity — adopting shell would prove the provider accepts the insert from shell, which is not
# the production caller. Detection and granting both happen AFTER the install below; the trap
# revokes ONLY what we granted.
# CAVEAT: `pm revoke` restores "denied", NOT "never requested" — a device that had never been asked
# ends up with the permission explicitly user-set-denied. Close enough for a scratch state, but it
# is not a byte-exact restore.
# Populated AFTER the install below — see there for why. Declared here so the trap, which is
# installed before any device mutation, always has a defined (possibly empty) list to revoke.
granted_calendar=""

restore_device() {
  if test "$needs_sms" = "1"; then
    if test -n "$prior" && test "$prior" != "$pkg"; then
      adb -s "$serial" shell cmd role add-role-holder --user "$user" "$role" "$prior" >/dev/null || true
    elif test -z "$prior"; then
      adb -s "$serial" shell cmd role remove-role-holder --user "$user" "$role" "$pkg" >/dev/null || true
    fi
  fi
  for perm in $granted_calendar; do
    adb -s "$serial" shell pm revoke --user "$user" "$pkg" "$perm" >/dev/null 2>&1 || true
  done
  adb -s "$serial" uninstall "$pkg.test" >/dev/null 2>&1 || true
}
# INT/TERM as well as EXIT: a Ctrl-C mid-run would otherwise leave the calendar permission granted
# and, on a needs_sms run, portage still holding the default-SMS role.
trap restore_device EXIT INT TERM

export ANDROID_SERIAL="$serial"
# Do NOT force an isolated GRADLE_USER_HOME. minSdk/compileSdk need a JDK 17 toolchain, and on a
# machine where 17 exists only as Gradle's own auto-provisioned JDK (~/.gradle/jdks — common when
# the system JDK is 21), a scratch gradle home cannot see it AND has no download repositories
# configured, so the build dies at "Cannot find a Java installation ... languageVersion=17" before
# a single test runs. --no-daemon already gives the isolation the scratch dir was reaching for.
# Still overridable by the caller if they need an isolated cache. (No CI workflow runs
# this script — it needs a physical device — so there is no CI-sets-its-own case to serve.)
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

./gradlew :app-recv:assembleDegoogleDebug :app-recv:assembleDegoogleDebugAndroidTest --no-daemon
adb -s "$serial" install -r app-recv/build/outputs/apk/degoogle/debug/app-recv-degoogle-debug.apk >/dev/null
test_apk="$(find app-recv/build/outputs/apk/androidTest/degoogle/debug -name '*.apk' -type f | head -1)"
test -f "$test_apk" || { echo "Instrumentation APK not found" >&2; exit 1; }
adb -s "$serial" install -r "$test_apk" >/dev/null

# Detect AFTER installing, not before. `dumpsys package <pkg>` on a phone that does not have
# app-recv yet prints no user block at all, and perm_granted_for_user correctly refuses to guess —
# which meant the harness exited before installing anything, i.e. it could not run on a fresh
# device. Detecting post-install is also still correct for "what did the user have before we
# touched it": `install -r` preserves existing grants, and a genuinely fresh install has none, so
# we grant and the trap revokes exactly what we added.
for perm in android.permission.READ_CALENDAR android.permission.WRITE_CALENDAR; do
  if ! perm_granted_for_user "$perm"; then
    granted_calendar="$granted_calendar $perm"
  fi
done

for perm in $granted_calendar; do
  adb -s "$serial" shell pm grant --user "$user" "$pkg" "$perm" >/dev/null
done
if test "$needs_sms" = "1"; then
  adb -s "$serial" shell cmd role add-role-holder --user "$user" "$role" "$pkg" >/dev/null
fi

if test -n "$filter"; then
  result="$(adb -s "$serial" shell am instrument -w --user "$user" \
    -e class "$filter" \
    -e portage_grants_prepared true \
    "$pkg.test/androidx.test.runner.AndroidJUnitRunner")"
else
  result="$(adb -s "$serial" shell am instrument -w --user "$user" \
    -e portage_grants_prepared true \
    "$pkg.test/androidx.test.runner.AndroidJUnitRunner")"
fi
printf '%s\n' "$result"

# `grep -q '^OK ('` alone is NOT a sufficient gate. Two ways a run reports OK having verified
# nothing, both seen in this repo's history of fail-open gates:
#
#  1. `OK (0 tests)` — a filter that is well-formed but names no existing test (a typo'd #method)
#     selects an empty set, and AndroidJUnitRunner's request builder deliberately turns that into a
#     blank runner rather than an error. Nothing ran; the gate accepted it.
#  2. An assumption-skip counts toward the OK line. The calendar test hard-fails instead when
#     `portage_grants_prepared` is set, but that only covers that one test.
#
# So require the OK line AND a non-zero test count. Anything else — FAILURES, a crash, a missing
# OK line, or zero tests — is a failure.
printf '%s\n' "$result" | grep -q '^OK (' || {
  echo "device-contract: instrumentation did not report OK" >&2; exit 1; }
ran="$(printf '%s\n' "$result" | sed -n 's/^OK (\([0-9]\{1,\}\) test.*/\1/p' | head -1)"
test -n "$ran" || { echo "device-contract: could not parse the test count from the OK line" >&2; exit 1; }
test "$ran" -gt 0 || {
  echo "device-contract: OK (0 tests) — nothing ran. Check the filter names a real class#method." >&2
  exit 1; }
echo "device-contract: $ran test(s) executed"
