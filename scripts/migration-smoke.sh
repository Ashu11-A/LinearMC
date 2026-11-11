#!/usr/bin/env bash
# Migration + clean-stop durability smoke on a real server (one host per run).
#
# usage: scripts/migration-smoke.sh <template-dir> <server.jar|horizon.jar> [work-root]
#   <template-dir>: server dir with the launch jar, eula.txt, configs and plugins/
#     (Horizon leg: horizon.jar + server.jar + plugins/<LinearMC jar>); its world/ is ignored
#   [work-root]: default proof/migration (git-ignored)
# Phases on a fresh copy:
#   A ANVIL : forceload 6 regions, set markers A/B, save-all, stop
#   B LINEAR: A/B still there (existing .mca read or converted, never regenerated);
#             set C (old region) and D (new region); stop WITHOUT save-all
#   C LINEAR: A-D still there (clean-stop durability)
#   D LINEAR --forceUpgrade (FORCE_UPGRADE=1 only; Paper 26.x stubs it): A-D kept
#   E ANVIL : set E in a region kept force-loaded and G in a control region
#             that is unloaded again (no save-all: Folia has none); stop
#   F ANVIL : live `/linear convert --to-linear --execute` while E's region is
#             open, then set F there; the control .mca must convert (the job
#             ran) and the open .mca must be kept
#   G LINEAR: E/F/G still there (a live convert never deletes an open .mca)
# Markers are checked with `setblock <marker> keep`: a present marker FAILS
# ("Could not set the block"), a lost one logs "Changed the block at x, y, z"
# (works on Paper, Folia and Canvas consoles). A lost marker is re-placed by its
# check, so the first phase that sees the loss is decisive. Also fails on a
# .linear beside an existing .mca (read path missed the old file) and, on the
# Horizon leg, without "final stop drain complete". Exit 0 = PASS.
set -u
. "$(dirname "$0")/lib/server.sh"
[ $# -ge 2 ] || { sed -n '2,8p' "$0" >&2; exit 2; }
[ -d "$1" ] || { echo "template dir not found: $1" >&2; exit 2; }
TEMPLATE="$(cd "$1" && pwd)"; LAUNCH="$2"
[ -f "$TEMPLATE/$LAUNCH" ] || { echo "launch jar not found: $TEMPLATE/$LAUNCH" >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${3:-$ROOT/proof/migration}/$(basename "$TEMPLATE")-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$WORK"
( cd "$TEMPLATE" && tar --exclude=./world --exclude=./world_nether --exclude=./world_the_end \
    --exclude=./logs --exclude=./server.log --exclude=./result.txt --exclude=./cmd.pipe -cf - . ) | ( cd "$WORK" && tar -xf - )
cd "$WORK" || exit 2
REPORT="$WORK/migration-report.txt"; : > "$REPORT"
HORIZON=0; [ "$LAUNCH" = horizon.jar ] && HORIZON=1
SERVER_ARGS=""
FAIL=0

run_phase() { # $1 phase, $2 format, $3.. console commands ("@N" = sleep N)
  local phase="$1" format="$2" c; shift 2
  # shellcheck disable=SC2086
  server_start "phase-$phase.log" -Xms512M -Xmx1G "-Dlinearmc.format=$format" -jar "$LAUNCH" --nogui $SERVER_ARGS
  if ! server_wait "phase-$phase.log" 'Done (' 150; then
    echo "phase $phase: SERVER_NOT_READY" >> "$REPORT"; kill -9 "$SERVER_PID" 2>/dev/null; server_stop 1
    cat "$REPORT"; exit 1
  fi
  for c in "$@"; do
    case "$c" in @*) sleep "${c#@}" ;; *) server_send "$c"; sleep 1 ;; esac
  done
  server_stop 90
  echo "phase $phase: java exit $SERVER_RC killed=$SERVER_KILLED" >> "$REPORT"
  [ "$SERVER_KILLED" = 0 ] || FAIL=1
}

count() { find world -path "*/$1/*" -name "*.$2" 2>/dev/null | wc -l; }
snapshot() {
  echo "$1: region mca=$(count region mca) linear=$(count region linear) |" \
    "entities mca=$(count entities mca) linear=$(count entities linear) |" \
    "poi mca=$(count poi mca) linear=$(count poi linear)" >> "$REPORT"
}

