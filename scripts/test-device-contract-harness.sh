#!/usr/bin/env bash
set -uo pipefail

# Self-test for scripts/device-contract.sh's DEVICE-RESTORE logic. Runs in CI; needs no phone.
#
# WHY THIS EXISTS
# ---------------
# device-contract.sh is the only thing standing between a contract run and a daily driver left
# holding portage as its texting app. That logic shipped two independent fail-open bugs, and both
# survived review because the only way to observe them was to attach a phone, break adb in a
# specific way, and read the exit code — which nobody does. It is the same shape CLAUDE.md records
# for the privilege-boundary gate: a restore that wrongly reports success is indistinguishable from
# one that rightly reports success, so it needs a test that can tell them apart.
#
# HOW IT WORKS
# ------------
# It runs the REAL script — not a reimplementation — against a stub `adb` on PATH, with a stub
# `./gradlew` and pre-made APK paths, in a scratch directory. The stub owns a mutable "device state"
# (who holds the SMS role, which permissions are granted) so scenarios can make a specific adb
# operation lie in a specific way and then assert on BOTH the script's exit status AND the final
# device state. Asserting on state as well as status is the point: "exited 0" and "gave the role
# back" are exactly the two things the historical bugs let drift apart.
#
# IF YOU CHANGE THE RESTORE LOGIC, MUTATION-TEST THIS. Splice the old behaviour back over the new
# one and confirm the named scenario goes RED; a self-test that still passes against the bug it was
# written for is worse than none. All THIRTEEN below were run and confirmed to go red:
#
#   mutation (revert this)                                            scenario that must fail
#   -----------------------------------------------------------------------------------------
#   restore_device's post-state re-read -> trust the command's rc      ROLE_HANDBACK_IGNORED
#   `tr -d` CR strip dropped from read_role_holder                     FILTER_PLAIN_CLASS
#   read_role_holder's `2>&1` -> `2>/dev/null`                         ROLE_READ_BLANK
#   read_role_holder's "must contain a dot" rule dropped               ROLE_READ_BARE_TOKEN
#   the empty-`prior` corroborating re-read removed                    ROLE_READ_TRANSIENT
#   the restore-side liveness re-check removed                         ROLE_READER_DIES_MID_RUN
#   post-take read-back dropped BUT role_read_trusted=1 asserted       TAKE_SILENTLY_IGNORED
#   the `prior == pkg` refusal removed                                 ROLE_ALREADY_PORTAGE
#   the revoke re-read -> a bare warning                               REVOKE_FAILS
#   perm_granted_for_user's `return 2` paths -> `return 1`             PERM_NO_RUNTIME_SECTION
#   signal handlers -> `trap restore_device INT TERM` (resume, no exit) SIGTERM_MID_RUN
#   the `trap -p INT` install check removed                            SIGINT_UNTRAPPABLE_REFUSES
#   the take-time read-back retry loop -> a single immediate read                TAKE_VISIBLE_LATE
#   whole-string `case` filter guard -> the line-oriented grep          FILTER_NEWLINE
#
# Recorded so nobody re-derives them:
#   - GUARDS THAT OVERLAP EACH OTHER SURVIVE EACH OTHER'S MUTATION. The post-take read-back and the
#     restore-side liveness check both catch ROLE_READ_SILENT, so deleting either leaves the suite
#     green. That is defence in depth, not slack — but it means each needs a scenario only IT can
#     catch. TAKE_SILENTLY_IGNORED is the read-back's (the reader is healthy; only the take failed);
#     ROLE_READER_DIES_MID_RUN is the liveness check's.
#   - Deleting the post-take read-back OUTRIGHT also clears `role_read_trusted`, so the restore-side
#     guard catches it. To isolate the read-back you must delete it AND assert trust anyway.
#   - ROLE_READ_BLANK pins the `2>&1` STDERR FOLD, not the whole-output `case`. Reverting the case
#     to the old `test -n "$prior" && ...` shape leaves the suite green, because the stderr text is
#     non-empty and the old shape rejects it too. The `case`'s own added strictness — the dot
#     requirement — is pinned by ROLE_READ_BARE_TOKEN instead. (An earlier version of this table
#     claimed otherwise; review caught it. Verify each row rather than trusting the list.)
#   - perm_granted_for_user's `grep` status >= 2 branch is NOT covered. A here-string grep does not
#     error, so the stub cannot provoke it; it is reachable only from a genuinely broken grep.
#   - The stub is CRLF-faithful because real `adb shell` emits CRLF. With an LF-only stub, deleting
#     any `tr -d` left the suite green while every real run aborted after taking the SMS role.
#   - ANDROID_SERIAL=STUBSERIAL is exported on every invocation deliberately: if the stub `adb` ever
#     failed to land on PATH, a real `adb` would fail on that serial rather than touching a phone.
#   - RUNTIME is ~13s, nearly all of it the script's take-time read-back retry burning its ~3s budget
#     in the scenarios that never converge. Do NOT add a test-only env knob to shorten it in the
#     production script: a timing override that exists for the tests is the kind of thing that later
#     gets set in anger on a real device, and 13s in a 10-minute job buys nothing worth that.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_UNDER_TEST="${SCRIPT_UNDER_TEST:-$REPO_ROOT/scripts/device-contract.sh}"
PKG="com.ventouxlabs.portage.recv"

