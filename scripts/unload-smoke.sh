#!/usr/bin/env bash
# Region unload flush smoke on a real server (one host per run, LINEAR only).
#
# usage: scripts/unload-smoke.sh <template-dir> <server.jar|horizon.jar> [work-root]
#   <template-dir>: server dir with the launch jar, eula.txt, configs and plugins/
#     (Horizon leg: horizon.jar + server.jar + plugins/<LinearMC jar>); its world/ is ignored
#   [work-root]: default proof/unload (git-ignored)
# Phases on a fresh copy, all with -Dlinearmc.format=LINEAR:
#   A: force-load two far regions, set markers U/V, then UNLOAD them again
#      (forceload remove, no player nearby) and wait flush-frequency + margin.
#      WHILE THE SERVER STILL RUNS, both region .linear files must exist, be
#      written after the unload (mtime >= unload time) and carry the Linear magic
#      in both header and footer (complete, not torn). No save-all is sent before
#      this check. Then a clean console "stop".
#   B: restart, reload both regions; U/V must still be there; clean "stop".
# Proves that unloading a LINEAR region writes it out by itself, without relying
# on save-all or the stop path. Marker checks follow migration-smoke.sh
# (`setblock <marker> keep`: a present marker FAILS with "Could not set the block").
# NO-KILL RULE: servers are stopped only with "stop"; a server that does not exit
# fails the run and is left alive (PID reported), see scripts/lib/server.sh.
# UNLOAD_WAIT (default 25 s = flush-frequency 10 s + margin) is how long phase A
# waits after the unload before checking the disk.
# Exit 0 = PASS.
set -u
. "$(dirname "$0")/lib/server.sh"
[ $# -ge 2 ] || { sed -n '2,7p' "$0" >&2; exit 2; }
[ -d "$1" ] || { echo "template dir not found: $1" >&2; exit 2; }
TEMPLATE="$(cd "$1" && pwd)"; LAUNCH="$2"
[ -f "$TEMPLATE/$LAUNCH" ] || { echo "launch jar not found: $TEMPLATE/$LAUNCH" >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${3:-$ROOT/proof/unload}/$(basename "$TEMPLATE")-$(date +%Y%m%d-%H%M%S)"
UNLOAD_WAIT="${UNLOAD_WAIT:-25}"
mkdir -p "$WORK"
( cd "$TEMPLATE" && tar --exclude=./world --exclude=./world_nether --exclude=./world_the_end \
    --exclude=./logs --exclude=./server.log --exclude=./result.txt --exclude=./cmd.pipe -cf - . ) | ( cd "$WORK" && tar -xf - )
cd "$WORK" || exit 2
REPORT="$WORK/unload-report.txt"; : > "$REPORT"
FAIL=0

boot() { # $1 phase
  server_start "phase-$1.log" -Xms512M -Xmx1G -Dlinearmc.format=LINEAR -jar "$LAUNCH" --nogui
  if ! server_wait "phase-$1.log" 'Done (' 150; then
    echo "phase $1: SERVER_NOT_READY" >> "$REPORT"
    server_stop 90 || echo "phase $1: SERVER_STUCK pid=$SERVER_PID (hung; killed for cleanup, TEST FAILED)" >> "$REPORT"
    echo "RESULT: FAIL ($WORK)" >> "$REPORT"; cat "$REPORT"; exit 1
  fi
}
stop_clean() { # $1 phase
  server_stop 90
  echo "phase $1: java exit $SERVER_RC stuck=$SERVER_STUCK" >> "$REPORT"
  if [ "$SERVER_OOM" = 1 ]; then
    server_oom_report >> "$REPORT"
    echo "RESULT: FAIL ($WORK)" >> "$REPORT"; cat "$REPORT"; exit 1
  fi
  if [ "$SERVER_STUCK" != 0 ]; then
    echo "phase $1: SERVER_STUCK pid=$SERVER_PID (hung; killed for cleanup, TEST FAILED); aborting" >> "$REPORT"
    echo "RESULT: FAIL ($WORK)" >> "$REPORT"; cat "$REPORT"; exit 1
  fi
}
send_all() { local c; for c in "$@"; do case "$c" in @*) sleep "${c#@}" ;; *) server_send "$c"; sleep 1 ;; esac; done; }

