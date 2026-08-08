#!/usr/bin/env python3
"""Mutation-test scripts/test-device-contract-harness.sh.

Reverts one guard at a time in the REAL device-contract.sh and asserts the named scenario goes red.
Python, not bash: the transforms embed shell quotes, and four levels of nesting silently truncated
the previous bash runner (it executed 14 of 16 mutations and reported success).
"""
import subprocess, sys, tempfile

import os
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = f"{REPO}/scripts/device-contract.sh"
HARNESS = f"{REPO}/scripts/test-device-contract-harness.sh"
# tempfile, not a fixed /tmp name: two concurrent runs would clobber each other's mutant and
# then execute whatever landed at that path.
MUT = tempfile.mkstemp(prefix="dc-mutant-", suffix=".sh")[1]
src = open(SRC).read()

def cut(text, start, end, replacement=""):
    i = text.index(start); j = text.index(end, i)
    return text[:i] + replacement + text[j:]

def check(name, want, build):
    try:
        mutant = build(src)
    except Exception as exc:               # stale anchor after a reformat, typically
        print(f"BROKEN {name:28s} transform raised: {type(exc).__name__}: {exc}")
        return False
    if mutant == src:
        print(f"BROKEN {name:28s} transform changed nothing"); return False
    open(MUT, "w").write(mutant)
    r = subprocess.run(["bash", HARNESS], capture_output=True, text=True,
                       env={"PATH": os.environ.get("PATH", "/usr/bin:/bin"),
                            "HOME": os.environ.get("HOME", os.path.expanduser("~")),
                            "SCRIPT_UNDER_TEST": MUT})
    red = any(l.startswith(f"FAIL {want}") for l in r.stdout.splitlines())
    print(f"{'ok  ' if red else 'FAIL'} {name:28s} -> {want}"
          f"{'' if red else '  DID NOT FAIL — guard is not load-bearing'}")
    return red

M = []
def m(name, want, fn): M.append((name, want, fn))

m("ROLE_POSTSTATE_REREAD", "ROLE_HANDBACK_IGNORED", lambda s: cut(
    s, '  if now="$(await_role_holder "$prior")"; then\n    return 0\n  fi',
    '  echo "" >&2\n  echo "!!! DEFAULT-SMS ROLE RESTORE FAILED', '  return 0\n'))
m("CRLF_STRIP_DROPPED", "FILTER_PLAIN_CLASS",
  lambda s: s.replace("tr -d " + chr(39) + chr(92) + "r" + chr(39) + " | sed -n 1p", "sed -n 1p"))
m("STDERR_FOLD_DROPPED", "ROLE_READ_BLANK", lambda s: s.replace(
    'cmd role get-role-holders --user "$user" "$role" 2>&1 |',
    'cmd role get-role-holders --user "$user" "$role" 2>/dev/null |'))
m("DOT_REQUIREMENT_DROPPED", "ROLE_READ_BARE_TOKEN", lambda s: s.replace(
    """    *.*) printf '%s' "$out" ; return 0 ;;\n    *) return 1 ;;""",
    """    *) printf '%s' "$out" ; return 0 ;;"""))
m("EMPTY_PRIOR_CORROBORATION", "ROLE_READ_TRANSIENT", lambda s: cut(
    s, '  if test -z "$prior"; then', '  # portage ALREADY holding the role'))
m("RESTORE_LIVENESS_CHECK", "ROLE_READER_DIES_MID_RUN", lambda s: cut(
    s, '    local live', '    fi\n  fi',
    '    adb -s "$serial" shell cmd role remove-role-holder --user "$user" "$role" "$pkg" '
    '>/dev/null || true\n    if false; then :\n'))
m("POST_TAKE_READBACK", "TAKE_SILENTLY_IGNORED", lambda s: cut(
    s, '  took="$(await_role_holder "$pkg")"', 'fi\n\n# Built ONCE',
    '  role_read_trusted=1\n'))
m("TAKE_AWAIT_SINGLE_READ", "TAKE_VISIBLE_LATE", lambda s: s.replace(
    '  took="$(await_role_holder "$pkg")" || true',
    '  took="$(read_role_holder)" || took="<unreadable>"'))
m("HANDBACK_AWAIT_SINGLE_READ", "HANDBACK_VISIBLE_LATE", lambda s: s.replace(
    '  if now="$(await_role_holder "$prior")"; then',
    '  if now="$(read_role_holder)" && test "$now" = "$prior"; then'))