test -f "$SCRIPT_UNDER_TEST" || { echo "not found: $SCRIPT_UNDER_TEST" >&2; exit 1; }

WORK="$(mktemp -d)" || exit 1
test -n "$WORK" && test -d "$WORK" || { echo "mktemp -d failed" >&2; exit 1; }
trap 'rm -rf "${WORK:?}"' EXIT
pass=0; fail=0

setup_tree() {
  # ${WORK:?} so an empty base aborts instead of expanding to `rm -rf /run` — this script runs
  # without `set -e` (scenarios must capture non-zero exits), so an assignment failure is survivable.
  rm -rf "${WORK:?}/run"; mkdir -p "${WORK:?}/run/bin" "$WORK/run/scripts" "$WORK/run/state"
  mkdir -p "$WORK/run/app-recv/build/outputs/apk/degoogle/debug"
  mkdir -p "$WORK/run/app-recv/build/outputs/apk/androidTest/degoogle/debug"
  touch "$WORK/run/app-recv/build/outputs/apk/degoogle/debug/app-recv-degoogle-debug.apk"
  touch "$WORK/run/app-recv/build/outputs/apk/androidTest/degoogle/debug/app-recv-androidTest.apk"
  cp "$SCRIPT_UNDER_TEST" "$WORK/run/scripts/device-contract.sh"
  # Optionally slow, so SIGNALS can be delivered while the script is mid-build.
  # shellcheck disable=SC2016  # $STUB_STATE must expand when gradlew RUNS, not when it is written
  printf '#!/usr/bin/env bash\n[ -f "$STUB_STATE/slow_build" ] && sleep 2\nexit 0\n' > "$WORK/run/gradlew"
  chmod +x "$WORK/run/gradlew"
  cp "$WORK/adb" "$WORK/run/bin/adb"
}

# The stub adb. Device state lives in $STUB_STATE so it survives across invocations within one run.
#   holder            file: current SMS role holder ("" = unheld)
#   cal_granted       file: "true"/"false" — calendar permission grant state
#   role_read_blank   flag: get-role-holders writes to stderr and exits 0 with EMPTY stdout
#   role_read_garbage flag: get-role-holders emits a non-package token
#   role_read_silent  flag: get-role-holders emits NOTHING at all and exits 0
#   handback_ignored  flag: add/remove-role-holder reports success WITHOUT moving the role
#   take_ignored      flag: the TAKE is silently refused; the reader stays healthy
#   take_visible_after file: N — the take lands but reads report the OLD holder N more times
#   revoke_ignored    flag: pm revoke reports success without revoking
#   perm_read_broken  flag: dumpsys emits no User block for the package at all
#   perm_no_runtime_section flag: dumpsys emits a User block with no "runtime permissions:" section
#   role_read_silent_once flag: fails silently on the FIRST call only (transient glitch)
#   role_read_bare_token  flag: emits a dot-less token, which the charset check alone accepts
#   role_read_dies_after  file: N — reads succeed N times, then fail silently forever
#   take_ignored      flag: the TAKE is silently refused; the reader stays healthy
#   take_visible_after file: N — the take lands but reads report the OLD holder N more times
#   slow_build        flag: the stub gradlew sleeps, so signals can land mid-build
#   tests             file: the test count the instrumentation reports
# Written BY the stub, asserted by scenarios:
#   role_ops          log:  every add/remove-role-holder, so "was the role touched at all?" is
#                           answerable — rc alone cannot distinguish "refused" from "refused after
#                           mutating the device"
#   instrument_argv   file: the `am instrument` argv, so the grants-prepared flag can be asserted
cat > "$WORK/adb" <<'STUB'
#!/usr/bin/env bash
S="${STUB_STATE:?}"
a=("$@")
[ "${a[0]:-}" = "-s" ] && a=("${a[@]:2}")
case "${a[0]:-}" in
  devices) echo "List of devices attached"; printf 'STUBSERIAL\tdevice\n'; exit 0 ;;
  install|uninstall) exit 0 ;;
  shell) a=("${a[@]:1}") ;;
  *) exit 0 ;;
