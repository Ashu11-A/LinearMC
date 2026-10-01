#!/usr/bin/env bash
# Local preflight mirror of .github/workflows/build.yml.
# Catches staging bugs in seconds that would otherwise cost a 10-minute CI
# round-trip (the last two CI failures were both in this class).
#
# Usage: scripts/preflight.sh [--folia-dir DIR] [--compile] [--tests]
#   default FOLIA_DIR=/tmp/folia-linear/folia (never modified, except --compile/--tests run gradle there)
# Exit 0 = push-ready. Anything else = fix locally, do NOT push.
set -u

SUPPLEMENT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FOLIA_DIR=/tmp/folia-linear/folia
DO_COMPILE=0
DO_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --folia-dir) shift_arg=1 ;;
    --compile) DO_COMPILE=1 ;;
    --tests) DO_TESTS=1 ;;
    *)
      if [ "${shift_arg:-0}" = 1 ]; then FOLIA_DIR="$arg"; shift_arg=0; else
        echo "usage: $0 [--folia-dir DIR] [--compile] [--tests]"; exit 2
      fi ;;
  esac
done

pass=0; fail=0
ok()   { echo "PASS: $1"; pass=$((pass+1)); }
nope() { echo "FAIL: $1"; fail=$((fail+1)); }
skip() { echo "SKIP: $1"; }

VERSIONS=$(cd "$SUPPLEMENT_DIR/versions/servers" && ls -d */ 2>/dev/null | tr -d '/' | sort -V)
if [ -z "${VERSIONS:-}" ]; then
  nope "no version folders under versions/servers/"
  echo "---- preflight: $pass passed, $fail failed ----"
  exit 1
fi

# 0. Repo hygiene: no server-runtime or build junk reaches commits.
# A booted server writes these into its cwd; scratch servers belong under
# proof/ or /tmp, never repo root. Catches blanket `git add -A` mistakes
# in seconds (tracked files) instead of after push.
junk_found=0
for junk in server.jar horizon.yml libraries world logs cache nohup.out; do
  if [ -e "$SUPPLEMENT_DIR/$junk" ]; then
    # Ignored-but-present is fine (local runs); tracked is the failure.
    if git -C "$SUPPLEMENT_DIR" ls-files --error-unmatch "$junk" >/dev/null 2>&1; then
      nope "tracked runtime junk at repo root: $junk (git rm it)"
      junk_found=1
    fi
  fi
done
tracked_jars=$(git -C "$SUPPLEMENT_DIR" ls-files '*.jar' 2>/dev/null \
  | grep -v '^release/' | grep -v '/gradle/wrapper/' || true)
if [ -n "$tracked_jars" ]; then
  nope "tracked jars outside release/ (gradle wrappers excluded): $tracked_jars"
  junk_found=1
fi
[ "$junk_found" -eq 0 ] && ok "repo hygiene: no tracked runtime junk or stray jars"

