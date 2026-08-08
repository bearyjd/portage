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
# written for is worse than none. All eight below were run and confirmed to go red:
#
#   mutation (revert this)                                          scenario that must fail
#   ---------------------------------------------------------------------------------------
#   restore_device's post-state re-read -> trust the command's rc    ROLE_HANDBACK_IGNORED
#   `prior` whole-output validation -> `test -n "$prior" && ...`     ROLE_READ_BLANK
#   drop the post-take read-back BUT still set role_read_trusted=1   ROLE_READ_SILENT
#   restore ignores role_read_trusted and guesses anyway             ROLE_READ_SILENT
#   the `prior == pkg` refusal removed                               ROLE_ALREADY_PORTAGE
#   the revoke re-read -> a bare warning                             REVOKE_FAILS
#   perm_granted_for_user's `return 2` paths -> `return 1`           PERM_NO_RUNTIME_SECTION
#   the whole-string `case` filter guard -> the line-oriented grep    FILTER_NEWLINE
#
# Two notes recorded so nobody re-derives them:
#   - Simply DELETING the post-take read-back does NOT turn ROLE_READ_SILENT red, because that also
#     clears `role_read_trusted` and the restore-side guard catches it instead. The two are
#     deliberately redundant; to isolate either, mutate one and leave the other intact.
#   - perm_granted_for_user's `grep` status >= 2 branch is NOT covered here. A here-string grep does
#     not error, so the stub cannot provoke it. It is reachable only from a genuinely broken grep.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_UNDER_TEST="${SCRIPT_UNDER_TEST:-$REPO_ROOT/scripts/device-contract.sh}"
PKG="com.ventouxlabs.portage.recv"

test -f "$SCRIPT_UNDER_TEST" || { echo "not found: $SCRIPT_UNDER_TEST" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0

setup_tree() {
  rm -rf "$WORK/run"; mkdir -p "$WORK/run/bin" "$WORK/run/scripts" "$WORK/run/state"
  mkdir -p "$WORK/run/app-recv/build/outputs/apk/degoogle/debug"
  mkdir -p "$WORK/run/app-recv/build/outputs/apk/androidTest/degoogle/debug"
  touch "$WORK/run/app-recv/build/outputs/apk/degoogle/debug/app-recv-degoogle-debug.apk"
  touch "$WORK/run/app-recv/build/outputs/apk/androidTest/degoogle/debug/app-recv-androidTest.apk"
  cp "$SCRIPT_UNDER_TEST" "$WORK/run/scripts/device-contract.sh"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/run/gradlew"; chmod +x "$WORK/run/gradlew"
  cp "$WORK/adb" "$WORK/run/bin/adb"
}

# The stub adb. Device state lives in $STUB_STATE so it survives across invocations within one run.
#   holder            file: current SMS role holder ("" = unheld)
#   cal_granted       file: "true"/"false" — calendar permission grant state
#   role_read_blank   flag: get-role-holders writes to stderr and exits 0 with EMPTY stdout
#   role_read_garbage flag: get-role-holders emits a non-package token
#   role_read_silent  flag: get-role-holders emits NOTHING at all and exits 0
#   handback_ignored  flag: add/remove-role-holder reports success WITHOUT moving the role
#   revoke_ignored    flag: pm revoke reports success without revoking
#   perm_read_broken  flag: dumpsys emits no User block for the package at all
#   perm_no_runtime_section flag: dumpsys emits a User block with no "runtime permissions:" section
#   tests             file: the test count the instrumentation reports
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
  "am get-current-user") echo 0; exit 0 ;;
  "cmd role")
    case "${a[2]}" in
      get-role-holders)
        if [ -f "$S/role_read_blank" ]; then echo "cmd: unknown option --user" >&2; exit 0; fi
        # SILENT failure: nothing on stdout, nothing on stderr, exit 0. Indistinguishable from
        # "the role is unheld" by any amount of output inspection.
        if [ -f "$S/role_read_silent" ]; then exit 0; fi
        if [ -f "$S/role_read_garbage" ]; then echo "Exception: SecurityException blah"; exit 0; fi
        h="$(cat "$S/holder" 2>/dev/null || true)"; [ -n "$h" ] && echo "$h"; exit 0 ;;
      add-role-holder)
        t="${a[*]: -1}"
        # Must affect the HANDBACK only. If it also blocked portage from taking the role,
        # restore would trivially succeed and the scenario would prove nothing.
        if [ -f "$S/handback_ignored" ] && [ "$t" != "com.ventouxlabs.portage.recv" ]; then exit 0; fi
        echo "$t" > "$S/holder"; exit 0 ;;
      remove-role-holder)
        [ -f "$S/handback_ignored" ] && exit 0
        : > "$S/holder"; exit 0 ;;
    esac ;;
  "dumpsys package")
    if [ -f "$S/perm_read_broken" ]; then
      printf 'Packages:\n  Package [x] (a):\n'; exit 0
    fi
    # A real User 0 block that simply has no "runtime permissions:" section — a DIFFERENT guard
    # from the empty-block one above, and the one that would otherwise go untested.
    if [ -f "$S/perm_no_runtime_section" ]; then
      printf 'Packages:\n  Package [com.ventouxlabs.portage.recv] (abc):\n    User 0: ceDataInode=1 installed=true\n      install permissions:\n        android.permission.INTERNET: granted=true\n'
      exit 0
    fi
    g="$(cat "$S/cal_granted" 2>/dev/null || echo false)"
    cat <<EOF
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
    printf 'Time: 1.0\n\nOK (%s tests)\n' "$(cat "$S/tests" 2>/dev/null || echo 7)"; exit 0 ;;