esac
case "${a[0]:-} ${a[1]:-}" in
  "am get-current-user") printf '0\r\n'; exit 0 ;;
  "cmd role")
    case "${a[2]}" in
      get-role-holders)
        if [ -f "$S/role_read_blank" ]; then printf 'cmd: unknown option --user\r\n' >&2; exit 0; fi
        # SILENT failure: nothing on stdout, nothing on stderr, exit 0. Indistinguishable from
        # "the role is unheld" by any amount of output inspection.
        if [ -f "$S/role_read_silent" ]; then exit 0; fi
        # Fails silently on the FIRST call only. The take-time read-back cannot catch this: by then
        # the reader works again and confirms the take, so a `prior` captured during the glitch is
        # trusted and the restore removes a role the user's real app holds.
        if [ -f "$S/role_read_silent_once" ] && [ ! -f "$S/role_read_once_done" ]; then
          : > "$S/role_read_once_done"; exit 0
        fi
        if [ -f "$S/role_read_garbage" ]; then printf 'Exception: SecurityException blah\r\n'; exit 0; fi
        # A BARE token: no dot, so not a package name. Only the dot requirement rejects this —
        # the charset check alone accepts it.
        if [ -f "$S/role_read_bare_token" ]; then printf 'notapackage\r\n'; exit 0; fi
        # Reader that works at take-time and dies AFTER n calls: the window the take-time
        # read-back cannot cover, because it only proves the reader worked at that instant.
        if [ -f "$S/role_read_dies_after" ]; then
          n=$(cat "$S/role_read_calls" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$S/role_read_calls"
          [ "$n" -gt "$(cat "$S/role_read_dies_after")" ] && exit 0
        fi
        # The write landed but is not yet VISIBLE — an async role commit. Models the platform
        # timing question this script must not assume: report the PREVIOUS holder for the first N
        # reads after a take, then the truth.
        if [ -f "$S/take_visible_after" ] && [ -f "$S/take_happened" ]; then
          n=$(cat "$S/stale_reads" 2>/dev/null || echo 0)
          if [ "$n" -lt "$(cat "$S/take_visible_after")" ]; then
            echo $((n + 1)) > "$S/stale_reads"
            printf '%s\r\n' "$(cat "$S/prev_holder" 2>/dev/null)"; exit 0
          fi
        fi
        h="$(cat "$S/holder" 2>/dev/null || true)"; [ -n "$h" ] && printf '%s\r\n' "$h"; exit 0 ;;
      add-role-holder)
        t="${a[*]: -1}"
        printf 'add %s\n' "$t" >> "$S/role_ops"
        # Must affect the HANDBACK only. If it also blocked portage from taking the role,
        # restore would trivially succeed and the scenario would prove nothing.
        if [ -f "$S/handback_ignored" ] && [ "$t" != "com.ventouxlabs.portage.recv" ]; then exit 0; fi
        # The mirror image: the TAKE is silently refused while the reader stays perfectly healthy.
        # Only reading the role back after taking it can notice — every later guard sees a
        # consistent, working device that simply never gave us the role.
        if [ -f "$S/take_ignored" ] && [ "$t" = "com.ventouxlabs.portage.recv" ]; then exit 0; fi
        if [ "$t" = "com.ventouxlabs.portage.recv" ]; then
          cat "$S/holder" > "$S/prev_holder" 2>/dev/null; : > "$S/take_happened"
        fi
        echo "$t" > "$S/holder"; exit 0 ;;
      remove-role-holder)
        printf 'remove\n' >> "$S/role_ops"
        [ -f "$S/handback_ignored" ] && exit 0
        : > "$S/holder"; exit 0 ;;
    esac ;;
  "dumpsys package")
    if [ -f "$S/perm_read_broken" ]; then
      printf 'Packages:\r\n  Package [x] (a):\r\n'; exit 0
    fi
    # A real User 0 block that simply has no "runtime permissions:" section — a DIFFERENT guard
    # from the empty-block one above, and the one that would otherwise go untested.
    if [ -f "$S/perm_no_runtime_section" ]; then
      printf 'Packages:\r\n  Package [com.ventouxlabs.portage.recv] (abc):\r\n    User 0: ceDataInode=1 installed=true\r\n      install permissions:\r\n        android.permission.INTERNET: granted=true\r\n'
      exit 0
    fi
    g="$(cat "$S/cal_granted" 2>/dev/null || echo false)"
    cat <<EOF | sed 's/$/\r/'
