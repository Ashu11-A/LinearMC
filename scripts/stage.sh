#!/usr/bin/env bash
# Stage a fork leg exactly as CI does (the CI actions call this script).
#
# usage: scripts/stage.sh <folia|canvas> [workdir] [--line <dir>] [--no-apply] [--overlay-only] [--no-tests]
#   folia : clone/reset the pin, copy patches + core + tests, applyAllPatches
#           (--no-apply: stop before applyAllPatches; CI applies itself)
#   canvas: clone/reset the pin, applyAllPatches, then the overlay
#           (--overlay-only: the tree is already applied; overlay only)
#   --line : version folder under versions/servers (default 26.1.x folia, 26.3 canvas)
#   workdir default: proof/fork-<leg> (git-ignored); must not be named "folia".
# Editing a Folia patch: edit the applied tree (folia-server/src/minecraft/java
# or paper-server), commit --fixup into its feature commit, autosquash, run
# ./gradlew :folia-server:rebuildAllServerFeaturePatches, copy the patch back.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LEG="${1:?usage: scripts/stage.sh <folia|canvas> [workdir] [--line <dir>] [--no-apply] [--overlay-only] [--no-tests]}"
shift
WORK="$ROOT/proof/fork-$LEG"; LINE=""; APPLY=1; OVERLAY_ONLY=0; TESTS=1
while [ $# -gt 0 ]; do
  case "$1" in
    --line) LINE="${2:?--line needs a value}"; shift ;;
    --no-apply) APPLY=0 ;;
    --overlay-only) OVERLAY_ONLY=1 ;;
    --no-tests) TESTS=0 ;;
    -*) echo "unknown flag $1" >&2; exit 2 ;;
    *) WORK="$1" ;;
  esac
  shift
done
[ "$(basename "$WORK")" != folia ] || { echo "workdir must not be named 'folia' (paperweight nested-build clash)" >&2; exit 2; }
WORK="$(realpath -m "$WORK")"   # copy_tree cds; keep every path absolute
case "$LEG" in
  folia)  LINE="${LINE:-26.1.x}"; PROPS="$ROOT/versions/servers/$LINE/upstream.properties"; P=FOLIA ;;
  canvas) LINE="${LINE:-26.3}";   PROPS="$ROOT/versions/servers/$LINE/upstream.canvas.properties"; P=CANVAS ;;
  *) echo "leg must be folia or canvas" >&2; exit 2 ;;
