#!/usr/bin/env bash
#
# Regression test for the privilege-boundary CI gates in .github/workflows/build.yml.
#
# WHY THIS EXISTS
# ---------------
# The "Assert least-privilege boundaries" step is the ONLY enforcement of portage's privilege
# boundary: the sender must never declare WRITE_SECURE_SETTINGS or carry :adb-bridge, and the play
# flavor must be bridge-free (a live Google-Play-policy invariant). On 2026-07-31 that step was
# found to contain TWO independent fail-open bugs (PR #135) — it could report SUCCESS while the
# sender manifest genuinely leaked WRITE_SECURE_SETTINGS. Both had the same shape:
#
#   a FORBID check that concludes "clean" from a non-match fails OPEN whenever an ERROR status is
#   indistinguishable from "not found".
#
#   1. `perl … | grep -q` re-spawned per token: a dead perl produced no output -> "token absent".
#   2. The verdict `grep` itself: 0 = found, 1 = not found, >=2 = ERROR, 128+n = signal-killed.
#      Every non-zero read as "not found".
#
# Neither was visible to CI, because a gate that wrongly passes looks exactly like a gate that
# rightly passes. This test makes that class of regression loud.
#
# HOW IT WORKS
# ------------
# It EXTRACTS the actual step bodies from build.yml and runs them against synthetic build-output
# trees. It deliberately does NOT re-implement the gate logic: a copy would drift from the real
# workflow and prove nothing. If the extraction fails, the test fails — it never falls back to a
# stub.
#
# Every scenario asserts a DIRECTION (must pass / must fail), so a gate that is merely broken-shut
# is caught too, not only one that is broken-open.
#
# THIS TEST WAS ITSELF MUTATION-TESTED (do the same if you change it)
# -------------------------------------------------------------------
# A green self-test proves nothing until you have watched it go red. Splicing the ACTUAL pre-#135
# step body (from `git show 3503282:.github/workflows/build.yml`) over the current one produces
# 8 failures, including the two that matter:
#
#     REAL sender leak + perl dies at #1     expected fail, got pass
#     REAL sender leak + grep errors at #2   expected fail, got pass
#
# Degrading `contains()` to a bare `grep -qE` (restoring only the grep half) produces 2 failures.
#
# KNOWN AND INTENDED: removing ONLY the `|| die` on the perl invocation inside normalize() does NOT
# trip this test, because the following `test -s "$out"` catches the empty output anyway. That is
# defense-in-depth working, not a hole — this test asserts BEHAVIOUR (does the gate fail closed),
# not implementation, so a change that preserves behaviour is correctly ignored. Do not "fix" that
# by asserting on the gate's internals; you would just make the test brittle to refactors.
#
# Usage:  scripts/test-boundary-gate.sh
# Exit:   0 = all scenarios behaved correctly; 1 = at least one did not (or a tool is missing).

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORKFLOW="$REPO_ROOT/.github/workflows/build.yml"

PASS=0
FAIL=0

# ── Preflight ────────────────────────────────────────────────────────────────────────────────
# Every tool is REQUIRED. There is deliberately no skip path: a self-test that silently skips the
# section it was written to cover is the exact failure mode this file exists to prevent.
missing=""
for t in python3 perl grep zip unzip gcc readelf find; do
  command -v "$t" >/dev/null 2>&1 || missing="$missing $t"
done
ZIPALIGN=""
if [ -n "${ANDROID_HOME:-}" ]; then
  ZIPALIGN=$(ls -d "$ANDROID_HOME"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -1 || true)
fi
[ -n "$ZIPALIGN" ] || missing="$missing zipalign(ANDROID_HOME/build-tools)"
if [ -n "$missing" ]; then
  echo "::error::boundary-gate self-test cannot run — missing required tools:$missing"
  echo "         Refusing to report success on an unrun test."
  exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# ── Extract a step's `run:` body from the workflow ───────────────────────────────────────────