Packages:
  Package [com.ventouxlabs.portage.recv] (abc):
    User 0: ceDataInode=1 installed=true hidden=false
      runtime permissions:
        android.permission.READ_CALENDAR: granted=$g
        android.permission.WRITE_CALENDAR: granted=$g
    User 10: ceDataInode=2 installed=true
      runtime permissions:
        android.permission.READ_CALENDAR: granted=true
        android.permission.WRITE_CALENDAR: granted=true
EOF
    exit 0 ;;
  "pm grant") echo true > "$S/cal_granted"; exit 0 ;;
  "pm revoke")
    [ -f "$S/revoke_ignored" ] && exit 0
    echo false > "$S/cal_granted"; exit 0 ;;
  "am instrument")
    # Recorded so a scenario can assert the argv, not just the outcome. Dropping
    # `-e portage_grants_prepared true` turns every precondition back into a skip that still
    # prints OK — invisible unless the harness looks at what was actually invoked.
    printf '%s\n' "${a[*]}" > "$S/instrument_argv"
    printf 'Time: 1.0\r\n\r\nOK (%s tests)\r\n' "$(cat "$S/tests" 2>/dev/null || echo 7)"; exit 0 ;;
esac
# Any adb call this stub does not model is a HOLE in the harness, not a no-op. Failing loudly means
# a device read added to the script later cannot be silently invisible here.
printf 'stub adb: unmodelled command: %s\n' "${a[*]}" >&2
exit 97
STUB
chmod +x "$WORK/adb"

# scenario <name> <expected-rc: 0|nonzero> <expected-final-holder> -- <state settings...>
scenario() {
  test "$#" -ge 4 || { echo "scenario: needs >=4 args, got $#" >&2; fail=$((fail + 1)); return; }
  local name="$1" want_rc="$2" want_holder="$3"; shift 4
  setup_tree
  echo "com.example.sms" > "$WORK/run/state/holder"
  echo false > "$WORK/run/state/cal_granted"
  local s extra_env=""
  for s in "$@"; do
    case "$s" in
      holder=*) printf '%s' "${s#holder=}" > "$WORK/run/state/holder" ;;
      tests=*)  echo "${s#tests=}" > "$WORK/run/state/tests" ;;
      env=*)    extra_env="${s#env=}" ;;
      role_read_dies_after=*) echo "${s#role_read_dies_after=}" > "$WORK/run/state/role_read_dies_after" ;;
      take_visible_after=*) echo "${s#take_visible_after=}" > "$WORK/run/state/take_visible_after" ;;
      *)        touch "$WORK/run/state/$s" ;;
    esac
  done

  local out rc holder granted
  out="$(cd "$WORK/run" && env STUB_STATE="$WORK/run/state" PATH="$WORK/run/bin:$PATH" \
    ANDROID_SERIAL=STUBSERIAL ${extra_env:+"$extra_env"} \
    bash scripts/device-contract.sh 2>&1)"; rc=$?
  holder="$(cat "$WORK/run/state/holder" 2>/dev/null || true)"
  granted="$(cat "$WORK/run/state/cal_granted" 2>/dev/null || true)"

  local ok=1 why=""
  case "$want_rc" in
    0)       test "$rc" -eq 0 || { ok=0; why="$why expected rc=0, got $rc;"; } ;;
    nonzero) test "$rc" -ne 0 || { ok=0; why="$why expected nonzero rc, got 0;"; } ;;
    # Without this arm a typo'd want_rc ("nonZero") asserted nothing at all and printed ok.
    *) ok=0; why="$why bad want_rc '$want_rc' — expected 0 or nonzero;" ;;
  esac
  test "$holder" = "$want_holder" ||
    { ok=0; why="$why expected holder '$want_holder', got '$holder';"; }
  # Whatever else happened, a run that reports success must not leave the permission behind.
  if test "$rc" -eq 0 && test "$granted" = "true"; then
    ok=0; why="$why exited 0 with calendar permission still granted;"
  fi
  # And any run that reached instrumentation must have carried the grants-prepared flag. Without it
  # every precondition in the suite reverts to an assumption-skip that JUnit still counts toward
  # `OK (N tests)` — the run would report success having verified nothing, and no outcome-based
  # assertion can see that. This is why the stub records argv.
  if test -f "$WORK/run/state/instrument_argv" &&
     ! grep -q 'portage_grants_prepared true' "$WORK/run/state/instrument_argv"; then
    ok=0; why="$why instrumented WITHOUT -e portage_grants_prepared true;"
  fi

  if test "$ok" = "1"; then
    printf 'ok   %s\n' "$name"; pass=$((pass + 1))
  else
    printf 'FAIL %s —%s\n' "$name" "$why"; fail=$((fail + 1))
    printf '%s\n' "$out" | sed 's/^/       | /'
  fi
}

