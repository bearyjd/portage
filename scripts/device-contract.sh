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
#
# REDACT BEFORE PASTING. The failure output names your default-SMS app and embeds the device serial
# in a copy-pasteable command. Neither is a secret, but both identify you, and this output is the
# kind that ends up in GitHub issues and agent transcripts.
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

# Echo the current holder of $role (possibly empty) and return 0; return 1 if the device's answer
# was not WELL-FORMED. The distinction is the whole point: "" from this function means the device
# said, in a well-formed way, that nobody holds the role — never "the read failed".
#
# ONE reader for every caller. There used to be three ad-hoc copies and only the first was
# validated, so the restore comparison happily accepted a stack trace or a blank line as a holder
# name. `2>&1` folds stderr in so error text fails the charset test rather than vanishing and
# leaving a blank that reads as "unheld"; `sed -n 1p` reads to EOF so it cannot SIGPIPE the writer
# and manufacture a pipeline failure under `set -o pipefail`.
read_role_holder() {
  local out
  out="$(adb -s "$serial" shell cmd role get-role-holders --user "$user" "$role" 2>&1 |
    tr -d '\r' | sed -n 1p)" || return 1
  # Accept: nothing at all, or exactly one dotted package token. Android package names require at
  # least two segments, so "contains a dot, does not lead or trail with one" is a real constraint.
  case "$out" in
    "") printf '' ; return 0 ;;
    *[!A-Za-z0-9._]*) return 1 ;;
    .*|*.) return 1 ;;
    *.*) printf '%s' "$out" ; return 0 ;;
    *) return 1 ;;
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
  prior="$(read_role_holder)" || {
    echo "Could not read the current $role holder — the device's answer was not a package name." >&2
    echo "Refusing to take the SMS role, because this run could not then give it back." >&2
    exit 1; }

  # An EMPTY answer earns a second opinion; a non-empty one does not need it. Empty is the value
  # that selects the branch REMOVING the role, so it is the answer that must be right — and a reader
  # that glitches exactly once, on this very first call, returns it. The take-time read-back cannot
  # rescue that case: by then the reader is working again and happily confirms the take, so a
  # `prior` captured during the glitch gets trusted and the restore strips a role the user's real
  # texting app was holding. Reading twice costs nothing: a genuinely unheld role answers "" both
  # times, a transient glitch does not. (A reader that is broken for the WHOLE run agrees with
  # itself here and is caught instead by the take-time read-back below — the two cover each other.)
  if test -z "$prior"; then
    confirm="$(read_role_holder)" || {
      echo "The $role holder read as empty, and the confirming read was not a package name." >&2
      echo "Refusing to take the SMS role on an unreliable reader." >&2
      exit 1; }
    if test -n "$confirm"; then
      echo "The $role holder read as empty, then as '$confirm' — the answer changed under us." >&2
      echo "Refusing to take the SMS role: acting on the first answer would have removed" >&2
      echo "'$confirm' from the role and never put it back." >&2
      exit 1
    fi
  fi

  # portage ALREADY holding the role is not a state to restore TO — it is the fingerprint of an
  # earlier run that died before its trap (the header documents the SIGKILL/yanked-cable case).
  # Proceeding would take the role, hand it back to portage, verify "restored", and exit 0, blessing
  # the leak permanently.
  #
  # The escape hatch takes the REAL prior holder rather than a boolean, deliberately. A boolean
  # ("yes, portage holding it is fine") would be advertised, in the error message, to the one person
  # guaranteed to be looking at a leak — and taking it would make the leak permanent while the run
  # printed success. Naming a package instead makes the only available escape the one that actually
  # gives the device its texting app back. portage-recv is an importer that holds the SMS role
  # TRANSIENTLY (see CLAUDE.md); there is no device where it is legitimately the permanent default,
  # so there is nothing to offer a boolean for.
  if test "$prior" = "$pkg"; then
    if test -n "${PORTAGE_CONTRACT_PRIOR_SMS:-}"; then
      # $pkg FIRST. Naming portage here re-creates precisely the leak this refusal exists to stop:
      # the run hands the role back to portage, the post-state read agrees, and it exits 0 with a
      # test app as the device's texting app. It is also the single most obvious value to paste,
      # because the refusal message above prints it — the same trap that killed the boolean form of
      # this override, which I removed for exactly that reason and then rebuilt by omission.
      case "$PORTAGE_CONTRACT_PRIOR_SMS" in
        "$pkg")
          echo "PORTAGE_CONTRACT_PRIOR_SMS is $pkg — that is the leak, not the fix." >&2
          echo "Name the texting app that should hold $role instead." >&2
          exit 1 ;;
        *[!A-Za-z0-9._]*|.*|*.) echo "PORTAGE_CONTRACT_PRIOR_SMS is not a package name" >&2; exit 1 ;;
        *.*) ;;
        *) echo "PORTAGE_CONTRACT_PRIOR_SMS is not a package name" >&2; exit 1 ;;
      esac
      echo "NOTE: $pkg already held $role; restoring to $PORTAGE_CONTRACT_PRIOR_SMS as instructed." >&2
      prior="$PORTAGE_CONTRACT_PRIOR_SMS"
    else
      echo "$pkg ALREADY holds $role." >&2
      echo "That usually means an earlier run was killed before it could hand the role back," >&2
      echo "and this script cannot know which app should have it." >&2
      echo "Fix it in Settings > Apps > Default apps > SMS app and re-run, or tell this run" >&2
      echo "which app to hand it back to:  PORTAGE_CONTRACT_PRIOR_SMS=<package>" >&2
      exit 1
    fi
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
# Wait until the $role holder reads as "$1" ("" meaning nobody), or a ~3s budget runs out. Echoes
# the last value read; returns 0 on agreement, 1 otherwise.
#
# EVERY role write in this script goes through `cmd role add/remove-role-holder`, which completes via
# a RoleManager callback — whether the result is visible to the very next `get-role-holders` is a
# platform timing property, not something this script gets to assume. Verifying a write with a single
# immediate read therefore returns a false verdict on a device that is merely slow, and the two sites
# fail in opposite, equally bad directions: on the TAKE it read as "the take had no effect" (skip the
# restore, leak the role), on the HANDBACK as a spurious RESTORE FAILED for a restore that worked.
# This existed at the take only for a while. Same command, same timing property — one rule, every
# call site. Costs one read where the write is already visible.
await_role_holder() {
  local want="$1" got="" _attempt
  for _attempt in 1 2 3 4 5 6; do
    got="$(read_role_holder)" || got="<unreadable>"
    if test "$got" = "$want"; then printf '%s' "$got"; return 0; fi
    test "$_attempt" -lt 6 && sleep 0.5
  done
  printf '%s' "$got"
  return 1
}

