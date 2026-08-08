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
#   - app-recv ITSELF is installed and NOT uninstalled on exit (only the .test APK is).
#     Now that detection runs post-install this script works on a phone that never had it,
#     so it can leave an app behind: `adb uninstall com.ventouxlabs.portage.recv` after.
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
#
# TWO checks, because the obvious one alone does not hold. `grep` is LINE-oriented: `^...$` anchor to
# a line, and `-q` succeeds if ANY line matches — so a filter whose first line is a well-formed
# selector passed validation with arbitrary content on line 2, and `adb shell` happily forwarded the
# newline for the device shell to run as a second command. The `case` glob below is whole-string and
# so rejects the newline (and every other out-of-charset byte); the regex then checks the SHAPE of
# what survived. Do not collapse these back into one grep.
case "$filter" in
  "") ;;
  *[!A-Za-z0-9_.#,]*)
    echo "Refusing filter: contains a character outside the class#method charset" >&2
    exit 1 ;;
esac
if test -n "$filter" &&
   ! [[ "$filter" =~ ^[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?(,[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?)*$ ]]; then
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

# Is $2 granted to $pkg FOR $user?
#
#   0 = granted   1 = not granted   2 = COULD NOT TELL
#
# Three states, not two, and that is the whole point. Any adb error, or output that doesn't contain
# the user's block at all, must not collapse into "not granted" — otherwise a transient USB hiccup
# reads as not-granted, we grant, and the EXIT trap then REVOKES a permission the user really had.
# Same fail-open shape CLAUDE.md documents for the boundary gate.
#
# Returns 2 rather than exiting, because this is called from the EXIT trap to verify the revokes:
# exiting there would skip the remaining restore steps and the failure banner. Callers must handle
# 2 explicitly — the pre-test detection treats it as fatal, the trap as "restore unverified".
perm_granted_for_user() {
  local perm="$1" out block
  out="$(adb -s "$serial" shell dumpsys package "$pkg" 2>/dev/null | tr -d '\r')" || {
    echo "adb dumpsys failed while reading permissions" >&2; return 2; }
  test -n "$out" || { echo "empty dumpsys output for $pkg" >&2; return 2; }
  # Prefix match, NOT equality: the header line carries trailing fields
  # ("    User 0: ceDataInode=... installed=true ..."), so an exact compare silently selects the
  # wrong block, finds no grant, and we would grant-then-REVOKE a permission the user really had.
  block="$(printf '%s\n' "$out" | awk -v u="User $user:" '
    $0 ~ ("^[[:space:]]+" u "([[:space:]]|$)") {inblock=1; next}
    /^[[:space:]]+User [0-9]+:([[:space:]]|$)/ {inblock=0}
    inblock {print}')"
  test -n "$block" || { echo "no 'User $user:' block for $pkg in dumpsys" >&2; return 2; }
  # Without this the parse could degrade to a block that simply has no permissions section, which
  # would read as "not granted" — the same fail-open by a different route.
  grep -q 'runtime permissions:' <<<"$block" || {
    echo "no 'runtime permissions:' section for user $user — refusing to guess" >&2; return 2; }
  # The LAST fail-open branch in a function whose whole point is failing closed: grep returns 1 for
  # not-found but >=2 for an ERROR (and 128+n if it is killed), and the caller writes
  # `if ! perm_granted_for_user`, which collapses every non-zero to "not granted". The consequence is
  # the one this function exists to prevent: we grant a permission the user already had, and the EXIT
  # trap then REVOKES it. So branch on the status explicitly and treat anything that is not a clean
  # 0/1 as fatal. (-F because $perm is a literal: unescaped `.` would otherwise match any character.)
  #
  # A here-string, not `printf | grep`: under `set -o pipefail` a `grep -q` that exits early on a
  # match can SIGPIPE the writer, and the pipeline would then report the writer's 141 — an error
  # status manufactured out of a successful lookup.
  local status=0
  grep -qF "$perm: granted=true" <<<"$block" || status=$?
  case "$status" in
    0) return 0 ;;
    1) return 1 ;;
    *) echo "grep failed (status $status) reading '$perm' for user $user" >&2; return 2 ;;
  esac
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

# The prior default-SMS holder, "" meaning nobody held it. That conflation is load-bearing and
# dangerous: restore_device's empty branch REMOVES the role, so a FAILED query misread as "nobody
# held it" strips the user's real texting app instead of handing it back.
#
# The validation therefore inspects the WHOLE output, and the earlier `test -n "$prior" && ...` shape
# was unsound for exactly the value that matters — it could not fire on empty, and empty is what a
# failed query most often looks like. `cmd role get-role-holders` can fail while writing to stderr
# and still exit 0, leaving stdout blank, so:
#   - stderr is folded in with 2>&1, so error text lands IN the value and fails the charset test
#     instead of vanishing and leaving a blank that reads as "unheld";
#   - the accept set is "exactly nothing" or "exactly one package token" — a blank-but-present line,
#     a stack trace, or a usage message all fail;
#   - `sed -n 1p` rather than `head -1`: it reads to EOF, so it cannot SIGPIPE the writer and
#     manufacture a pipeline failure under `set -o pipefail`.
# Empty then means "the device told us, in a well-formed way, that nobody holds it".
prior=""
if test "$needs_sms" = "1"; then
  role_read="$(adb -s "$serial" shell cmd role get-role-holders --user "$user" "$role" 2>&1 |
    tr -d '\r' | sed -n 1p)"
  # Accept: empty, or one dotted package token. Android requires at least two segments in a package
  # name, so "must contain a dot, but not lead or trail with one" is a real constraint, not a guess.
  role_ok=0
  case "$role_read" in
    "") role_ok=1 ;;
    *[!A-Za-z0-9._]*) role_ok=0 ;;
    .*|*.) role_ok=0 ;;
    *.*) role_ok=1; prior="$role_read" ;;
  esac
  if test "$role_ok" != "1"; then
    echo "Could not read the current $role holder (got: '$role_read')" >&2
    echo "Refusing to take the SMS role, because this run could not then give it back." >&2
    exit 1
  fi

  # portage ALREADY holding the role is not a state to restore to — it is the fingerprint of a
  # previous run that died before its trap (the header documents the SIGKILL/yanked-cable case).
  # Proceeding would take the role, hand it back to portage, verify "restored", and exit 0, blessing
  # the leak permanently. This script cannot know what the real texting app was, so it stops and
  # says so. Genuinely-portage-by-choice devices set the override.
  if test "$prior" = "$pkg" && test "${PORTAGE_CONTRACT_ALLOW_PRIOR_SELF:-0}" != "1"; then
    echo "$pkg ALREADY holds $role." >&2
    echo "That usually means an earlier run was killed before it could hand the role back." >&2
    echo "Restore your real texting app first (Settings > Apps > Default apps > SMS app)," >&2
    echo "or set PORTAGE_CONTRACT_ALLOW_PRIOR_SELF=1 if portage is deliberately the default." >&2
    exit 1
  fi
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