m("OVERRIDE_ACCEPTS_PORTAGE", "OVERRIDE_POINTS_AT_PORTAGE", lambda s: cut(
    s, '        "$pkg")\n          echo "PORTAGE_CONTRACT_PRIOR_SMS is $pkg',
    '        *[!A-Za-z0-9._]*|.*|*.) echo "PORTAGE_CONTRACT_PRIOR_SMS is not a package name"'))
m("PRIOR_EQ_PKG_REFUSAL", "ROLE_ALREADY_PORTAGE", lambda s: cut(
    s, '  if test "$prior" = "$pkg"; then', 'fi\n\n# The calendar contract test'))
m("REVOKE_BARE_WARNING", "REVOKE_FAILS", lambda s: cut(
    s, '    perm_status=0\n    perm_granted_for_user', '  done\n  return "$rc"', '    :\n'))
m("PERM_STATUS_COLLAPSE", "PERM_NO_RUNTIME_SECTION", lambda s: s.replace(
    """    echo "no 'runtime permissions:' section for user $user — refusing to guess" >&2; return 2; }""",
    """    return 1; }"""))
m("SIGNAL_HANDLERS_RESUME", "SIGTERM_MID_RUN", lambda s: s.replace(
    "trap 'restore_device; trap - INT; kill -INT $$' INT\n"
    "trap 'restore_device; trap - TERM; kill -TERM $$' TERM",
    "trap restore_device INT TERM"))
m("SIGINT_INSTALL_CHECK", "SIGINT_UNTRAPPABLE_REFUSES", lambda s: cut(
    s, 'case "$(trap -p INT)" in', 'esac\n\nexport ANDROID_SERIAL')
    .replace("esac\n\nexport ANDROID_SERIAL", "export ANDROID_SERIAL", 1))
m("FILTER_LINE_ORIENTED_GREP", "FILTER_NEWLINE", lambda s: cut(
    s, 'case "$filter" in\n  "") ;;', 'if test -n "$filter" &&').replace(
    '! [[ "$filter" =~ ^[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?(,[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?)*$ ]]',
    "! printf '%s' \"$filter\" | grep -qE "
    "'^[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?(,[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?)*$'"))
m("UNTRUSTED_ABSTAINS", "ROLE_READ_SILENT", lambda s: cut(
    s, '    if test -n "$prior"; then\n      echo "  Attempting to hand',
    "    echo \"  Dropped $pkg's claim on $role"))
# The restore_problems[] feature itself. Before these three the whole inventory was unverified:
# deleting its printf, keeping only the last problem, or never calling the reporter at all left the
# suite fully green.
INV_PRINTF = "  printf '  - %s" + chr(92) + "n' " + chr(34) + "${restore_problems[@]}" + chr(34) + " >&2" + chr(10)
m("INVENTORY_NOT_PRINTED", "ROLE_AND_PERM_BOTH_FAIL", lambda s: s.replace(INV_PRINTF, ""))
m("ONLY_LAST_PROBLEM_KEPT", "ROLE_AND_PERM_BOTH_FAIL",
  lambda s: s.replace("restore_problems+=(", "restore_problems=("))
m("REPORT_NEVER_CALLED", "ROLE_AND_PERM_BOTH_FAIL",
  lambda s: s.replace("  report_restore_result\n}", "  return 0\n}"))

# The two fixes from the testing lane's CRITICALs.
m("ROLE_TAKEN_SET_AFTER_WRITE", "TAKE_LANDS_THEN_ERRORS", lambda s: s.replace(
    '  role_taken=1\n  adb -s "$serial" shell cmd role add-role-holder --user "$user" "$role" "$pkg" >/dev/null',
    '  adb -s "$serial" shell cmd role add-role-holder --user "$user" "$role" "$pkg" >/dev/null\n  role_taken=1'))
m("RESTORE_SKIPPED_ON_FILTERED_RUN", "FILTER_PLAIN_CLASS", lambda s: s.replace(
    "restore_sms_role() {\n", 'restore_sms_role() {\n  test -z "$filter" || return 0\n', 1))

m("LIVENESS_ABSTAINS", "ROLE_READER_DIES_MID_RUN", lambda s: cut(
    s, 'role remove-role-holder --user "$user" "$role" "$pkg" >/dev/null 2>&1 ||\n'
       '        true\n      echo "  Dropped',
    '      echo "  Check by hand:'))

print(f"mutation test: {len(M)} guards, each reverted in turn\n")
results = [check(n, w, f) for n, w, f in M]
print(f"\nred: {sum(results)}/{len(results)}")
sys.exit(0 if all(results) else 1)