# signal_case <name> <signal> <want-rc>
#
# Delivers a signal to the SCRIPT ONLY (not the process group) while it is mid-build. That
# distinction is the whole point: Ctrl-C at a terminal signals the group, so the child dies and
# `set -e` aborts anyway — which masked, for the life of this script, that the handler cleaned up
# and then RESUMED. A lone `kill -TERM` (timeout, CI cancel, IDE stop) does not have that cover, and
# the script would carry on to take the SMS role after being told to stop.
signal_case() {
  local name="$1" sig="$2" want_rc="$3"
  setup_tree
  echo "com.example.sms" > "$WORK/run/state/holder"
  echo false > "$WORK/run/state/cal_granted"
  : > "$WORK/run/state/slow_build"
  local pid rc holder ops ok=1 why=""
  # `set -m` matters and is not incidental: without job control, bash sets SIGINT to SIG_IGN in
  # async children, the script's INT handler cannot install, and (correctly) it now refuses to take
  # the SMS role at all. Job control gives the child its own process group, so the signal below
  # reaches the script ALONE — which is the case being tested. See NO_JOB_CONTROL_REFUSES for the
  # other half (SIGINT_UNTRAPPABLE_REFUSES).
  set -m
  ( cd "$WORK/run" && STUB_STATE="$WORK/run/state" PATH="$WORK/run/bin:$PATH" \
    ANDROID_SERIAL=STUBSERIAL exec bash scripts/device-contract.sh ) >/dev/null 2>&1 &
  pid=$!
  set +m
  sleep 0.5
  kill "-$sig" "$pid" 2>/dev/null
  wait "$pid"; rc=$?
  holder="$(cat "$WORK/run/state/holder" 2>/dev/null || true)"
  ops="$(cat "$WORK/run/state/role_ops" 2>/dev/null || true)"
  test "$rc" = "$want_rc" || { ok=0; why="$why expected rc=$want_rc, got $rc;"; }
  # The signal lands during the build, before the role is taken. A script that honoured it touched
  # nothing; one that resumed went on to take the role.
  test -z "$ops" || { ok=0; why="$why kept going and mutated the role after $sig: $ops;"; }
  test "$holder" = "com.example.sms" || { ok=0; why="$why holder became '$holder';"; }
  if test "$ok" = "1"; then printf 'ok   %s\n' "$name"; pass=$((pass + 1))
  else printf 'FAIL %s —%s\n' "$name" "$why"; fail=$((fail + 1)); fi
}

echo "device-contract harness self-test — $(basename "$SCRIPT_UNDER_TEST")"
echo