restore_done=0
# Did we take the role, and did we ever PROVE the role read works on this device? Both start false
# and are set at the point of the act, so the trap can tell "never touched it" from "took it and
# cannot verify" — which need opposite handling and used to be indistinguishable.
role_taken=0
# READ EXACTLY ONCE, at the top of restore_sms_role, to tell "took the role but never proved the
# reader" from "took it and can verify". Nothing downstream consumes it, so writing to it later in
# the restore changes NOTHING — an earlier version did exactly that and the assignment was dead.
role_read_trusted=0
# One short line per thing that could not be restored or verified, printed together at the end. A
# lone "see above" made the operator scroll back past a gradle build and a full instrumentation dump
# to work out WHICH restore failed — on the one output that most needs to be actionable.
restore_problems=()

# ----------------------------------------------------------------------------------------------
# The three helpers below all run from inside the EXIT/INT/TERM trap, under `set -e`. That
# constrains every line in them: a bare command failure aborts the whole handler and silently skips
# everything after it — which is exactly how an earlier version lost its revokes AND its uninstall
# whenever the role handback failed. GUARD EVERY COMMAND (`|| true`, `if x="$(...)"`, `|| rc=$?`),
# and let the caller decide what a non-zero return means. Do not "tidy" those guards away.
# ----------------------------------------------------------------------------------------------

