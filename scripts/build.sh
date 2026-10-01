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
  VERSIONS=$(cd "$SUPPLEMENT_DIR/versions/servers" && ls -d */ | tr -d '/' | sort -V)
else
  VERSIONS="$MC"
fi

fail=0
for v in $VERSIONS; do
  VDIR="$SUPPLEMENT_DIR/versions/servers/$v"
  PROPS="$VDIR/upstream.properties"
  if [ ! -f "$PROPS" ]; then
    echo "FAIL: $v has no upstream.properties; run scripts/new-version.sh first"
    fail=$((fail+1)); continue
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
    WORK="/tmp/folia-linear/build-$v"
  fi

  echo "=== $v: $FOLIA_BRANCH @ $FOLIA_REF (mc $MC_VERSION) in $WORK ==="
  # LinearMC leg resolution (mirrors .github/actions/stage): per-server leg
  # first, legacy flat dirs as fallback; common/ patches stage alongside.
  LEGDIR="$VDIR/folia"
  [ -d "$LEGDIR/patches" ] || LEGDIR="$VDIR"
  if [ "$DRY_RUN" = 1 ]; then
    echo "DRY-RUN: git clone --branch $FOLIA_BRANCH --single-branch $FOLIA_REPO $WORK"
    echo "DRY-RUN: git -C $WORK fetch origin && git -C $WORK checkout $FOLIA_REF"
    echo "DRY-RUN: cp $VDIR/common/*.patch $WORK/folia-server/{minecraft,paper}-patches/features/ (when present)"
    echo "DRY-RUN: cp $LEGDIR/patches/minecraft-*.patch $WORK/folia-server/minecraft-patches/features/"
    echo "DRY-RUN: cp $LEGDIR/patches/paper-*.patch $WORK/folia-server/paper-patches/features/"
    echo "DRY-RUN: python3 $LEGDIR/build-hunk.py $WORK/folia-server/build.gradle.kts.patch"
    echo "DRY-RUN: mkdir -p $WORK/folia-server/src/test/java/net/linear && cp $LEGDIR/tests/*.java ..."
    echo "DRY-RUN: (cd $WORK && ./gradlew applyAllPatches && ./gradlew build && ./gradlew folia-server:createPaperclipJar)"
    echo "DRY-RUN: ls $WORK/folia-server/build/libs/folia-paperclip-*.jar"
    continue
  fi

  if [ ! -d "$WORK/.git" ]; then
    git clone --branch "$FOLIA_BRANCH" --single-branch "$FOLIA_REPO" "$WORK" \
      || { echo "FAIL: $v clone"; fail=$((fail+1)); continue; }
  fi
  git -C "$WORK" fetch origin "$FOLIA_BRANCH" \
    && git -C "$WORK" checkout "$FOLIA_REF" \
    || { echo "FAIL: $v checkout $FOLIA_REF"; fail=$((fail+1)); continue; }
  HEAD=$(git -C "$WORK" rev-parse HEAD)
  if [ "$HEAD" != "$FOLIA_REF" ]; then
    echo "WARN: $v branch moved ($HEAD); patches target $FOLIA_REF, apply step will prove compatibility"
  fi

  if ls "$VDIR"/common/*.patch >/dev/null 2>&1; then
    cp "$VDIR"/common/minecraft-*.patch "$WORK/folia-server/minecraft-patches/features/" 2>/dev/null || true
    cp "$VDIR"/common/paper-*.patch "$WORK/folia-server/paper-patches/features/" 2>/dev/null || true
  fi
  cp "$LEGDIR"/patches/minecraft-*.patch "$WORK/folia-server/minecraft-patches/features/" \
    && cp "$LEGDIR"/patches/paper-*.patch "$WORK/folia-server/paper-patches/features/" \
    && python3 "$LEGDIR/build-hunk.py" "$WORK/folia-server/build.gradle.kts.patch" \
    && mkdir -p "$WORK/folia-server/src/test/java/net/linear" \
    && cp "$LEGDIR"/tests/*.java "$WORK/folia-server/src/test/java/net/linear/" \
    || { echo "FAIL: $v staging"; fail=$((fail+1)); continue; }

  git -C "$WORK" config user.name "folia-linear-build" >/dev/null
  git -C "$WORK" config user.email "folia-linear-build@users.noreply.github.com" >/dev/null
  (cd "$WORK" && ./gradlew applyAllPatches --stacktrace) \
    || { echo "FAIL: $v applyAllPatches (rebase conflict?)"; fail=$((fail+1)); continue; }
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