# Restores what the run took, and — the part that matters — REPORTS when it could not.
#
# The failure this guards is the worst thing this script can do to a daily driver: leave portage as
# the user's texting app. The previous version could not detect it. `|| true` on the handback
# swallowed the error, and nothing re-read the role afterwards, so a refused or silently-ignored
# handback still ended with the script printing "N test(s) executed" and exiting 0.
#
# Every step is individually guarded rather than allowed to abort the handler: this runs as a trap
# under `set -e`, so a bare failure partway through would skip the revokes and the uninstall below.
# Failures are accumulated and reported at the end, and a failed ROLE restore exits non-zero.
restore_done=0
# Did we take the role, and did we ever PROVE the role read works on this device? Both start false
# and are set at the point of the act, so the trap can tell "never touched it" from "took it and
# cannot verify" — which need opposite handling and used to be indistinguishable.
role_taken=0
role_read_trusted=0
restore_device() {
  # Runs from EXIT and, via the signal handlers below, from INT/TERM. Guard against a second pass:
  # it would be harmless but would reprint the banner, and a banner still on screen after a restore
  # that actually succeeded is its own kind of misinformation.
  test "$restore_done" = "0" || return 0
  restore_done=1

  local restore_failed=0 perm_status=0
  local now=""
  if test "$needs_sms" = "1" && test "$role_taken" = "1" && test "$role_read_trusted" != "1"; then
    # We hold the role and cannot trust the read that told us who held it before. Both recoveries
    # are guesses: handing back to a `prior` that may be fiction, or removing the role and possibly
    # deleting the real texting app's claim. Refusing to guess and saying so loudly beats performing
    # a destructive action on data we already know is unreliable.
    echo "" >&2
    echo "!!! SMS ROLE STATE UNVERIFIABLE — NOT GUESSING !!!" >&2
    echo "  $pkg holds $role and this device's role read could not be trusted," >&2
    echo "  so the prior holder is unknown. Set your texting app by hand:" >&2
    echo "    Settings > Apps > Default apps > SMS app" >&2
    restore_failed=1
  elif test "$needs_sms" = "1" && test "$role_taken" = "1"; then
    if test -n "$prior"; then
      adb -s "$serial" shell cmd role add-role-holder --user "$user" "$role" "$prior" >/dev/null ||
        true
    else
      adb -s "$serial" shell cmd role remove-role-holder --user "$user" "$role" "$pkg" >/dev/null ||
        true
    fi
    # The POST-STATE is authoritative; the exit status above is not even consulted. That command can
    # report success without the role moving, AND report failure for a no-op that left the device
    # correct (removing a role portage never took) — judging by its status produced both a missed
    # real failure and a false alarm. What the device says afterwards produces neither. A read we
    # cannot perform is itself a failure: "I cannot tell you who holds your SMS role" is not an
    # acceptable way to finish.
    if now="$(adb -s "$serial" shell cmd role get-role-holders --user "$user" "$role" 2>&1 |
      tr -d '\r' | sed -n 1p)"; then
      test "$now" = "$prior" || restore_failed=1
    else
      now="<query failed>"
      restore_failed=1
    fi
  fi

  # Verified by re-reading, the same way and for the same reason as the role. A failed `pm revoke`
  # leaves portage holding a calendar permission the user never granted; warning about that while
  # exiting 0 is the same fail-open shape, just with a smaller blast radius. perm_granted_for_user
  # returns 2 for "could not tell", which must not be read as "successfully revoked".
  for perm in $granted_calendar; do
    adb -s "$serial" shell pm revoke --user "$user" "$pkg" "$perm" >/dev/null 2>&1 || true
    perm_status=0
    perm_granted_for_user "$perm" || perm_status=$?
    case "$perm_status" in
      1) ;;
      0) echo "!!! PERMISSION RESTORE FAILED: $pkg still holds $perm !!!" >&2
         echo "    adb -s $serial shell pm revoke --user $user $pkg $perm" >&2
         restore_failed=1 ;;
      *) echo "!!! PERMISSION RESTORE UNVERIFIED: could not read $perm back !!!" >&2
         echo "    adb -s $serial shell pm revoke --user $user $pkg $perm" >&2
         restore_failed=1 ;;
    esac
  done
  adb -s "$serial" uninstall "$pkg.test" >/dev/null 2>&1 || true

  if test "$restore_failed" = "1"; then
    if test "$needs_sms" = "1" && test "$role_read_trusted" = "1" && test "$now" != "$prior"; then
      echo "" >&2
      echo "!!! DEFAULT-SMS ROLE RESTORE FAILED !!!" >&2
      echo "  expected holder: ${prior:-<none>}" >&2
      echo "  actual holder:   ${now:-<none>}" >&2
      # Printed when the holder is portage OR unknown. Withholding it on "unknown" was backwards:
      # that is exactly the case where the worst outcome cannot be ruled out.
      if test "$now" = "$pkg" || test "$now" = "<query failed>"; then
        echo "  portage MAY STILL BE THIS DEVICE'S TEXTING APP. Fix it now:" >&2
        echo "    adb -s $serial shell cmd role add-role-holder --user $user $role <your-sms-app>" >&2
        echo "  or in Settings > Apps > Default apps > SMS app." >&2
      fi
    fi
    echo "device-contract: THE DEVICE WAS NOT FULLY RESTORED — see above." >&2
    exit 1
  fi
}
# INT/TERM as well as EXIT: a Ctrl-C mid-run would otherwise leave the calendar permission granted
# and, on a needs_sms run, portage still holding the default-SMS role.
#
# The signal handlers must TERMINATE, not just clean up. `trap restore_device INT TERM` ran the
# handler and then let bash resume at the next statement — so a `kill -TERM` from `timeout`, a CI
# cancel, or an IDE stop button would restore the device and then carry on to TAKE THE SMS ROLE
# after being asked to stop. (Ctrl-C at a terminal usually hid this: it signals the whole process
# group, so the child died and `set -e` aborted anyway.) Re-raising with the default disposition
# also yields the conventional 130/143 exit rather than a synthesised one.
trap restore_device EXIT
trap 'restore_device; trap - INT; kill -INT $$' INT
trap 'restore_device; trap - TERM; kill -TERM $$' TERM

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
#
# Status 2 ("could not tell") is FATAL here and must not be folded in with 1 ("not granted") by a
# bare `if !` — that is the fail-open the three-state return exists to prevent: an unreadable
# dumpsys would read as not-granted, we would grant, and the trap would revoke a permission the
# user really held.
for perm in android.permission.READ_CALENDAR android.permission.WRITE_CALENDAR; do
  detect_status=0
  perm_granted_for_user "$perm" || detect_status=$?
  case "$detect_status" in
    0) ;;
    1) granted_calendar="$granted_calendar $perm" ;;
    *) echo "Could not determine whether $pkg holds $perm for user $user — refusing to guess" >&2
       exit 1 ;;
  esac
