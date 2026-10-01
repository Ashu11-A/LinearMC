#!/usr/bin/env bash
# Start a new MC server version line: copy the newest versions/servers/* folder,
# re-pin it, and run applyAllPatches immediately so rebase conflicts surface
# here in seconds instead of in a 10-minute CI round-trip.
# Scaffolds server lines only (versions/servers/<line>/ with folia + canvasmc
# legs); plugin versions under versions/plugins/ are NOT auto-created here.
#
# Usage: scripts/new-version.sh [--dry-run] [--folia-dir DIR] [--mc-version V] <new-dir> <new-branch> <new-ref>
#   Example: scripts/new-version.sh 26.2.x ver/26.2.x a1b2c3d4
#     creates versions/servers/26.2.x from the newest versions/servers/* line.
#   <new-dir>    folder to create under versions/servers/ (e.g. 26.2.x)
#   <new-branch> upstream Folia branch (e.g. ver/26.2.x)
#   <new-ref>    pinned upstream commit for the new line
#   --mc-version Minecraft version for upstream.properties. Default: <new-dir>
#                with trailing `.x` replaced by `.0` (e.g. 26.2.x -> 26.2.0);
#                confirm it, the script cannot know the real MC version.
#   --folia-dir  run applyAllPatches in this existing checkout instead of a
#                fresh temp clone (checkout is left on <new-ref>).
#   --dry-run    print the plan without creating, cloning or building anything.
#
# Next steps after this script (not done here): port each patch hunk to the
# new base, run scripts/preflight.sh, build with scripts/build.sh --mc <new-dir>.
set -u

SUPPLEMENT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DRY_RUN=0
FOLIA_DIR=""
MC_VERSION=""

usage() {
  echo "usage: $0 [--dry-run] [--folia-dir DIR] [--mc-version V] <new-dir> <new-branch> <new-ref>"
  echo "  example: $0 26.2.x ver/26.2.x a1b2c3d4  (creates versions/servers/26.2.x)"
  echo "  server lines only; versions/plugins/ are never auto-created here"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)    DRY_RUN=1; shift ;;
    --folia-dir)  FOLIA_DIR="${2:?--folia-dir needs a value}"; shift 2 ;;
    --mc-version) MC_VERSION="${2:?--mc-version needs a value}"; shift 2 ;;
    -h|--help)    usage; exit 0 ;;
    --*) echo "unknown flag: $1"; usage; exit 2 ;;
    *) break ;;
  esac
done

if [ $# -ne 3 ]; then
  usage; exit 2
fi
NEW_DIR="$1"; NEW_BRANCH="$2"; NEW_REF="$3"

case "$NEW_DIR" in
  */..*|*/*|.|..|"") echo "ABORT: bad <new-dir>: $NEW_DIR"; exit 2 ;;
esac
case "$NEW_DIR" in
  [0-9]*.[0-9]*.x) ;;
  *) echo "ABORT: <new-dir> should look like 26.2.x, got: $NEW_DIR"; exit 2 ;;
esac

# Copy source = newest line WITH leg content (scaffolds carry pins but no
# patches to copy; copying one would clone an empty scaffold).
SRC_VER=$(cd "$SUPPLEMENT_DIR/versions/servers" && for d in */; do
  [ -f "$d/upstream.properties" ] || continue
  ls "$d/folia/patches/"*.patch >/dev/null 2>&1 || ls "$d/common/"*.patch >/dev/null 2>&1 || continue
  echo "${d%/}"
done | sort -V | tail -1)
if [ -z "${SRC_VER:-}" ]; then
  echo "ABORT: no versions/servers/*/ folder to copy from"; exit 1
fi
SRC="$SUPPLEMENT_DIR/versions/servers/$SRC_VER"
DST="$SUPPLEMENT_DIR/versions/servers/$NEW_DIR"
if [ -e "$DST" ]; then
  echo "ABORT: $DST already exists"; exit 1
fi
if [ ! -f "$SRC/upstream.properties" ]; then
  echo "ABORT: $SRC/upstream.properties missing"; exit 1
fi

if [ -z "$MC_VERSION" ]; then
  MC_VERSION="${NEW_DIR%.x}.0"
  echo "NOTE: --mc-version not given, defaulting to $MC_VERSION; fix it if wrong."
fi
FOLIA_REPO=$(grep '^FOLIA_REPO=' "$SRC/upstream.properties" | cut -d= -f2-)
if [ -z "${FOLIA_REPO:-}" ]; then
  echo "ABORT: no FOLIA_REPO in $SRC/upstream.properties"; exit 1
fi