# Pure-Python, no PyYAML: the runner image is not guaranteed to have it, and a missing import
# would turn into a skipped test.
extract_step() { # <name-substring> <outfile>
  python3 - "$WORKFLOW" "$1" "$2" <<'PY'
import re, sys
path, needle, out = sys.argv[1], sys.argv[2], sys.argv[3]
lines = open(path).read().split("\n")
start = None
for i, l in enumerate(lines):
    if l.strip().startswith("- name:") and needle in l:
        start = i
        break
if start is None:
    sys.exit(f"step not found: {needle}")
run_i = None
for j in range(start + 1, len(lines)):
    if lines[j].strip().startswith("- name:"):
        break
    if re.match(r"^\s*run:\s*\|", lines[j]):
        run_i = j
        break
if run_i is None:
    sys.exit(f"no 'run: |' block under step: {needle}")
indent = len(lines[run_i]) - len(lines[run_i].lstrip())
body = []
for k in range(run_i + 1, len(lines)):
    l = lines[k]
    if l.strip() == "":
        body.append("")
        continue
    cur = len(l) - len(l.lstrip())
    if cur <= indent:
        break
    body.append(l)
if not body:
    sys.exit(f"empty run block for step: {needle}")
strip = min(len(l) - len(l.lstrip()) for l in body if l.strip())
open(out, "w").write("\n".join(l[strip:] if l.strip() else "" for l in body) + "\n")
PY
}

BOUNDARY="$WORK/boundary.sh"
ALIGN="$WORK/align.sh"
extract_step "Assert least-privilege boundaries" "$BOUNDARY"
extract_step "16 KB page alignment"              "$ALIGN"
bash -n "$BOUNDARY" || { echo "::error::extracted boundary step is not valid bash"; exit 1; }
bash -n "$ALIGN"    || { echo "::error::extracted alignment step is not valid bash"; exit 1; }

