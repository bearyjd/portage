#!/usr/bin/env bash
#
# Regression test: every third-party GitHub Action in .github/workflows must be pinned to a full
# 40-hex commit SHA (mutable tags like @v4 can be re-pointed by the action's owner, a supply-chain
# risk). Local actions (./path) are exempt. Format: `uses: owner/repo@<sha> # vX`.
set -euo pipefail
cd "$(dirname "$0")/.."

bad=$(grep -rnE '^\s*-?\s*uses:' .github \
  | grep -vE 'uses:\s*\./' \
  | grep -vE 'uses:\s*[^[:space:]@]+@[0-9a-f]{40}(\s|$)' || true)

if [ -n "$bad" ]; then
  echo "FAIL: actions not pinned to a commit SHA:" >&2
  echo "$bad" >&2
  exit 1
fi
echo "OK: all actions pinned to commit SHAs"
