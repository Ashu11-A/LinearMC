#!/usr/bin/env bash
# Revert (LINEAR -> ANVIL) + permission smoke on a real server (one host per run).
#
# usage: scripts/revert-smoke.sh <template-dir> <server.jar|horizon.jar> [work-root]
#   <template-dir>: server dir with the launch jar, eula.txt, configs and plugins/
#   [work-root]: default proof/revert (git-ignored)
# Phases on a fresh copy (markers live far from spawn so nothing keeps them open):
#   A ANVIL : forceload R1, set marker A, save-all flush, stop (pre-existing .mca world)
#   B LINEAR: A kept (old .mca read); forceload R2, set marker B (new .linear region);
#             `/linear convert world --to-linear --execute`; drop all forceloads; stop
#             without save-all. Expect .mca=0 in region/entities/poi.
#   C LINEAR: `/linear convert <w> --to-mca --execute` for world, world_nether,
#             world_the_end (convert targets one Bukkit world); stop
#             Expect .linear=0 and .b_linear=0 in region/entities/poi.
#   D ANVIL : A/B kept, no read errors; stop
#   E ANVIL : A/B kept again (second restart); console `linear stats`/`linearstats`
#             answer (op allow path); stop. A non-op deny needs a real player
#             (`execute as <entity>` keeps the console permission level), so the
#             deny side is covered by LinearPermissionsTest / LinearBrigadierTest.
# Markers use `setblock <marker> keep` (see migration-smoke.sh). Exit 0 = PASS.
# NO-KILL RULE: servers stop only via "stop" (scripts/lib/server.sh).
set -u
. "$(dirname "$0")/lib/server.sh"
[ $# -ge 2 ] || { sed -n '2,6p' "$0" >&2; exit 2; }
[ -d "$1" ] || { echo "template dir not found: $1" >&2; exit 2; }
TEMPLATE="$(cd "$1" && pwd)"; LAUNCH="$2"
[ -f "$TEMPLATE/$LAUNCH" ] || { echo "launch jar not found: $TEMPLATE/$LAUNCH" >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${3:-$ROOT/proof/revert}/$(basename "$TEMPLATE")-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$WORK" || exit 2
WORK="$(cd "$WORK" && pwd)" || exit 2  # absolute: the script cds into it; a relative [work-root] broke every later path
( cd "$TEMPLATE" && tar --exclude=./world --exclude=./world_nether --exclude=./world_the_end \
    --exclude=./logs --exclude=./server.log --exclude=./result.txt --exclude=./cmd.pipe -cf - . ) | ( cd "$WORK" && tar -xf - )
cd "$WORK" || exit 2
REPORT="$WORK/revert-report.txt"; : > "$REPORT"
FAIL=0

run_phase() { # $1 phase, $2 format, $3.. console commands ("@N" = sleep N)
  local phase="$1" format="$2" c; shift 2
  server_start "phase-$phase.log" -Xms512M -Xmx1G "-Dlinearmc.format=$format" -jar "$LAUNCH" --nogui
  if ! server_wait "phase-$phase.log" 'Done (' 150; then
    echo "phase $phase: SERVER_NOT_READY" >> "$REPORT"
    server_stop 90 || echo "phase $phase: SERVER_STUCK pid=$SERVER_PID (hung; killed for cleanup, TEST FAILED)" >> "$REPORT"
    echo "RESULT: FAIL ($WORK)" >> "$REPORT"; cat "$REPORT"; exit 1
  fi
  for c in "$@"; do
    case "$c" in @*) sleep "${c#@}" ;; *) server_send "$c"; sleep 1 ;; esac
  done
  server_stop 90
  echo "phase $phase: java exit $SERVER_RC stuck=$SERVER_STUCK" >> "$REPORT"
  if [ "$SERVER_OOM" = 1 ]; then
    server_oom_report >> "$REPORT"
    echo "RESULT: FAIL ($WORK)" >> "$REPORT"; cat "$REPORT"; exit 1
  fi
  if [ "$SERVER_STUCK" != 0 ]; then
    echo "phase $phase: SERVER_STUCK pid=$SERVER_PID (hung; killed for cleanup, TEST FAILED); aborting" >> "$REPORT"
    echo "RESULT: FAIL ($WORK)" >> "$REPORT"; cat "$REPORT"; exit 1
  fi
}

count() { find world -path "*/$1/*" -name "*.$2" 2>/dev/null | wc -l; }
counts() { local d e s=""; for d in region entities poi; do s+="$d"; for e in mca linear b_linear; do s+=" $e=$(count "$d" "$e")"; done; s+=" | "; done; echo "$s"; }
total() { find world \( -path '*/region/*' -o -path '*/entities/*' -o -path '*/poi/*' \) -name "*.$1" 2>/dev/null | wc -l; }