esac
copy_tree() { # $1 source java root, $2 destination java root (relative paths kept)
  [ -d "$1" ] || return 0
  (cd "$1" && find . -name '*.java' -print0 | while IFS= read -r -d '' f; do
    mkdir -p "$2/$(dirname "$f")"; cp "$f" "$2/$f"
  done)
}
copy_core() { # $1 destination java root; stale core classes of an earlier run are removed first
  rm -rf "$1/net/linear"
  local m
  for m in "$ROOT"/linear-core/*/src/net/linear; do copy_tree "$m" "$1/net/linear"; done
}
apply_gradle() {
  git -C "$WORK" config user.email >/dev/null || git -C "$WORK" config user.email "stage@linearmc.local"
  git -C "$WORK" config user.name >/dev/null || git -C "$WORK" config user.name "linearmc-stage"
  (cd "$WORK" && ./gradlew applyAllPatches --console=plain -q)
}

if [ "$OVERLAY_ONLY" = 0 ]; then
  prop() { grep "^$1=" "$PROPS" | cut -d= -f2-; }
  REF="$(prop ${P}_REF)"
  [ ${#REF} -eq 40 ] || { echo "$PROPS ${P}_REF must be a 40-char sha" >&2; exit 1; }
  [ -d "$WORK/.git" ] || git clone --filter=blob:none --branch "$(prop ${P}_BRANCH)" --single-branch "$(prop ${P}_REPO)" "$WORK"
  git -C "$WORK" fetch -q origin "$REF" 2>/dev/null || true
  git -C "$WORK" checkout -q --detach "$REF"
  git -C "$WORK" reset -q --hard "$REF"   # an earlier run's staged edits must not survive
  echo "stage: $LEG @ $REF in $WORK"
fi

if [ "$LEG" = folia ]; then
  LD="$ROOT/versions/servers/$LINE/folia"
  git -C "$WORK" clean -fdq -- folia-server/minecraft-patches/features folia-server/paper-patches/features
  for kind in minecraft paper; do
    cp "$ROOT/versions/servers/$LINE/common/$kind"-*.patch "$WORK/folia-server/$kind-patches/features/" 2>/dev/null || true
    cp "$LD/patches/$kind"-*.patch "$WORK/folia-server/$kind-patches/features/"
  done
  python3 "$LD/build-hunk.py" "$WORK/folia-server/build.gradle.kts.patch"
  copy_core "$WORK/folia-server/src/main/java"
  rm -rf "$WORK/folia-server/src/test/java/net/linear"   # renamed/deleted tests must not linger
  if [ "$TESTS" = 1 ]; then
    mkdir -p "$WORK/folia-server/src/test/java/net/linear"
    cp "$LD/tests"/*.java "$WORK/folia-server/src/test/java/net/linear/"
  fi
  [ "$APPLY" = 0 ] || apply_gradle
  echo "stage: folia ready"
  exit 0
fi

# canvas: upstream applied first, then the overlay (hub + leg; a leg file shadows the hub file).
[ "$OVERLAY_ONLY" = 1 ] || { [ "$APPLY" = 1 ] || { echo "canvas needs the applied tree (use --overlay-only)" >&2; exit 2; }; apply_gradle; }
[ -d "$WORK/paper-server/src/main/java" ] && [ -d "$WORK/canvas-server/src/minecraft/java" ] \
  || { echo "$WORK is not an applied Canvas tree (run applyAllPatches first)" >&2; exit 1; }
HUB="$ROOT/versions/servers/common/canvas"
LG="$ROOT/versions/servers/$LINE/canvasmc"
[ -f "$LG/OVERLAY.md" ] || { echo "missing $LG/OVERLAY.md" >&2; exit 1; }
overlay_files() { # $1 family -> hub files not shadowed by the leg, then leg files
  local p
  for p in "$HUB/overlay/$1"/*.patch; do [ -e "$p" ] && [ ! -e "$LG/overlay/$1/$(basename "$p")" ] && echo "$p"; done
  for p in "$LG/overlay/$1"/*.patch; do [ -e "$p" ] && echo "$p"; done
  return 0
}
apply_family() { # $1 family, $2 git dir; --check the whole family first (never half-applied)
  local p
  while IFS= read -r p; do git -C "$2" apply --check -p1 "$p" || { echo "overlay --check failed: $p" >&2; exit 1; }; done < <(overlay_files "$1")
  while IFS= read -r p; do git -C "$2" apply -p1 --whitespace=nowarn "$p"; done < <(overlay_files "$1")
}
apply_family nms "$WORK/canvas-server/src/minecraft/java"
copy_core "$WORK/canvas-server/src/main/java"
copy_tree "$HUB/paper-server/src/main/java" "$WORK/paper-server/src/main/java"
copy_tree "$LG/paper-server/src/main/java" "$WORK/paper-server/src/main/java"
apply_family paper "$WORK/paper-server"
apply_family config "$WORK"
python3 - "$WORK/canvas-server/build.gradle.kts" <<'EOF'
import re, sys
p = sys.argv[1]
s = open(p).read()
zstd = '    implementation("com.github.luben:zstd-jni:1.5.6-8") // Linear - whole-region zstd (same coordinate as the Folia leg)\n'
openhft = '    implementation("net.openhft:zero-allocation-hashing:2026.0") // Linear - XXH64 (same coordinate as Folia/Horizon)\n'
if 'zstd-jni' not in s:
    m = re.search(r'    implementation\("io\.canvasmc\.httpclient:httpclient:[^"]+"\)\n', s)
    assert m, 'dependency anchor moved; update stage wiring'
    s = s.replace(m.group(0), m.group(0) + zstd + openhft)
elif 'zero-allocation-hashing' not in s:
    m = re.search(r'    implementation\("com\.github\.luben:zstd-jni:[^"]+"\)[^\n]*\n', s)
    assert m, 'zstd line moved; update stage wiring'
    s = s[:m.end()] + openhft + s[m.end():]
open(p, 'w').write(s)
EOF
rm -rf "$WORK/canvas-server/src/test/java/net/linear"
if [ "$TESTS" = 1 ]; then
  copy_tree "$HUB/tests" "$WORK/canvas-server/src/test/java"
  copy_tree "$LG/tests" "$WORK/canvas-server/src/test/java"
fi
echo "stage: canvas ready"