done

for perm in $granted_calendar; do
  adb -s "$serial" shell pm grant --user "$user" "$pkg" "$perm" >/dev/null
done
if test "$needs_sms" = "1"; then
  adb -s "$serial" shell cmd role add-role-holder --user "$user" "$role" "$pkg" >/dev/null
  role_taken=1
  # PROVE THE READ WORKS, here, while the answer is known.
  #
  # Everything protecting the user's texting app rests on `get-role-holders` telling the truth, and
  # the restore check compares two reads from that same command — so a broken read is invisible to
  # it: both sides come back empty, they match, and the run reports a successful restore having
  # verified nothing. This is the one moment the expected answer is known independently (we just
  # took the role), so it is the only place the read itself can be tested. If it cannot observe a
  # write we just made, abort before running a single test rather than proceed with a blind restore.
  took="$(adb -s "$serial" shell cmd role get-role-holders --user "$user" "$role" 2>&1 |
    tr -d '\r' | sed -n 1p)"
  test "$took" = "$pkg" || {
    echo "Cannot verify the SMS role on this device: after taking it, the holder reads" >&2
    echo "  '$took' rather than $pkg." >&2
    echo "Aborting rather than running with a restore check that cannot detect its own failure." >&2
    exit 1; }
  role_read_trusted=1
fi

# Built ONCE, with the filter as the only difference. This used to be two near-identical
# `am instrument` invocations that both had to carry `-e portage_grants_prepared true` — and that
# flag's ABSENCE is what turns the suite's preconditions back into assumption-skips, which JUnit
# counts toward `OK (N tests)` and this script's gate accepts. Dropping it from one of two copies
# while editing the other would have silently converted a verified run into a skipped one that still
# reported success. One copy cannot drift from itself.
#
# Unquoted expansion is not a concern: $filter was validated against a strict class#method charset
# above precisely because `adb shell` concatenates argv and hands it to the device's /system/bin/sh.
instrument_args=(am instrument -w --user "$user" -e portage_grants_prepared true)
if test -n "$filter"; then
  instrument_args+=(-e class "$filter")
fi
instrument_args+=("$pkg.test/androidx.test.runner.AndroidJUnitRunner")
result="$(adb -s "$serial" shell "${instrument_args[@]}")"
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
