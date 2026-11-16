#!/usr/bin/env bash
#
# Regression test: the privileged ADB dependency chain (libadb-android + spake2-android, ADR-003 /
# ADR-004 §2) must be pinned by SHA-256 in gradle/verification-metadata.xml, and must NOT be
# exempted via <trusted-artifacts>. Without this, a JitPack re-publish or tampered artifact of the
# same version would be accepted silently into the privileged APK.
set -uo pipefail
cd "$(dirname "$0")/.."

F=gradle/verification-metadata.xml
fail=0
check() { echo "FAIL: $1"; fail=1; }

[ -s "$F" ] || { check "$F missing or empty"; exit 1; }

grep -q '<verify-metadata>true</verify-metadata>' "$F" || check "metadata verification not enabled"

for art in \
  'libadb-android-3.1.1.aar' 'libadb-android-3.1.1.pom' 'libadb-android-3.1.1.module' \
  'spake2-android-2.2.1.aar' 'spake2-android-2.2.1.pom' 'spake2-android-2.2.1.module'; do
  # the artifact entry must contain a 64-hex sha256 child
  if ! awk -v a="$art" '
      index($0, "name=\"" a "\"") { f=1; next }
      f && /<sha256 value="[0-9a-f]{64}"/ { ok=1; exit }
      f && /<\/artifact>/ { exit }
      END { exit ok ? 0 : 1 }' "$F"; then
    check "no sha256 pinned for $art"
  fi
done

# The ADB group must never be trusted wholesale (a trust rule matching it disables the pin).
# Allowed: only a regex with a negative lookahead that EXCLUDES it; anything else naming it fails.
if grep -E '<trust ' "$F" | grep -i 'MuntashirAkon' | grep -qv '(?!com\\.github\\.MuntashirAkon)'; then
  check "MuntashirAkon group is trusted"
fi
# Any other trust rule must not be a catch-all that matches it.
if grep -E '<trust ' "$F" | grep -v 'MuntashirAkon' | grep -q .; then
  check "unexpected <trust> rule that may cover the MuntashirAkon group"
fi

[ "$fail" = 0 ] && echo "dependency-verification: OK"
exit "$fail"