esac
exit 0
STUB
chmod +x "$WORK/adb"

# scenario <name> <expected-rc: 0|nonzero> <expected-final-holder> -- <state settings...>
scenario() {
  local name="$1" want_rc="$2" want_holder="$3"; shift 4
  setup_tree
  echo "com.example.sms" > "$WORK/run/state/holder"
  echo false > "$WORK/run/state/cal_granted"
  local s
  for s in "$@"; do
    case "$s" in
      holder=*) printf '%s' "${s#holder=}" > "$WORK/run/state/holder" ;;
      tests=*)  echo "${s#tests=}" > "$WORK/run/state/tests" ;;
      *)        touch "$WORK/run/state/$s" ;;
    esac
  done

  local out rc holder granted
  out="$(cd "$WORK/run" && STUB_STATE="$WORK/run/state" PATH="$WORK/run/bin:$PATH" \
    ANDROID_SERIAL=STUBSERIAL bash scripts/device-contract.sh 2>&1)"; rc=$?
  holder="$(cat "$WORK/run/state/holder" 2>/dev/null || true)"
  granted="$(cat "$WORK/run/state/cal_granted" 2>/dev/null || true)"

  local ok=1 why=""
  case "$want_rc" in
    0)       test "$rc" -eq 0 || { ok=0; why="$why expected rc=0, got $rc;"; } ;;
    nonzero) test "$rc" -ne 0 || { ok=0; why="$why expected nonzero rc, got 0;"; } ;;
  esac
  test "$holder" = "$want_holder" ||
    { ok=0; why="$why expected holder '$want_holder', got '$holder';"; }
  # Whatever else happened, a run that reports success must not leave the permission behind.
  if test "$rc" -eq 0 && test "$granted" = "true"; then
    ok=0; why="$why exited 0 with calendar permission still granted;"
  fi

  if test "$ok" = "1"; then
    printf 'ok   %s\n' "$name"; pass=$((pass + 1))
  else
    printf 'FAIL %s —%s\n' "$name" "$why"; fail=$((fail + 1))
    printf '%s\n' "$out" | sed 's/^/       | /'
  fi
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
scenario NO_PRIOR_HOLDER         0       ""              -- holder=
scenario NO_PRIOR_REMOVE_IGNORED nonzero "$PKG"          -- holder= handback_ignored

# --- permissions --------------------------------------------------------------------------------
scenario REVOKE_FAILS            nonzero com.example.sms -- revoke_ignored
scenario PERM_NO_USER_BLOCK      nonzero com.example.sms -- perm_read_broken
scenario PERM_NO_RUNTIME_SECTION nonzero com.example.sms -- perm_no_runtime_section

# --- the result gate ----------------------------------------------------------------------------
scenario ZERO_TESTS              nonzero com.example.sms -- tests=0

# --- filter validation (no device mutation expected) --------------------------------------------
filter_case() {
  local name="$1" filter="$2" want="$3"
  setup_tree
  echo "com.example.sms" > "$WORK/run/state/holder"
  local rc
  (cd "$WORK/run" && STUB_STATE="$WORK/run/state" PATH="$WORK/run/bin:$PATH" \
    ANDROID_SERIAL=STUBSERIAL bash scripts/device-contract.sh "$filter" >/dev/null 2>&1); rc=$?
  if { test "$want" = "reject" && test "$rc" -ne 0; } ||
     { test "$want" = "accept" && test "$rc" -eq 0; }; then
    printf 'ok   %s\n' "$name"; pass=$((pass + 1))
  else
    printf 'FAIL %s — wanted %s, rc=%s\n' "$name" "$want" "$rc"; fail=$((fail + 1))
  fi
}
filter_case FILTER_PLAIN_CLASS   "com.ventouxlabs.portage.recv.ProviderDeviceContractTest" accept
filter_case FILTER_METHOD        "com.a.B#calendarCreates"                                 accept
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