# Hand the default-SMS role back. Returns 1 if anything could not be done or verified.
restore_sms_role() {
  # role_taken is only ever set inside the needs_sms branch, so the first guard can never be the
  # one that returns; role_taken carries the meaning. Kept because it states the precondition.
  test "$needs_sms" = "1" || return 0
  test "$role_taken" = "1" || return 0

  local now=""
  if test "$role_read_trusted" != "1"; then
    # We may hold the role and cannot trust the reads. An earlier version abstained here on the
    # grounds that removing might delete the real texting app's claim. That reasoning was wrong on
    # two counts, and abstaining left a test app holding the SMS role indefinitely:
    #   1. `android.app.role.SMS` is EXCLUSIVE. If our take landed, the prior holder's claim was
    #      already evicted at that moment — there is nothing left to protect by not removing.
    #   2. `remove-role-holder ... "$pkg"` NAMES portage. It can only ever drop OUR OWN claim; it is
    #      incapable of touching a third party's. If the take never landed, it is a no-op.
    # So both outcomes of the ambiguity are improved by trying, and neither is harmed. Hand back to
    # `prior` first if we have one (that alone evicts portage), then drop our claim regardless, then
    # report honestly that none of it could be verified.
    echo "" >&2
    echo "!!! SMS ROLE STATE UNVERIFIABLE !!!" >&2
    if test -n "$prior"; then
      echo "  Attempting to hand $role back to '$prior' (unverified)." >&2
      adb -s "$serial" shell cmd role add-role-holder --user "$user" "$role" "$prior" >/dev/null 2>&1 ||
        true
    fi
    adb -s "$serial" shell cmd role remove-role-holder --user "$user" "$role" "$pkg" >/dev/null 2>&1 ||
      true
    echo "  Dropped $pkg's claim on $role; this device's role read cannot confirm the result." >&2
    echo "  Set your texting app by hand: Settings > Apps > Default apps > SMS app" >&2
    restore_problems+=("SMS role: state unverifiable — dropped $pkg's claim, result unconfirmed")
    return 1
  fi

  if test -n "$prior"; then
    # Handing back to a NAMED app: the verification below waits for a non-empty answer, which a
    # broken reader cannot counterfeit. Nothing extra needed.
    adb -s "$serial" shell cmd role add-role-holder --user "$user" "$role" "$prior" >/dev/null ||
      true
  else
    # The blank-read trap, one level deeper than the pre-read. An empty `prior` makes this a
    # REMOVAL, whose verification expects a blank answer — indistinguishable from what a broken
    # reader returns. The take-time read-back proved the reader worked THEN; a reader that dies
    # mid-run (adbd restart, USB flap, reboot) lands right here. So re-prove it against an answer
    # already known: portage holds the role at this instant. If the device will not say so, its
    # blank answer after a removal would mean nothing — and removing on that basis risks stripping
    # a holder we never actually saw.
    local live
    # await_, not read_: a reader that is merely LAGGING must not be branded "gone bad" and
    # trigger the alarm below on a device that is fine.
    live="$(await_role_holder "$pkg")" || true
    if test "$live" = "$pkg"; then
      adb -s "$serial" shell cmd role remove-role-holder --user "$user" "$role" "$pkg" >/dev/null ||
        true
    else
      # Same reasoning as the unverifiable branch above: the removal NAMES portage, so it can only
      # drop our own claim, and the take already evicted whoever held it before. Abstaining here
      # protected nothing and left a test app holding the role. Do it, and report that it could
      # not be confirmed.
      echo "" >&2
      echo "!!! ROLE READER WENT BAD MID-RUN !!!" >&2
      echo "  $pkg should hold $role at this point, but the device says '$live'." >&2
      adb -s "$serial" shell cmd role remove-role-holder --user "$user" "$role" "$pkg" >/dev/null 2>&1 ||
        true
      echo "  Dropped $pkg's claim anyway (it can only ever remove OUR claim); unconfirmed." >&2
      echo "  Check by hand: Settings > Apps > Default apps > SMS app" >&2
      restore_problems+=("SMS role: reader failed mid-run — dropped $pkg's claim, unconfirmed")
      return 1
    fi
  fi

  # The POST-STATE is authoritative; the write's exit status is not even consulted. That command can
  # report success without the role moving, AND report failure for a no-op that left the device
  # correct (removing a role portage never took) — judging by its status produced both a missed real
  # failure and a false alarm. What the device says afterwards produces neither. Retried, because the
  # handback is the same async-capable command as the take: a single read here reported RESTORE
  # FAILED on devices whose restore had in fact worked.
  if now="$(await_role_holder "$prior")"; then
    return 0
  fi
  echo "" >&2
  echo "!!! DEFAULT-SMS ROLE RESTORE FAILED !!!" >&2
  echo "  expected holder: ${prior:-<none>}" >&2
  echo "  actual holder:   ${now:-<none>}" >&2
  # Printed when the holder is portage OR unknown. Withholding it on "unknown" was backwards: that
  # is exactly the case where the worst outcome cannot be ruled out.
  if test "$now" = "$pkg" || test "$now" = "<unreadable>"; then
    echo "  portage MAY STILL BE THIS DEVICE'S TEXTING APP. Fix it now:" >&2
    echo "    adb -s $serial shell cmd role add-role-holder --user $user $role <your-sms-app>" >&2
    echo "  or in Settings > Apps > Default apps > SMS app." >&2
  fi
  restore_problems+=("SMS role: expected holder '${prior:-<none>}', device says '${now:-<none>}'")
  return 1
}