# name -> "x y z block"
declare -A MARK=(
  [A]="8 100 8 minecraft:diamond_block"
  [B]="-8 100 -520 minecraft:gold_block"
  [C]="9 100 9 minecraft:emerald_block"
  [D]="2056 100 2056 minecraft:lapis_block"
  [E]="-2040 100 -2040 minecraft:redstone_block"
  [F]="-2041 100 -2041 minecraft:coal_block"
  [G]="-4090 100 -4090 minecraft:iron_block"
)
set_cmd() { echo "setblock ${MARK[$1]}"; }
keep_cmd() { echo "setblock ${MARK[$1]} keep"; }
changed() { # $1 phase, $2 marker -> "Changed the block at <marker>" count
  local x y z _
  read -r x y z _ <<< "${MARK[$2]}"
  grep -cF "Changed the block at $x, $y, $z" "phase-$1.log"
}
expect_set() { # $1 phase, markers...: each was placed in this phase
  local p="$1" m line="phase $1:"; shift
  for m in "$@"; do
    if [ "$(changed "$p" "$m")" -ge 1 ]; then line+=" $m=SET"; else line+=" $m=NOT_SET"; FAIL=1; fi
  done
  echo "$line" >> "$REPORT"
}
expect_kept() { # $1 phase, markers...: keep-checks; a mute console cannot fake a pass
  local p="$1" m line="phase $1:" fails; shift
  fails=$(grep -c "Could not set the block" "phase-$p.log")
  for m in "$@"; do
    if [ "$(changed "$p" "$m")" -eq 0 ] && [ "$fails" -ge $# ]; then line+=" $m=OK"; else line+=" $m=MISSING"; FAIL=1; fi
  done
  echo "$line" >> "$REPORT"
}

run_phase A ANVIL "forceload add -16 -528 15 15" @10 "$(set_cmd A)" "$(set_cmd B)" "save-all flush" @8
snapshot "after A (ANVIL)"
expect_set A A B

run_phase B LINEAR @10 "$(keep_cmd A)" "$(keep_cmd B)" "$(set_cmd C)" "forceload add 2048 2048 2063 2063" @8 "$(set_cmd D)" @3
snapshot "after B (LINEAR, stop without save-all)"
expect_kept B A B
expect_set B C D
SIB=0
while IFS= read -r -d '' f; do [ -f "${f%.mca}.linear" ] && SIB=$((SIB + 1)); done < <(find world -name '*.mca' -print0 2>/dev/null)
echo "mca+linear sibling regions after B: $SIB (0 expected)" >> "$REPORT"
[ "$SIB" -eq 0 ] || FAIL=1
DRAIN=$(grep -c 'final stop drain complete' phase-B.log)
echo "final stop drain lines in B: $DRAIN (horizon=$HORIZON)" >> "$REPORT"
[ "$HORIZON" = 0 ] || [ "$DRAIN" -ge 1 ] || FAIL=1

run_phase C LINEAR @10 "$(keep_cmd A)" "$(keep_cmd B)" "$(keep_cmd C)" "$(keep_cmd D)" "linearstats" @2
snapshot "after C (LINEAR restart)"
expect_kept C A B C D

if [ "${FORCE_UPGRADE:-0}" = 1 ]; then
  SERVER_ARGS="--forceUpgrade"
  run_phase D LINEAR @5 "$(keep_cmd A)" "$(keep_cmd B)" "$(keep_cmd C)" "$(keep_cmd D)" @2
  SERVER_ARGS=""
  snapshot "after D (LINEAR --forceUpgrade)"
  expect_kept D A B C D
  ERR=$(grep -c 'Failed to read chunks' phase-D.log)
  echo "upgrader read errors in D: $ERR (0 expected)" >> "$REPORT"
  [ "$ERR" -eq 0 ] || FAIL=1
fi

run_phase E ANVIL @10 "forceload add -2048 -2048 -2033 -2033" "forceload add -4096 -4096 -4081 -4081" @8 \
  "$(set_cmd E)" "$(set_cmd G)" "forceload remove -4096 -4096 -4081 -4081" @3
expect_set E E G
run_phase F ANVIL @10 "linear convert world --to-linear --execute" @20 "$(set_cmd F)" "linear queue list" @3
expect_set F F
region_has() { find world -path "*/region/$1" 2>/dev/null | wc -l; }
CONV=$(( $(region_has r.-8.-8.linear) == 1 && $(region_has r.-8.-8.mca) == 0 ))
echo "control .mca converted by the live convert in F: $CONV (1 expected)" >> "$REPORT"
KEPT=$(region_has r.-4.-4.mca)
echo "open .mca kept by the live convert in F: $KEPT (1 expected)" >> "$REPORT"
SIB=0
while IFS= read -r -d '' f; do [ -f "${f%.mca}.linear" ] && SIB=$((SIB + 1)); done < <(find world -name '*.mca' -print0 2>/dev/null)
echo "mca+linear sibling regions after F: $SIB (0 expected)" >> "$REPORT"
[ "$CONV" -eq 1 ] && [ "$KEPT" -eq 1 ] && [ "$SIB" -eq 0 ] || FAIL=1
run_phase G LINEAR @10 "forceload add -4096 -4096 -4081 -4081" @8 "$(keep_cmd E)" "$(keep_cmd F)" "$(keep_cmd G)" @2
snapshot "after G (LINEAR restart after a live convert)"
expect_kept G E F G

{
  echo "--- key log (B, C) ---"
  grep -hE 'LinearMC:|\[region-format\]|Mixin.*(error|fail)|Exception|ERROR' phase-B.log phase-C.log | sort -u | head -40
  echo "RESULT: $([ "$FAIL" = 0 ] && echo PASS || echo FAIL) ($WORK)"
} >> "$REPORT"
cat "$REPORT"
[ "$FAIL" = 0 ]