# --- the restore contract -----------------------------------------------------------------------
scenario HAPPY_PATH              0       com.example.sms --
scenario ROLE_HANDBACK_IGNORED   nonzero "$PKG"          -- handback_ignored
scenario ROLE_READ_GARBAGE       nonzero com.example.sms -- role_read_garbage
# The bug my first mutation test missed: a failed read with EMPTY stdout. It aliased onto "nobody
# held the role", which selects the branch that REMOVES it, and the post-check compared two blank
# reads and called them equal.
scenario ROLE_READ_BLANK         nonzero com.example.sms -- role_read_blank
# A leaked role from a previously-killed run must not be silently blessed as the state to restore to.
scenario ROLE_ALREADY_PORTAGE    nonzero "$PKG"          -- "holder=$PKG"
# A read that fails SILENTLY — empty stdout, empty stderr, exit 0 — is the one failure no amount of
# output validation can catch, because it is byte-identical to "the role is unheld". Only reading
# back a write we just made can. And once the read is known bad, `prior` is fiction, so the restore
# must refuse to act on it rather than remove a role claim that may belong to the user's real app.
scenario ROLE_READ_SILENT        nonzero "$PKG"          -- role_read_silent
# A reader that glitches ONCE, on the very first call, yields "" — the value that selects the
# destructive branch. The take-time read-back cannot rescue it (the reader works again by then), so
# an empty answer is corroborated by a second read before it is believed.
# The take is silently refused and the reader is fine. Nothing downstream can tell — the device
# looks consistent and simply never handed over the role. Only the post-take read-back sees it, and
# because the reader demonstrably works, the script can say "nothing was changed" instead of
# warning about a role it never took.
# The take commits but is not visible to the next read — an async role commit. A single immediate
# read would lose that race, conclude "nothing changed", skip the restore, and leave portage holding
# the SMS role. The run must ride it out and finish normally.
scenario TAKE_VISIBLE_LATE       0       com.example.sms -- take_visible_after=2
scenario TAKE_SILENTLY_IGNORED   nonzero com.example.sms -- take_ignored
scenario ROLE_READ_TRANSIENT     nonzero com.example.sms -- role_read_silent_once
# A bare token passes the charset test; only the "must contain a dot" rule rejects it.
scenario ROLE_READ_BARE_TOKEN    nonzero com.example.sms -- role_read_bare_token
# Reader alive at take-time, dead by restore: the liveness re-check must refuse to remove the role
# on the word of a reader that is already wrong.
scenario ROLE_READER_DIES_MID_RUN nonzero "$PKG"         -- holder= role_read_dies_after=3
# The escape hatch takes the REAL prior holder, so the only way past the leak refusal is the one
# that actually gives the device its texting app back.
scenario PRIOR_SMS_OVERRIDE      0       com.example.sms -- "holder=$PKG" "env=PORTAGE_CONTRACT_PRIOR_SMS=com.example.sms"
scenario NO_PRIOR_HOLDER         0       ""              -- holder=
scenario NO_PRIOR_REMOVE_IGNORED nonzero "$PKG"          -- holder= handback_ignored

# --- permissions --------------------------------------------------------------------------------
scenario REVOKE_FAILS            nonzero com.example.sms -- revoke_ignored
scenario PERM_NO_USER_BLOCK      nonzero com.example.sms -- perm_read_broken
scenario PERM_NO_RUNTIME_SECTION nonzero com.example.sms -- perm_no_runtime_section

# --- the result gate ----------------------------------------------------------------------------
scenario ZERO_TESTS              nonzero com.example.sms -- tests=0

# --- signals: the handler must TERMINATE, not clean up and resume -------------------------------
signal_case SIGTERM_MID_RUN      TERM 143
signal_case SIGINT_MID_RUN       INT  130

