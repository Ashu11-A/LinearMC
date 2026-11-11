#!/usr/bin/env bash
# Full local validation of every leg, one Gradle build at a time (fits a
# ~16 GB workstation): core suites, Horizon tests on every dev bundle + jar,
# Folia fork (stage, compile, Linear suite, paperclip jar), Canvas fork (same),
# static gates. Results land in proof/validate/<step>.log; a summary prints at
# the end. Live server checks stay separate (scripts/migration-smoke.sh).
#
# usage: scripts/validate-all.sh [--skip-forks] [--skip-horizon] [--jars]
#   --jars  also build the fork paperclip jars (for live tests)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/proof/validate"
mkdir -p "$OUT"
SKIP_FORKS=0; SKIP_HORIZON=0; JARS=0
for a in "$@"; do
  case "$a" in
    --skip-forks) SKIP_FORKS=1 ;;
    --skip-horizon) SKIP_HORIZON=1 ;;
    --jars) JARS=1 ;;
    *) echo "unknown flag $a" >&2; exit 2 ;;
  esac
done
declare -a SUMMARY
step() { # $1 name, $2.. command
  local name="$1"; shift
  if "$@" > "$OUT/$name.log" 2>&1; then
    SUMMARY+=("PASS $name"); return 0
  fi
  SUMMARY+=("FAIL $name (see proof/validate/$name.log)"); return 1
}
junit_sum() { # $1 results dir -> "tests N fail M"
  python3 - "$1" <<'EOF'
import glob, re, sys
t = f = 0
for x in glob.glob(sys.argv[1] + '/*.xml'):
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', open(x).read(4000))
    if m:
        t += int(m[1]); f += int(m[3]) + int(m[4])
print(f"tests {t} fail {f}")
sys.exit(1 if f or not t else 0)
EOF
}
stop_daemons() { for d in "$ROOT/versions/plugins/horizon/1.0.0" "$ROOT/proof/fork-folia" "$ROOT/proof/fork-canvas"; do
  [ -x "$d/gradlew" ] && (cd "$d" && ./gradlew --stop -q >/dev/null 2>&1); done; }

step core env CORE_LIBS="$ROOT/proof/corelibs" bash "$ROOT/linear-core/build.sh"

if [ "$SKIP_HORIZON" = 0 ]; then
  H="$ROOT/versions/plugins/horizon/1.0.0"
  for props in "$ROOT"/versions/plugins/horizon/upstream.horizon-*.properties; do
    pin="$(grep '^HORIZON_DEV_BUNDLE=' "$props" | cut -d= -f2-)"
    step "horizon-test-$pin" bash -c "cd '$H' && ./gradlew clean test --console=plain -PdevBundle='$pin'" \
      && SUMMARY+=("     $(junit_sum "$H/build/test-results/test")")
  done
  step horizon-jar bash -c "cd '$H' && ./gradlew clean build -x test --console=plain"
  stop_daemons
fi

if [ "$SKIP_FORKS" = 0 ]; then
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS_FORK:--Xmx3g}"
  for leg in folia canvas; do
    W="$ROOT/proof/fork-$leg"
    M="$leg-server"
    step "$leg-stage" bash "$ROOT/scripts/stage.sh" "$leg" || { stop_daemons; continue; }
    step "$leg-compile" bash -c "cd '$W' && ./gradlew :$M:compileJava :$M:compileTestJava --console=plain" || { stop_daemons; continue; }
    step "$leg-suite" bash -c "cd '$W' && ./gradlew :$M:cleanTest :$M:test --tests 'net.linear.LinearNmsTestSuite' --console=plain" \
      && SUMMARY+=("     $(junit_sum "$W/$M/build/test-results/test")")
    [ "$JARS" = 1 ] && step "$leg-paperclip" bash -c "cd '$W' && ./gradlew :$M:createPaperclipJar --console=plain"
    stop_daemons
  done
fi

# Static gates last: preflight reads the fork staged above (proof/fork-folia); it includes the parity contract.
step static-preflight bash "$ROOT/scripts/preflight.sh"
printf '%s\n' "${SUMMARY[@]}" | tee "$OUT/SUMMARY.txt"
! grep -q '^FAIL' "$OUT/SUMMARY.txt"
