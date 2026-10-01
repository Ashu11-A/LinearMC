#!/usr/bin/env bash
# Guard: net/linear/* PRODUCTION sources are owned by linear-core/ (the single
# codebase). Leg TESTS may live under a net/linear package path (src/test),
# so the guard exempts src/test hunks and fails on anything else.
# Fails loudly listing every offending
# '+++ ...net/linear/...' hunk in ANY leg patch, EXCEPT leg tests
# (src/test/... may use the net/linear package for package-visible access).
# (Retired replay direction: this script used to stage net/linear/* out of
# the patches into common/linear-core/src; core is now the source of truth,
# so replaying from patches would resurrect stale duplicates.)
#
# Usage: scripts/stage-core.sh [--mc <ver>]  (default: 26.1.x)
set -u
SUPPLEMENT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MC="${1:-}"
if [ "${1:-}" = "--mc" ]; then MC="${2:?--mc needs a value}"; fi
MC="${MC:-26.1.x}"

VD="$SUPPLEMENT_DIR/versions/servers/$MC"
OFFENDERS="$(grep -rn '^+++.*net/linear/' "$VD/folia/patches" "$VD/common" "$VD/canvasmc/patches" "$VD/canvasmc/overlay" 2>/dev/null | grep -v 'src/test/' || true)"
if [ -n "$OFFENDERS" ]; then
  echo "stage-core GUARD FAIL: net/linear/* hunks belong in linear-core/, not in patches:" >&2
  echo "$OFFENDERS" >&2
  exit 1
fi
echo "stage-core guard OK: no net/linear/* hunks in $VD/{folia/patches,common,canvasmc/{patches,overlay}}"