# ── Reader-failure stubs ─────────────────────────────────────────────────────────────────────
# Simulate a reader dying mid-step (OOM-kill, transient failure) at a chosen invocation.
mkdir -p "$WORK/stub"
cat > "$WORK/stub/perl" <<'EOF'
#!/bin/sh
C="$STUBDIR/pcount"; n=$(cat "$C" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$C"
[ "$n" = "${PFAIL_ON:-0}" ] && exit 137
exec /usr/bin/perl "$@"
EOF
cat > "$WORK/stub/grep" <<'EOF'
#!/bin/sh
C="$STUBDIR/gcount"; n=$(cat "$C" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$C"
[ "$n" = "${GFAIL_ON:-0}" ] && exit 2
exec /usr/bin/grep "$@"
EOF
chmod +x "$WORK/stub/perl" "$WORK/stub/grep"

# ── Fixture: a synthetic build-output tree the gate can inspect ──────────────────────────────
RECV_MAIN='<uses-permission android:name="android.permission.READ_CONTACTS"/>
  <uses-permission android:name="android.permission.CAMERA"/>'
DEGOOGLE_EXTRA='<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"/>
  <uses-permission android:name="android.permission.READ_SMS"/>
  <uses-permission android:name="android.permission.SEND_SMS"/>
  <uses-permission android:name="android.permission.RECEIVE_SMS"/>
  <uses-permission android:name="android.permission.RECEIVE_MMS"/>
  <uses-permission android:name="android.permission.RECEIVE_WAP_PUSH"/>
  <uses-permission android:name="android.permission.WRITE_CALL_LOG"/>
  <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
  <receiver android:name="com.ventouxlabs.portage.recv.sms.SmsDeliverReceiver"/>'

mk_manifest() { # <root> <app> <variant> <inner>
  local d="$1/$2/build/intermediates/merged_manifest/$3"
  mkdir -p "$d"
  {
    echo '<?xml version="1.0" encoding="utf-8"?>'
    # Prose deliberately mentioning the forbidden tokens: the gate must strip comments before
    # grepping, so this doubles as the comment-stripping control.
    echo '<!-- This manifest discusses WRITE_SECURE_SETTINGS and the ADB bridge in prose only. -->'
    echo '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
    echo "  $4"
    echo '</manifest>'
  } > "$d/AndroidManifest.xml"
}

mk_apk() { # <root> <app> <flavor/buildtype> <with_bridge_libs:0|1> [lib_src]
  local d="$1/$2/build/outputs/apk/$3"; mkdir -p "$d"
  local t; t=$(mktemp -d "$WORK/apk.XXXXXX")
  mkdir -p "$t/lib/arm64-v8a"; echo dex > "$t/classes.dex"
  if [ "$4" = 1 ]; then
    cp "${5:-$WORK/libgood.so}" "$t/lib/arm64-v8a/libconscrypt_jni.so"
    cp "${5:-$WORK/libgood.so}" "$t/lib/arm64-v8a/libspake2.so"
  else
    cp "${5:-$WORK/libgood.so}" "$t/lib/arm64-v8a/libandroidx.graphics.path.so"
  fi
  ( cd "$t" && zip -q -X -0 "$d/app.apk" -r . )
  "$ZIPALIGN" -f -P 16 4 "$d/app.apk" "$d/aligned.apk" >/dev/null 2>&1 \
    && mv "$d/aligned.apk" "$d/app.apk"
  rm -rf "$t"
}

build_fixture() { # <root>
  local r="$1"; rm -rf "$r"; mkdir -p "$r"
  local v
  for v in degoogleDebug playDebug degoogleRelease playRelease; do
    mk_manifest "$r" app-send "$v" '<uses-permission android:name="android.permission.READ_CONTACTS"/>'
  done
  for v in playDebug playRelease;        do mk_manifest "$r" app-recv "$v" "$RECV_MAIN"; done
  for v in degoogleDebug degoogleRelease; do mk_manifest "$r" app-recv "$v" "$RECV_MAIN
  $DEGOOGLE_EXTRA"; done
  local p
  for p in degoogle/debug play/debug degoogle/release play/release; do mk_apk "$r" app-send "$p" 0; done
  for p in play/debug play/release;      do mk_apk "$r" app-recv "$p" 0; done
  for p in degoogle/debug degoogle/release; do mk_apk "$r" app-recv "$p" 1; done
}

# ── Scenario runner ──────────────────────────────────────────────────────────────────────────
# <label> <script> <expect: pass|fail> <mutator> [env assignments…]
scenario() {
  local label="$1" script="$2" expect="$3" mutator="$4"; shift 4
  local root="$WORK/tree"
  build_fixture "$root"
  ( cd "$root" && eval "$mutator" ) >/dev/null 2>&1 || true
  rm -f "$WORK/stub/pcount" "$WORK/stub/gcount"

  local out rc pathpfx="" e
  for e in "$@"; do case "$e" in PFAIL_ON=*|GFAIL_ON=*) pathpfx="$WORK/stub:";; esac; done

  set +e
  out=$(cd "$root" && env "$@" STUBDIR="$WORK/stub" ANDROID_HOME="${ANDROID_HOME}" \
        PATH="$pathpfx$PATH" bash "$script" 2>&1)
  rc=$?
  set -e

  local got; [ "$rc" -eq 0 ] && got=pass || got=fail
  if [ "$got" = "$expect" ]; then
    PASS=$((PASS + 1)); printf '  \033[32mok\033[0m   %-58s (%s)\n' "$label" "$got"
  else
    FAIL=$((FAIL + 1))
    printf '  \033[31mFAIL\033[0m %-58s expected %s, got %s\n' "$label" "$expect" "$got"
    printf '%s\n' "$out" | sed 's/^/         | /' | head -6
  fi
}

# Shared native libs for the alignment fixtures.
echo 'int portage_selftest(void){return 1;}' > "$WORK/t.c"
gcc -shared -fPIC -o "$WORK/libgood.so" -Wl,-z,max-page-size=16384 "$WORK/t.c"
gcc -shared -fPIC -o "$WORK/libbad.so"  -Wl,-z,max-page-size=4096  "$WORK/t.c"

SEND_M='app-send/build/intermediates/merged_manifest'
RECV_M='app-recv/build/intermediates/merged_manifest'
inject() { echo "sed -i 's|</manifest>|$1\\n</manifest>|' $2/AndroidManifest.xml"; }

echo
echo "── privilege-boundary gate ──────────────────────────────────────────────────────"
scenario "healthy tree passes" "$BOUNDARY" pass 'true'

echo "  detection (each must FAIL the build):"
scenario "sender manifest leaks WRITE_SECURE_SETTINGS" "$BOUNDARY" fail \
  "$(inject '<uses-permission android:name=\"android.permission.WRITE_SECURE_SETTINGS\"/>' "$SEND_M/degoogleDebug")"
scenario "sender manifest names the adbbridge package" "$BOUNDARY" fail \
  "$(inject '<provider android:name=\"com.ventouxlabs.portage.adbbridge.X\"/>' "$SEND_M/playRelease")"
scenario "play manifest leaks READ_SMS" "$BOUNDARY" fail \
  "$(inject '<uses-permission android:name=\"android.permission.READ_SMS\"/>' "$RECV_M/playDebug")"
scenario "play manifest declares a .sms. role component" "$BOUNDARY" fail \
  "$(inject '<activity android:name=\"com.ventouxlabs.portage.recv.sms.Compose\"/>' "$RECV_M/playRelease")"
scenario "degoogle manifest loses WRITE_CALL_LOG" "$BOUNDARY" fail \
  "sed -i '/WRITE_CALL_LOG/d' $RECV_M/degoogleRelease/AndroidManifest.xml"
scenario "degoogle manifest loses WRITE_SECURE_SETTINGS" "$BOUNDARY" fail \
  "sed -i '/WRITE_SECURE_SETTINGS/d' $RECV_M/degoogleDebug/AndroidManifest.xml"
scenario "sender APK gains bridge native libs" "$BOUNDARY" fail \
  "cd app-send/build/outputs/apk/play/debug && mkdir -p lib/arm64-v8a && cp $WORK/libgood.so lib/arm64-v8a/libspake2.so && zip -q -0 app.apk lib/arm64-v8a/libspake2.so"
scenario "degoogle APK loses bridge native libs" "$BOUNDARY" fail \
  'cd app-recv/build/outputs/apk/degoogle/debug && zip -qd app.apk "lib/arm64-v8a/libconscrypt_jni.so" "lib/arm64-v8a/libspake2.so"'
scenario "merged manifest is empty" "$BOUNDARY" fail \
  ": > $RECV_M/degoogleDebug/AndroidManifest.xml"
scenario "merged manifest is missing" "$BOUNDARY" fail \
  "rm -f $SEND_M/playDebug/AndroidManifest.xml"

echo "  fail-CLOSED on reader failure (the two shipped fail-opens — PR #135):"
for n in 1 2 4 6 8; do
  scenario "perl dies at call #$n (clean tree)" "$BOUNDARY" fail 'true' "PFAIL_ON=$n"
done
for n in 1 3 5 8; do
  scenario "grep errors at call #$n (clean tree)" "$BOUNDARY" fail 'true' "GFAIL_ON=$n"
done
# The load-bearing case: a REAL leak present AND the reader failing. Must never report success.
for n in 1 2 3 4; do
  scenario "REAL sender leak + perl dies at #$n" "$BOUNDARY" fail \
    "$(inject '<uses-permission android:name=\"android.permission.WRITE_SECURE_SETTINGS\"/>' "$SEND_M/degoogleDebug")" "PFAIL_ON=$n"
done
for n in 1 2 3 4 5 6; do
  scenario "REAL sender leak + grep errors at #$n" "$BOUNDARY" fail \
    "$(inject '<uses-permission android:name=\"android.permission.WRITE_SECURE_SETTINGS\"/>' "$SEND_M/degoogleDebug")" "GFAIL_ON=$n"
done
scenario "REAL play leak + grep errors at #5" "$BOUNDARY" fail \
  "$(inject '<uses-permission android:name=\"android.permission.READ_SMS\"/>' "$RECV_M/playDebug")" "GFAIL_ON=5"

echo
echo "── 16 KB native-library alignment gate ──────────────────────────────────────────"
scenario "healthy tree passes" "$ALIGN" pass 'true'
scenario "a 4 KB-aligned .so fails (ELF p_align)" "$ALIGN" fail \
  "cp $WORK/libbad.so app-recv/build/outputs/apk/degoogle/debug/x.so && cd app-recv/build/outputs/apk/degoogle/debug && zip -q -0 app.apk x.so && printf '' && mkdir -p lib/arm64-v8a && mv x.so lib/arm64-v8a/libbad.so && zip -qd app.apk x.so && zip -q -0 app.apk lib/arm64-v8a/libbad.so"
scenario "a COMPRESSED .so fails (mmap impossible)" "$ALIGN" fail \
  "cd app-recv/build/outputs/apk/degoogle/debug && mkdir -p lib/arm64-v8a && cp $WORK/libgood.so lib/arm64-v8a/libz.so && zip -q -9 app.apk lib/arm64-v8a/libz.so"

echo
echo "─────────────────────────────────────────────────────────────────────────────────"
printf 'boundary-gate self-test: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -ne 0 ]; then
  echo "::error::boundary-gate self-test FAILED — the CI privilege gate does not behave as specified"
  exit 1
fi
echo "All scenarios behaved as specified."