# Revoke the calendar permissions this run granted. Returns 1 if any could not be revoked/verified.
#
# Verified by re-reading, the same way and for the same reason as the role. A failed `pm revoke`
# leaves portage holding a calendar permission the user never granted; warning about that while
# exiting 0 is the same fail-open shape, just with a smaller blast radius. perm_granted_for_user
# returns 2 for "could not tell", which must not be read as "successfully revoked".
restore_permissions() {
  local perm perm_status rc=0
  for perm in $granted_calendar; do
    adb -s "$serial" shell pm revoke --user "$user" "$pkg" "$perm" >/dev/null 2>&1 || true
    perm_status=0
    perm_granted_for_user "$perm" || perm_status=$?
    case "$perm_status" in
      1) ;;
      0) echo "!!! PERMISSION RESTORE FAILED: $pkg still holds $perm !!!" >&2
         echo "    adb -s $serial shell pm revoke --user $user $pkg $perm" >&2
         restore_problems+=("$perm: still granted to $pkg")
         rc=1 ;;
      *) echo "!!! PERMISSION RESTORE UNVERIFIED: could not read $perm back !!!" >&2
         echo "    adb -s $serial shell pm revoke --user $user $pkg $perm" >&2
         restore_problems+=("$perm: revoke could not be verified")
         rc=1 ;;
    esac
  done
  return "$rc"
}

# Print every problem together, then fail the run. Exits; never returns on failure.
report_restore_result() {
  test "${#restore_problems[@]}" -gt 0 || return 0
  echo "" >&2
  echo "device-contract: THE DEVICE WAS NOT FULLY RESTORED:" >&2
  printf '  - %s\n' "${restore_problems[@]}" >&2
  echo "  (the remediation command for each is in the banner above it)" >&2
  exit 1
}