declare -A MARK=(
  [A]="2056 100 2056 minecraft:diamond_block"
  [B]="-2040 100 -2040 minecraft:gold_block"
)
R1="forceload add 2048 2048 2063 2063"; R1X="forceload remove 2048 2048 2063 2063"
R2="forceload add -2048 -2048 -2033 -2033"; R2X="forceload remove -2048 -2048 -2033 -2033"
set_cmd() { echo "setblock ${MARK[$1]}"; }
keep_cmd() { echo "setblock ${MARK[$1]} keep"; }
changed() { local x y z _; read -r x y z _ <<< "${MARK[$2]}"; grep -cF "Changed the block at $x, $y, $z" "phase-$1.log"; }
expect_set() {
  local p="$1" m line="phase $1:"; shift
  for m in "$@"; do if [ "$(changed "$p" "$m")" -ge 1 ]; then line+=" $m=SET"; else line+=" $m=NOT_SET"; FAIL=1; fi; done
  echo "$line" >> "$REPORT"
}
expect_kept() {
  local p="$1" m line="phase $1:" fails; shift
  fails=$(grep -c "Could not set the block" "phase-$p.log")
  for m in "$@"; do
    if [ "$(changed "$p" "$m")" -eq 0 ] && [ "$fails" -ge $# ]; then line+=" $m=OK"; else line+=" $m=MISSING"; FAIL=1; fi
  done
  echo "$line" >> "$REPORT"
}
read_errors() { grep -cE 'Failed to read chunk|Couldn.t load chunk|Error reading chunk|Corrupt|RegionFileVersion|Exception.*(region|Region|linear|Linear)' "phase-$1.log"; }
check_errors() { local n; n=$(read_errors "$1"); echo "phase $1: read/region errors=$n (0 expected)" >> "$REPORT"; [ "$n" -eq 0 ] || FAIL=1; }

run_phase A ANVIL "$R1" @10 "$(set_cmd A)" "save-all flush" @8 "$R1X" @3
echo "after A (ANVIL): $(counts)" >> "$REPORT"
expect_set A A

run_phase B LINEAR @5 "$R1" @10 "$(keep_cmd A)" "$R2" @10 "$(set_cmd B)" "$R1X" "$R2X" @5 \
  "linear convert world --to-linear --execute" "linear convert world_nether --to-linear --execute" \
  "linear convert world_the_end --to-linear --execute" @25 "linear queue list" @3
echo "after B (LINEAR + --to-linear): $(counts)" >> "$REPORT"
expect_kept B A
expect_set B B
check_errors B
MCA=$(total mca); echo "phase B: .mca left after --to-linear: $MCA (0 expected)" >> "$REPORT"
[ "$MCA" -eq 0 ] || FAIL=1

run_phase C LINEAR @5 "linear convert world --to-mca --execute" "linear convert world_nether --to-mca --execute" \
  "linear convert world_the_end --to-mca --execute" @30 "linear queue list" @3
echo "after C (LINEAR + --to-mca): $(counts)" >> "$REPORT"
check_errors C
LIN=$(( $(total linear) + $(total b_linear) ))
echo "phase C: .linear/.b_linear left after --to-mca: $LIN (0 expected)" >> "$REPORT"
[ "$LIN" -eq 0 ] || FAIL=1

run_phase D ANVIL @5 "$R1" "$R2" @12 "$(keep_cmd A)" "$(keep_cmd B)" "$R1X" "$R2X" @3
echo "after D (ANVIL restart): $(counts)" >> "$REPORT"
expect_kept D A B
check_errors D
LIN=$(( $(total linear) + $(total b_linear) )); echo "phase D: .linear/.b_linear: $LIN (0 expected)" >> "$REPORT"
[ "$LIN" -eq 0 ] || FAIL=1

run_phase E ANVIL @5 "$R1" "$R2" @12 "$(keep_cmd A)" "$(keep_cmd B)" \
  "linear stats" @1 "linearstats" @1 "$R1X" "$R2X" @3
echo "after E (ANVIL second restart): $(counts)" >> "$REPORT"
expect_kept E A B
check_errors E
ST=$(grep -c 'Linear stats (' phase-E.log); echo "phase E: console stats panels=$ST (2 expected)" >> "$REPORT"
[ "$ST" -ge 2 ] || FAIL=1

{
  echo "--- console permission feedback (E) ---"
  grep -hiE 'permission|unknown or incomplete|Unknown command|Incorrect argument|Linear stats' phase-E.log | grep -viE 'Loaded|Mixin' | head -30
  echo "--- convert/queue lines (B, C) ---"
  grep -hiE 'convert|queue|job' phase-B.log phase-C.log | grep -viE 'Loaded|Mixin' | head -30
  echo "RESULT: $([ "$FAIL" = 0 ] && echo PASS || echo FAIL) ($WORK)"
} >> "$REPORT"
cat "$REPORT"
# No report (or no RESULT line) is a failure, never a silent exit 0.
grep -q '^RESULT: PASS' "$REPORT" 2>/dev/null || { grep -q '^RESULT:' "$REPORT" 2>/dev/null || echo "FAIL: no report produced at $REPORT" >&2; exit 1; }
[ "$FAIL" = 0 ]