# Regions r.6.6 (x/z 3072..3583) and r.-7.6 (x -3584..-3073, z 3072..3583): far from spawn.
declare -A MARK=(
  [U]="3080 100 3080 minecraft:diamond_block"
  [V]="-3080 100 3080 minecraft:gold_block"
)
LOAD=("forceload add 3072 3072 3087 3087" "forceload add -3088 3072 -3073 3087")
UNLOAD=("forceload remove 3072 3072 3087 3087" "forceload remove -3088 3072 -3073 3087")
changed() { local x y z _; read -r x y z _ <<< "${MARK[$2]}"; grep -cF "Changed the block at $x, $y, $z" "phase-$1.log"; }
region() { find world -path "*/region/$1" -size +0 2>/dev/null | wc -l; }
MAGIC=c3ff13183cca9d9a
# $1 region file name, $2 epoch of the unload: prints OK or the reason it is not.
linear_ok() {
  local f; f=$(find world -path "*/region/$1" 2>/dev/null | head -n 1)
  [ -n "$f" ] || { echo MISSING; return; }
  local size mtime head tail
  size=$(stat -c %s "$f"); mtime=$(stat -c %Y "$f")
  [ "$size" -ge 26 ] || { echo "TOO_SMALL($size)"; return; }
  [ "$mtime" -ge "$2" ] || { echo "STALE(mtime $mtime < unload $2)"; return; }
  head=$(od -An -tx1 -N8 "$f" | tr -d ' \n')
  tail=$(tail -c 8 "$f" | od -An -tx1 | tr -d ' \n')
  [ "$head" = "$MAGIC" ] || { echo "BAD_HEADER($head)"; return; }
  [ "$tail" = "$MAGIC" ] || { echo "BAD_FOOTER($tail)"; return; }
  echo "OK(size=$size)"
}

# Phase A: set, unload, wait, check files WHILE the server runs, then clean stop.
boot A
send_all @10 "${LOAD[@]}" @8 "setblock ${MARK[U]}" "setblock ${MARK[V]}" @2
UNLOAD_AT=$(date +%s)
send_all "${UNLOAD[@]}" "@$UNLOAD_WAIT"
line="phase A:"
for m in U V; do
  if [ "$(changed A "$m")" -ge 1 ]; then line+=" $m=SET"; else line+=" $m=NOT_SET"; FAIL=1; fi
done
echo "$line" >> "$REPORT"
alive=yes
kill -0 "$SERVER_PID" 2>/dev/null || { alive=no; FAIL=1; }
echo "phase A: server alive during disk check: $alive" >> "$REPORT"
for r in r.6.6.linear r.-7.6.linear; do
  st=$(linear_ok "$r" "$UNLOAD_AT")
  echo "phase A: $r after unload (no save-all, no stop): $st" >> "$REPORT"
  case "$st" in OK*) ;; *) FAIL=1 ;; esac
done
MCA=$(( $(region r.6.6.mca) + $(region r.-7.6.mca) ))
echo "unloaded region .mca files: $MCA (0 expected)" >> "$REPORT"
[ "$MCA" -eq 0 ] || FAIL=1
stop_clean A

# Phase B: reload and keep-check the markers.
boot B
send_all @10 "${LOAD[@]}" @8 "setblock ${MARK[U]} keep" "setblock ${MARK[V]} keep" @2
stop_clean B
fails=$(grep -c "Could not set the block" phase-B.log)
line="phase B:"
for m in U V; do
  if [ "$(changed B "$m")" -eq 0 ] && [ "$fails" -ge 2 ]; then line+=" $m=OK"; else line+=" $m=MISSING"; FAIL=1; fi
done
echo "$line" >> "$REPORT"
ERR=$(grep -cE 'Failed to read chunk|Corrupt|RegionFile.*(Exception|error)' phase-B.log)
echo "region read errors in B: $ERR (0 expected)" >> "$REPORT"
[ "$ERR" -eq 0 ] || FAIL=1

{
  echo "--- key log (A, B) ---"
  grep -hE 'LinearMC:|\[region-format\]|Mixin.*(error|fail)|Exception|ERROR' phase-A.log phase-B.log | sort -u | head -40
  echo "RESULT: $([ "$FAIL" = 0 ] && echo PASS || echo FAIL) ($WORK)"
} >> "$REPORT"
cat "$REPORT"
[ "$FAIL" = 0 ]