restore_device() {
  # Runs from EXIT and, via the signal handlers below, from INT/TERM. Guard against a second pass:
  # it would be harmless but would reprint the banner, and a banner still on screen after a restore
  # that actually succeeded is its own kind of misinformation.
  test "$restore_done" = "0" || return 0
  restore_done=1

  # `|| true` on each: the helpers return non-zero to mean "recorded a problem", which under `set -e`
  # would otherwise abort the trap and skip everything after it.
  restore_sms_role || true
  restore_permissions || true
  adb -s "$serial" uninstall "$pkg.test" >/dev/null 2>&1 || true
  report_restore_result
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

# ...and CHECK that the INT handler actually installed. POSIX says a signal that was SIG_IGN when
# the shell started cannot be trapped, and bash obeys silently — `trap ... INT` appears to succeed
# and `trap -p INT` then reports `trap -- '' SIGINT`. A shell without job control sets SIGINT to
# SIG_IGN in every async child, so `device-contract.sh &`, a `make` recipe, many CI step runners,
# `nohup`, and some IDE run configurations all land here. That is precisely the launch style this
# handler was written for, and there Ctrl-C/cancel becomes a NO-OP: the run continues past the stop
# request to grant permissions and take the SMS role. The usual next move — escalating to SIGKILL —
# then skips the trap entirely and leaves portage as the device's texting app.
#
# TERM is unaffected, so the run is still stoppable; we refuse only when the SMS role is at stake,
# because an unstoppable run that takes it is the one case where not starting beats continuing.
case "$(trap -p INT)" in
  *"-- '' SIGINT"*)
    echo "SIGINT is ignored in this environment, so Ctrl-C/cancel will NOT restore the device." >&2
    echo "(A shell without job control sets SIGINT to SIG_IGN in async children — e.g. running" >&2
    echo " this script with '&', from a make recipe, or under a CI step runner.)" >&2
    if test "$needs_sms" = "1"; then
      echo "Refusing to take the default-SMS role in a run that cannot be interrupted." >&2
      echo "Run it in the foreground, or pass a non-SMS '<class>#<method>' filter." >&2
      exit 1
    fi
    echo "Continuing: this run does not touch the SMS role. SIGTERM still restores." >&2 ;;
esac

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
# `sed -n 1p`, not `head -1`: head exits after the first line, which can SIGPIPE `find` and make the
# pipeline report 141 under `set -o pipefail`. sed reads to EOF. Same reason as the role reads above.
test_apk="$(find app-recv/build/outputs/apk/androidTest/degoogle/debug -name '*.apk' -type f | sed -n 1p)"
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
  # BEFORE the write, not after. `add-role-holder` can COMMIT and then report non-zero — a
  # transport drop or adbd restart between the role commit and the status returning is an ordinary
  # adb failure mode. With the flag set afterwards, `set -e` aborted with role_taken=0, restore_sms_role
  # returned at its role_taken gate, and portage kept the role with no banner, no inventory and no
  # remediation. Setting it first is safe in the other direction: the restore's removal NAMES portage,
  # so "restoring" a take that never landed is a no-op.
  role_taken=1
  adb -s "$serial" shell cmd role add-role-holder --user "$user" "$role" "$pkg" >/dev/null
  # PROVE THE READ WORKS, here, while the answer is known.
  #
  # Everything protecting the user's texting app rests on `get-role-holders` telling the truth, and
  # the restore check compares two reads from that same command — so a broken read is invisible to
  # it: both sides come back empty, they match, and the run reports a successful restore having
  # verified nothing. This is the one moment the expected answer is known independently (we just
  # took the role), so it is the only place the read itself can be tested. If it cannot observe a
  # write we just made, abort before running a single test rather than proceed with a blind restore.
  # Retried via await_role_holder — see there for why a single read is not evidence.
  took="$(await_role_holder "$pkg")" || true
  if test "$took" = "$pkg"; then
    role_read_trusted=1
  else
    # NOT "the take had no effect". An earlier version concluded exactly that whenever the read-back
    # returned `prior`, set role_taken=0, and therefore SKIPPED the restore — which is right if the
    # take was refused and catastrophic if it merely had not become visible yet: portage keeps the
    # SMS role while the run reports it changed nothing. The retry above narrows that window but
    # cannot close it, and the two cases are genuinely indistinguishable from here.
    #
    # So do not disambiguate — say what is and is not known, leave role_taken=1 so the restore still
    # runs, and let it drop portage's claim (which is safe either way; see there).
    echo "Could not confirm the $role take: the holder reads '$took', not $pkg." >&2
    echo "Either the take was refused, or it has not become visible within the retry budget." >&2
    echo "Aborting rather than running with a restore check that cannot detect its own failure." >&2
    exit 1
  fi
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
ran="$(printf '%s\n' "$result" | sed -n '/^OK (/{s/^OK (\([0-9]\{1,\}\) test.*/\1/p;q;}')"
test -n "$ran" || { echo "device-contract: could not parse the test count from the OK line" >&2; exit 1; }
test "$ran" -gt 0 || {
  echo "device-contract: OK (0 tests) — nothing ran. Check the filter names a real class#method." >&2
  exit 1; }
echo "device-contract: $ran test(s) executed"
