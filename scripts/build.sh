#!/usr/bin/env bash
# Build the paperclip jar for one or every versions/servers/* line.
# Mirrors the `build` job in .github/workflows/build.yml (staging commands,
# generic jar glob, git identity for `git am`), so a local green run predicts
# CI. Reads the pin from each version's upstream.properties.
#
# Usage: scripts/build.sh [--mc <ver>|all] [--folia-dir DIR] [--dry-run]
#   --mc        version folder under versions/servers/ (e.g. 26.1.x). Default: all.
#   --folia-dir existing Folia checkout to build in. Only valid with a single
#               --mc; otherwise a scratch dir /tmp/folia-linear/build-<ver>
#               is used per version. The checkout is left on FOLIA_REF.
#   --dry-run   print the plan (clone, stage, gradle) without mutating anything.
set -u

SUPPLEMENT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MC=all
FOLIA_DIR=""
DRY_RUN=0

usage() {
  echo "usage: $0 [--mc <ver>|all] [--folia-dir DIR] [--dry-run]"
  echo "  builds versions/servers/<ver>/ (default: every folder in versions/servers/)"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --mc)       MC="${2:?--mc needs a value}"; shift 2 ;;
    --folia-dir) FOLIA_DIR="${2:?--folia-dir needs a value}"; shift 2 ;;
    --dry-run)  DRY_RUN=1; shift ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "unknown arg: $1"; usage; exit 2 ;;
  esac
done

if [ -n "$FOLIA_DIR" ] && [ "$MC" = all ]; then
  echo "ABORT: --folia-dir only makes sense with a single --mc <ver>" >&2
  exit 2
fi

if [ "$MC" = all ]; then
  VERSIONS=$(cd "$SUPPLEMENT_DIR/versions/servers" && ls -d */ | tr -d '/' | grep -v '^removed$' | sort -V)
else
  VERSIONS="$MC"
fi

fail=0
for v in $VERSIONS; do
  VDIR="$SUPPLEMENT_DIR/versions/servers/$v"
  PROPS="$VDIR/upstream.properties"
  if [ ! -f "$PROPS" ]; then
    # Canvas-only lines have upstream.canvas.properties instead; their
    # paperclip build is owned by .github/workflows/build.yml (needs the
    # post-apply overlay staging, which this Folia-oriented script lacks).
    echo "SKIP: $v has no Folia pin (Canvas-only line)"
    continue
  fi
  # shellcheck disable=SC1090
  FOLIA_REPO=$(grep '^FOLIA_REPO=' "$PROPS" | cut -d= -f2-)
  FOLIA_BRANCH=$(grep '^FOLIA_BRANCH=' "$PROPS" | cut -d= -f2-)
  FOLIA_REF=$(grep '^FOLIA_REF=' "$PROPS" | cut -d= -f2-)
  MC_VERSION=$(grep '^MC_VERSION=' "$PROPS" | cut -d= -f2-)
  if [ -z "${FOLIA_REPO:-}" ] || [ -z "${FOLIA_BRANCH:-}" ] || [ -z "${FOLIA_REF:-}" ] || [ -z "${MC_VERSION:-}" ]; then
    echo "FAIL: $v/upstream.properties is missing a key (need all four)"
    fail=$((fail+1)); continue
  fi

  if [ -n "$FOLIA_DIR" ]; then
    WORK="$FOLIA_DIR"
  else
    WORK="$(cd "$(dirname "$0")/.." && pwd)/proof/build-$v"
  fi

  echo "=== $v: $FOLIA_BRANCH @ $FOLIA_REF (mc $MC_VERSION) in $WORK ==="
  if [ "$DRY_RUN" = 1 ]; then
    echo "DRY-RUN: scripts/stage.sh folia $WORK --line $v   # clone/reset pin, patches, core, tests, applyAllPatches"
    echo "DRY-RUN: (cd $WORK && ./gradlew build && ./gradlew folia-server:createPaperclipJar)"
    continue
  fi
  # Same staging as CI and local workspaces (scripts/stage.sh).
  bash "$(dirname "$0")/stage.sh" folia "$WORK" --line "$v" \
    || { echo "FAIL: $v staging/applyAllPatches"; fail=$((fail+1)); continue; }
  (cd "$WORK" && ./gradlew build --stacktrace) \
    || { echo "FAIL: $v build"; fail=$((fail+1)); continue; }
  (cd "$WORK" && ./gradlew folia-server:createPaperclipJar --stacktrace) \
    || { echo "FAIL: $v createPaperclipJar"; fail=$((fail+1)); continue; }
  # Generic glob on purpose: the jar suffix has changed before (preflight gates this too).
  JAR=$(ls "$WORK"/folia-server/build/libs/folia-paperclip-*.jar 2>/dev/null | head -1)
  if [ -n "${JAR:-}" ]; then
    echo "OK: $v -> $JAR"
  else
    echo "FAIL: $v paperclip jar missing"; fail=$((fail+1))
  fi
done

if [ "$DRY_RUN" = 1 ]; then
  echo "---- build.sh --dry-run: plan printed, tree untouched ----"
  exit 0
fi
echo "---- build.sh: $fail version(s) failed ----"
[ "$fail" -eq 0 ]