echo "Plan: copy versions/servers/$SRC_VER -> versions/servers/$NEW_DIR"
echo "      pin: $FOLIA_REPO $NEW_BRANCH @ $NEW_REF (mc $MC_VERSION)"
echo "      then stage + applyAllPatches so rebase conflicts surface now."
if [ "$DRY_RUN" = 1 ]; then
  echo "DRY-RUN: cp -r $SRC $DST"
  echo "DRY-RUN: write $DST/upstream.properties (FOLIA_REPO=$FOLIA_REPO FOLIA_BRANCH=$NEW_BRANCH FOLIA_REF=$NEW_REF MC_VERSION=$MC_VERSION)"
  echo "DRY-RUN: keep $DST/upstream.canvas.properties from $SRC_VER, then re-pin the Canvas leg by hand"
  if [ -n "$FOLIA_DIR" ]; then
    echo "DRY-RUN: stage $DST into $FOLIA_DIR, checkout $NEW_REF, ./gradlew applyAllPatches"
  else
    echo "DRY-RUN: clone --branch $NEW_BRANCH $FOLIA_REPO <tmpdir>, checkout $NEW_REF, stage $DST, ./gradlew applyAllPatches"
  fi
  echo "---- new-version.sh --dry-run: plan printed, tree untouched ----"
  exit 0
fi

cp -r "$SRC" "$DST" || exit 1
cat > "$DST/upstream.properties" <<EOF
# Single source of truth for the upstream pin for this version line.
# Copied from versions/servers/$SRC_VER on $(date -u +%F); re-pinned to $NEW_BRANCH.
# CI consumes this file; do not duplicate the pin elsewhere.
FOLIA_REPO=$FOLIA_REPO
FOLIA_BRANCH=$NEW_BRANCH
FOLIA_REF=$NEW_REF
MC_VERSION=$MC_VERSION
EOF
echo "wrote $DST/upstream.properties"
echo "NOTE: $DST/upstream.canvas.properties was copied from $SRC_VER; re-pin the Canvas leg by hand."

if [ -n "$FOLIA_DIR" ]; then
  WORK="$FOLIA_DIR"
  SCRATCH=0
else
  WORK=$(mktemp -d /tmp/folia-linear-newver-XXXXXX)
  git clone --branch "$NEW_BRANCH" --single-branch "$FOLIA_REPO" "$WORK" || exit 1
  SCRATCH=1
fi

git -C "$WORK" fetch origin "$NEW_BRANCH" \
  && git -C "$WORK" checkout "$NEW_REF" \
  || { echo "FAIL: checkout $NEW_REF in $WORK"; exit 1; }
LEG="$DST/folia"
[ -d "$LEG/patches" ] || LEG="$DST"
if ls "$DST"/common/*.patch >/dev/null 2>&1; then
  cp "$DST"/common/minecraft-*.patch "$WORK/folia-server/minecraft-patches/features/" 2>/dev/null || true
  cp "$DST"/common/paper-*.patch "$WORK/folia-server/paper-patches/features/" 2>/dev/null || true
fi
cp "$LEG"/patches/minecraft-*.patch "$WORK/folia-server/minecraft-patches/features/" \
  && cp "$LEG"/patches/paper-*.patch "$WORK/folia-server/paper-patches/features/" \
  && python3 "$LEG/build-hunk.py" "$WORK/folia-server/build.gradle.kts.patch" \
  && mkdir -p "$WORK/folia-server/src/test/java/net/linear" \
  && cp "$LEG"/tests/*.java "$WORK/folia-server/src/test/java/net/linear/" \
  || { echo "FAIL: staging $NEW_DIR (patch filenames changed?)"; exit 1; }
git -C "$WORK" config user.name "folia-linear-newver" >/dev/null
git -C "$WORK" config user.email "folia-linear-newver@users.noreply.github.com" >/dev/null

if (cd "$WORK" && ./gradlew applyAllPatches --stacktrace); then
  echo "OK: applyAllPatches green for versions/servers/$NEW_DIR @ $NEW_REF"
  if [ "$SCRATCH" = 1 ]; then
    rm -rf "$WORK"  # scratch clone served its purpose; repro via build.sh if needed
  fi
else
  echo "FAIL: applyAllPatches conflicts for versions/servers/$NEW_DIR @ $NEW_REF -- port the hunks, then re-run preflight"
  if [ "$SCRATCH" = 1 ]; then
    echo "NOTE: conflicted checkout kept at $WORK for inspection"
  fi
  exit 1
fi

echo "Next: port hunks to the new base, run scripts/preflight.sh, then scripts/build.sh --mc $NEW_DIR"