# POSIX forbids trapping a signal that was SIG_IGN when the shell started, and bash obeys SILENTLY,
# so the INT handler never installs and Ctrl-C/cancel becomes a no-op. A run that cannot be
# interrupted must not take the user's default-SMS role.
#
# The condition is CONSTRUCTED (`trap "" INT` then `exec`) rather than induced by launching async
# from a non-job-control shell. Both produce it, but the async route depends on the ambient shell's
# job-control state, which is not the same everywhere: this scenario passed locally and failed on a
# GitHub runner, whose shell left SIGINT trappable in async children. `trap "" INT; exec` sets the
# disposition explicitly, so the test measures the guard rather than the environment around it.
sigint_untrappable_case() {
  setup_tree
  echo "com.example.sms" > "$WORK/run/state/holder"
  local rc ops ok=1 why=""
  ( cd "$WORK/run" && STUB_STATE="$WORK/run/state" PATH="$WORK/run/bin:$PATH" \
    ANDROID_SERIAL=STUBSERIAL bash -c 'trap "" INT; exec bash scripts/device-contract.sh' \
    >/dev/null 2>&1 ); rc=$?
  ops="$(cat "$WORK/run/state/role_ops" 2>/dev/null || true)"
  test "$rc" -ne 0 || { ok=0; why="$why expected a refusal, got rc=0;"; }
  test -z "$ops"   || { ok=0; why="$why took the role in an uninterruptible run: $ops;"; }
  if test "$ok" = "1"; then printf 'ok   SIGINT_UNTRAPPABLE_REFUSES\n'; pass=$((pass + 1))
  else printf 'FAIL SIGINT_UNTRAPPABLE_REFUSES —%s\n' "$why"; fail=$((fail + 1)); fi
}
sigint_untrappable_case

# --- filter validation (no device mutation expected) --------------------------------------------
# filter_case <name> <filter> <accept|reject> [<role: touched|untouched>]
#
# Asserts the ROLE OPS as well as the exit code. rc alone is not enough in either direction: a
# rejected filter must mutate NOTHING (a regression moving the guard after the take would still
# exit 1), and an accepted one must respect the deliberate fail-closed rule that only a filter
# naming a specific non-SMS #method may skip the role handoff — a class-only filter still runs the
# SMS test, so it must still take the role.
filter_case() {
  local name="$1" filter="$2" want="$3" role_want="${4:-}"
  setup_tree
  echo "com.example.sms" > "$WORK/run/state/holder"
  local rc ops ok=1 why=""
  (cd "$WORK/run" && STUB_STATE="$WORK/run/state" PATH="$WORK/run/bin:$PATH" \
    ANDROID_SERIAL=STUBSERIAL bash scripts/device-contract.sh "$filter" >/dev/null 2>&1); rc=$?
  ops="$(cat "$WORK/run/state/role_ops" 2>/dev/null || true)"
  case "$want" in
    reject) test "$rc" -ne 0 || { ok=0; why="$why wanted reject, rc=0;"; }
            test -z "$ops"   || { ok=0; why="$why rejected but MUTATED the role: $ops;"; } ;;
    accept) test "$rc" -eq 0 || { ok=0; why="$why wanted accept, rc=$rc;"; } ;;
    *) ok=0; why="$why bad want '$want';" ;;
  esac
  case "$role_want" in
    "") ;;
    touched)   test -n "$ops" || { ok=0; why="$why expected the role to be taken, it was not;"; } ;;
    untouched) test -z "$ops" || { ok=0; why="$why expected the role untouched, got: $ops;"; } ;;
    *) ok=0; why="$why bad role_want '$role_want';" ;;
  esac
  if test "$ok" = "1"; then
    printf 'ok   %s\n' "$name"; pass=$((pass + 1))
  else
    printf 'FAIL %s —%s\n' "$name" "$why"; fail=$((fail + 1))
  fi
}
# A class-only filter still runs the SMS test, so it MUST still take the role (fail-closed rule).
filter_case FILTER_PLAIN_CLASS   "com.ventouxlabs.portage.recv.ProviderDeviceContractTest" accept touched
# A specific non-SMS #method must not make portage the user's texting app just to check a calendar.
filter_case FILTER_METHOD        "com.a.B#calendarCreates"                                 accept untouched
# grep is LINE-oriented, so `^...$` anchored per line and `-q` matched ANY line: a well-formed first
# line smuggled a second device-side command past validation, to run as shell uid.
filter_case FILTER_NEWLINE       "$(printf 'com.a.B\nrm -rf /sdcard')"                      reject
filter_case FILTER_SEMICOLON     "com.a.B;id"                                              reject
# shellcheck disable=SC2016  # single quotes are the point: pass $(...) and `...` through LITERALLY
filter_case FILTER_SUBSHELL      'com.a.B$(id)'                                            reject
# shellcheck disable=SC2016
filter_case FILTER_BACKTICK      'com.a.B`id`'                                             reject

echo
echo "passed: $pass   failed: $fail"
test "$fail" -eq 0