# 1. Per-version inventory: every file CI references must exist.
# Version-scoped supplement lives under versions/servers/<branch>/ (pin: upstream.properties).
# LinearMC layout: server legs (versions/servers/<branch>/folia/) + shared common/.
legdir() { # $1 = version -> prints leg dir (per-server first, legacy fallback)
  if [ -d "$SUPPLEMENT_DIR/versions/servers/$1/folia/patches" ]; then
    echo "versions/servers/$1/folia"
  else
    echo "versions/servers/$1"
  fi
}
for v in $VERSIONS; do
  VDIR="$SUPPLEMENT_DIR/versions/servers/$v"
  # Legs present in this line (a line may carry folia, canvas, both, or neither).
  FOLIA_LEG=0; CANVAS_LEG=0; COMMON=0
  ls "$VDIR"/folia/patches/*.patch >/dev/null 2>&1 && FOLIA_LEG=1
  ls "$VDIR"/canvasmc/overlay/*/*.patch >/dev/null 2>&1 && CANVAS_LEG=1
  ls "$VDIR"/common/*.patch >/dev/null 2>&1 && COMMON=1
  if [ "$FOLIA_LEG" = 0 ] && [ "$CANVAS_LEG" = 0 ] && [ "$COMMON" = 0 ]; then
    if [ ! -f "$VDIR/upstream.properties" ]; then
      skip "inventory $v (scaffold-only: no upstream pin)"
    else
      skip "inventory $v (scaffold-only: pin but no leg content)"
    fi
    continue
  fi
  if [ "$FOLIA_LEG" = 1 ]; then
  LEG=$(legdir "$v")
  for f in versions/servers/$v/upstream.properties "$LEG/build-hunk.py" \
           "$LEG/patches/minecraft-*.patch" "$LEG/patches/paper-*.patch" \
           "$LEG/tests/LinearRegionFileRoundTripTest.java" \
           "$LEG/tests/LinearNmsTestSuite.java" "$LEG/tests/LinearTimingInstrumentationTest.java" \
           "$LEG/tests/LinearStatsCommandTest.java" "$LEG/tests/LinearGlobalConfigTest.java" \
           "$LEG/tests/LinearFlushUnlockTest.java" "$LEG/tests/LinearEvictionUnlockTest.java" \
           "$LEG/tests/LinearMeasurementCountersTest.java"; do
    # shellcheck disable=SC2086
    found=$(ls "$SUPPLEMENT_DIR"/$f 2>/dev/null | wc -l)
    [ "$found" -ge 1 ] && ok "inventory $f" || nope "inventory $f (missing)"
  done
  else
    skip "inventory $v/folia (no folia leg in this line)"
  fi
  if [ "$COMMON" = 1 ]; then
    # shellcheck disable=SC2086
    found=$(ls "$VDIR"/common/minecraft-*.patch 2>/dev/null | wc -l)
    [ "$found" -ge 1 ] && ok "inventory versions/servers/$v/common/minecraft-*.patch" || nope "inventory versions/servers/$v/common/minecraft-*.patch (missing)"
  else
    skip "inventory $v/common (no shared patches in this line)"
  fi
  if [ "$CANVAS_LEG" = 1 ]; then
  for f in versions/servers/$v/upstream.canvas.properties \
           "versions/servers/$v/canvasmc/overlay/nms/*.patch" \
           "versions/servers/$v/canvasmc/overlay/paper/*.patch" \
           "versions/servers/$v/canvasmc/overlay/config/*.patch" \
           versions/servers/$v/canvasmc/OVERLAY.md \
           versions/servers/$v/canvasmc/paper-server/src/main/java/io/papermc/paper/command/CommandLinear.java \
           versions/servers/$v/canvasmc/paper-server/src/main/java/io/papermc/paper/linear/LinearStartupConversion.java \
           "versions/servers/$v/canvasmc/tests/net/linear/*.java"; do
    # shellcheck disable=SC2086
    found=$(ls "$SUPPLEMENT_DIR"/$f 2>/dev/null | wc -l)
    [ "$found" -ge 1 ] && ok "inventory $f" || nope "inventory $f (missing)"
  done
  else
    skip "inventory $v/canvasmc (no canvas leg in this line)"
  fi
  # 1b. upstream pin completeness: CI and build.sh read these keys.
  if [ -f "$VDIR/upstream.properties" ]; then
  for key in FOLIA_REPO FOLIA_BRANCH FOLIA_REF MC_VERSION; do
    if grep -qE "^${key}=.+" "$VDIR/upstream.properties" 2>/dev/null; then
      ok "props $v $key"
    else
      nope "props $v $key (missing or empty)"
    fi
  done
  else
    skip "props $v (no folia pin in this line)"
  fi
  if [ -f "$VDIR/upstream.canvas.properties" ]; then
  for key in CANVAS_REPO CANVAS_BRANCH CANVAS_REF MC_VERSION; do
    if grep -qE "^${key}=.+" "$VDIR/upstream.canvas.properties" 2>/dev/null; then
      ok "props $v $key"
    else
      nope "props $v $key (missing or empty)"
    fi
  done
  else
    skip "props $v canvas (no canvas pin in this line)"
  fi
done
[ -f "$FOLIA_DIR/folia-server/build.gradle.kts.patch" ] \
  && ok "fork base file present" || nope "fork base file missing ($FOLIA_DIR)"

# 2. Hunk determinism per version: pristine base + script must reproduce the
# working file byte-identically. Only runs for the version FOLIA_DIR is pinned
# at; other versions SKIP (point --folia-dir at their ref for the deep check).
if [ -d "$FOLIA_DIR/.git" ]; then
  HEAD=$(git -C "$FOLIA_DIR" rev-parse HEAD)
  for v in $VERSIONS; do
    WANT=$(grep '^FOLIA_REF=' "$SUPPLEMENT_DIR/versions/servers/$v/upstream.properties" 2>/dev/null | cut -d= -f2-)
    if [ "$HEAD" != "${WANT:-}" ]; then
      skip "$v hunk check (FOLIA_DIR at $HEAD, want ${WANT:-unknown}; use --folia-dir)"
      continue
    fi
    TMPD=$(mktemp -d)
    LEG2=$(legdir "$v")
    # The determinism gate compares a fresh splice against the fork working
    # tree — meaningful only once the fork is STAGED (stage action splices
    # the same hunks in). A pristine clone can never match; skip it here
    # (the unit job stages first, so the deep check runs there).
    if ! grep -q 'zstd-jni' "$FOLIA_DIR/folia-server/build.gradle.kts.patch" 2>/dev/null; then
      skip "$v hunk check (fork not staged; deep check runs in unit job)"
      rm -rf "$TMPD"
      continue
    fi
    git -C "$FOLIA_DIR" show HEAD:folia-server/build.gradle.kts.patch > "$TMPD/base.patch" 2>/dev/null \
      && python3 "$SUPPLEMENT_DIR/$LEG2/build-hunk.py" "$TMPD/base.patch" >/dev/null \
      && (diff -q "$TMPD/base.patch" "$FOLIA_DIR/folia-server/build.gradle.kts.patch" >/dev/null \
          && ok "$v hunk reproduces working tree byte-identically" \
          || nope "$v hunk output differs from working tree (script or base drifted)") \
      || nope "$v hunk script failed on pristine base"
    rm -rf "$TMPD"
  done
else
  nope "not a git checkout: $FOLIA_DIR"
fi

# 3. Patch files are non-empty unified diffs, in every version folder
# (server legs + shared common/; legacy flat patches/ where legs are mid-migration).
patch_dirs() { # $1 = version -> prints dirs holding *.patch, one per line
  for d in "$SUPPLEMENT_DIR/versions/servers/$1/folia/patches" \
           "$SUPPLEMENT_DIR/versions/servers/$1/common" \
           "$SUPPLEMENT_DIR/versions/servers/$1/patches" \
           "$SUPPLEMENT_DIR/versions/servers/$1/canvasmc/overlay/nms" \
           "$SUPPLEMENT_DIR/versions/servers/$1/canvasmc/overlay/paper" \
           "$SUPPLEMENT_DIR/versions/servers/$1/canvasmc/overlay/config"; do
    if ls "$d"/*.patch >/dev/null 2>&1; then echo "$d"; fi
  done
}
for v in $VERSIONS; do
  VDIR2="$SUPPLEMENT_DIR/versions/servers/$v"
  if ! ls "$VDIR2"/folia/patches/*.patch >/dev/null 2>&1 \
    && ! ls "$VDIR2"/common/*.patch >/dev/null 2>&1 \
    && ! ls "$VDIR2"/canvasmc/overlay/*/*.patch >/dev/null 2>&1; then
    skip "$v patch set (scaffold-only: no patches yet)"
    continue
  fi
  found_any=0
  for d in $(patch_dirs "$v"); do
    for p in "$d"/*.patch; do
      found_any=1
      grep -q '^diff --git' "$p" && grep -q '^@@' "$p" \
        && ok "patch parses: $v/$(basename "$p")" \
        || nope "patch malformed: $v/$(basename "$p")"
    done
  done
  [ "$found_any" = 1 ] || nope "$v patch set empty"
done

# 4. Inherited-bug grep gates, per version (see docs/architecture.md
# "Inherited bugs that were fixed, not ported"). Scans added patch lines,
# skipping comment-only lines, *Test.java demonstration fixtures (which pin
# the buggy behaviour on purpose), and lines marked BUGGY / // BUG.
bug_gate() { # $1 = version dir, $2 = fixed-string pattern
  awk -v pat="$2" '
    /^\+\+\+ b\// { file=$0; next }
    /^\+/ {
      if (substr($0, 1, 3) == "+++") next
      line = substr($0, 2)
      if (file ~ /Test\.java$/) next
      if (line ~ /^[[:space:]]*(\*|\/\/)/) next
      if (line ~ /[Bb][Uu][Gg][Gg][Yy]/) next
      if (index(line, pat)) print file " :: " line
    }' "$1"/folia/patches/*.patch "$1"/common/*.patch "$1"/patches/*.patch \
       "$1"/canvasmc/overlay/nms/*.patch "$1"/canvasmc/overlay/paper/*.patch \
       "$1"/canvasmc/overlay/config/*.patch 2>/dev/null
}
for v in $VERSIONS; do
  if [ -z "$(patch_dirs "$v")" ]; then
    skip "$v bug gates (no patches)"
    continue
  fi
  for spec in '|| !endsWith#extension predicate always true' \
              'format.equals(#enum-vs-String guard always true' \
              '(linear | mca)#spaced upgrader alternation never matches'; do
    pat="${spec%%#*}"; label="${spec#*#}"
    hits=$(bug_gate "$SUPPLEMENT_DIR/versions/servers/$v" "$pat")
    if [ -z "$hits" ]; then
      ok "$v inherited bug absent: $pat"
    else
      nope "$v inherited bug reappeared ($label): $hits"
    fi
  done
done

# 5. Workflow self-checks (same greps as CI preflight job).
python3 -c "import yaml; yaml.safe_load(open('$SUPPLEMENT_DIR/.github/workflows/build.yml'))" 2>/dev/null \
  && ok "workflow YAML parses" || echo "SKIP: workflow YAML parse (no pyyaml)"
if grep -rn 'folia-paperclip-26\.1\.2-\*' "$SUPPLEMENT_DIR/.github/workflows/build.yml" >/dev/null; then
  nope "over-specific jar glob present (use folia-paperclip-*.jar)"
else
  ok "jar globs generic"
fi
if grep -rnE 'gh release (upload|create|edit|delete)' "$SUPPLEMENT_DIR/.github/workflows/build.yml" | grep -v '\-\-repo' >/dev/null; then
  nope "gh invocation without --repo"
else
  ok "gh invocations carry --repo"
fi

# 6. Opt-in slow gates (need fork gradle env + network).
if [ "$DO_COMPILE" = 1 ]; then
  (cd "$FOLIA_DIR" && ./gradlew :folia-server:compileJava --stacktrace) \
    && ok "fork compileJava" || nope "fork compileJava"
fi
if [ "$DO_TESTS" = 1 ]; then
  (cd "$FOLIA_DIR" && ./gradlew :folia-server:test --tests "net.linear.LinearNmsTestSuite" --stacktrace) \
    && ok "Linear suites green" || nope "Linear suites"
fi

echo "---- preflight: $pass passed, $fail failed ----"
[ "$fail" -eq 0 ]
