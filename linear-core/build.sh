#!/usr/bin/env bash
# Standalone proof for linear-core (multi-module tree).
# 1. NMS-grep gate: every .java under linear-core/*/src must not reference
#    net.minecraft / io.papermc / ca.spottedleaf. No exceptions: the former
#    factory seam (format/.../AbstractRegionFileFactory) is NMS-clean by
#    design (anvil handles cross as Object, legs cast back).
# 2. javac: compiles all NMS-free module sources in one invocation (javac
#    resolves order itself; logical dependency order is
#    format -> codec+config -> flush+convert -> command) against
#    lz4 + zstd-jni + slf4j + logging.
# 3. JUnit: compiles all tests under linear-core/*/tests and runs every
#    *Test class found (discovered via find, mapped to FQN by package decl).
#
# Needs lz4-java + zstd-jni + slf4j-api + mojang logging jars. Pass
# CORE_LIBS=/path/to/dir, or they are resolved from Maven Central on first run
# (cached under /tmp/opencode/corelibs).
# Usage: linear-core/build.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"

fail=0
echo "--- NMS-grep gate (must be empty) ---"
SRC_FILES="$(find "$HERE" -path "$HERE/*/src/*.java" | sort)"
if [ -z "$SRC_FILES" ]; then
  echo "FAIL: no module sources found under linear-core/*/src"; fail=1
else
  # shellcheck disable=SC2086
  if grep -l 'net\.minecraft\|io\.papermc\|ca\.spottedleaf' $SRC_FILES; then
    echo "FAIL: NMS reference in module sources"; fail=1
  else
    echo "OK: module sources are NMS-free (strict gate, no exceptions)"
  fi
fi

LIBS="${CORE_LIBS:-/tmp/opencode/corelibs}"
mkdir -p "$LIBS"
# Dependency resolution order per jar: $CORE_LIBS (sticky cache), the local
# Gradle module cache (populated by any fork build), then Maven Central.
# This keeps the gate hermetic on CI runners with a warm Gradle home while
# still bootstrapping on a cold box with network.
resolve_jar() { # $1 = dest name, $2.. = candidate paths/URLs (URLs start with https://)
  local dest="$LIBS/$1"; shift
  [ -f "$dest" ] && return 0
  local cand
  for cand in "$@"; do
    case "$cand" in
      https://*)
        curl -sL --max-time 90 -o "$dest" "$cand" && [ -s "$dest" ] && return 0
        rm -f "$dest"
        ;;
      *)
        if [ -f "$cand" ]; then cp "$cand" "$dest" && return 0; fi
        ;;
    esac
  done
  echo "FAIL: could not resolve $1" >&2
  return 1
}
GRADLE_MOD="$HOME/.gradle/caches/modules-2/files-2.1"
resolve_jar lz4-java.jar \
  "$GRADLE_MOD/org.lz4/lz4-java/1.8.0/"*/lz4-java-1.8.0.jar \
  "https://repo1.maven.org/maven2/org/lz4/lz4-java/1.8.0/lz4-java-1.8.0.jar" || fail=1
resolve_jar zstd-jni.jar \
  "https://repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.6-8/zstd-jni-1.5.6-8.jar" || fail=1
resolve_jar junit.jar \
  "https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar" || fail=1
resolve_jar hamcrest.jar \
  "https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" || fail=1
resolve_jar slf4j.jar \
  "$GRADLE_MOD/org.slf4j/slf4j-api/2.0.17/"*/slf4j-api-2.0.17.jar \
  "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar" || fail=1
resolve_jar mojang-logging.jar \
  "$GRADLE_MOD/com.mojang/logging/1.6.11/"*/logging-1.6.11.jar \
  "https://repo1.maven.org/maven2/com/mojang/logging/1.6.11/logging-1.6.11.jar" || fail=1
CP_DEPS="$LIBS/lz4-java.jar:$LIBS/zstd-jni.jar:$LIBS/slf4j.jar:$LIBS/mojang-logging.jar"
echo "--- javac NMS-free module sources (single invocation) ---"
OUT="$(mktemp -d /tmp/linearmc-core-classes.XXXXXX)"
if [ -n "$SRC_FILES" ]; then
  # shellcheck disable=SC2086
  javac -d "$OUT" -cp "$CP_DEPS" $SRC_FILES || fail=1
  echo "compiled classes: $(find "$OUT" -name '*.class' | wc -l)"
else
  echo "SKIP: no module sources to compile"
fi
echo "--- core unit tests (JUnit) ---"
TEST_FILES="$(find "$HERE" -path "$HERE/*/tests/*Test.java" | sort)"
if [ -n "$TEST_FILES" ]; then
  # shellcheck disable=SC2086
  javac -d "$OUT" -cp "$OUT:$CP_DEPS:$LIBS/junit.jar:$LIBS/hamcrest.jar" \
    $TEST_FILES || fail=1
  TEST_CLASSES=""
  for t in $TEST_FILES; do
    pkg="$(sed -n 's/^package[[:space:]]\+\([A-Za-z0-9_.]\+\).*/\1/p' "$t" | head -n 1)"
    cls="$(basename "$t" .java)"
    if [ -n "$pkg" ]; then
      TEST_CLASSES="$TEST_CLASSES $pkg.$cls"
    else
      TEST_CLASSES="$TEST_CLASSES $cls"
    fi
  done
  echo "test classes:$TEST_CLASSES"
  # shellcheck disable=SC2086
  java -cp "$OUT:$CP_DEPS:$LIBS/junit.jar:$LIBS/hamcrest.jar" \
    org.junit.runner.JUnitCore $TEST_CLASSES || fail=1
else
  echo "SKIP: no tests"
fi
rm -rf "$OUT"
[ "$fail" -eq 0 ] && echo "linear-core BUILD OK" || { echo "linear-core BUILD FAIL"; exit 1; }
