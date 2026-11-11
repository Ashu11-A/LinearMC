#!/usr/bin/env bash
# Quick per-server smoke/load test (one server, ~5 minutes max).
# usage: scripts/server-smoke.sh <server-dir> <loadgen|forceload|idle> <seconds> [horizon] [format]
#   <server-dir>: server.jar (or horizon.jar + plugins/ for the Horizon leg) and an accepted eula.txt
#   [format]: LINEAR (default), B_LINEAR, ANVIL (-Dlinearmc.format) or CONFIG (no sysprop)
#   env: EXTRA_JAVA_OPTS (e.g. -Dlinearmc.implementation=V2), SMOKE_CMDS (';'-separated console commands)
# LoadGen (~/Documents/GitHub/folia-loadgen) needs Folia/Canvas; use forceload on Paper.
# Exit 0 = booted, stopped cleanly and, on a fresh LINEAR/B_LINEAR world, wrote no .mca.
# Migration and clean-stop durability: scripts/migration-smoke.sh.
set -u
. "$(dirname "$0")/lib/server.sh"
usage() { sed -n '2,9p' "$0" >&2; exit 2; }
[ $# -ge 3 ] || usage
DIR="$1"; MODE="$2"; DURATION="$3"; JAR=server.jar; [ "${4:-}" = horizon ] && JAR=horizon.jar; FORMAT="${5:-LINEAR}"
case "$MODE" in loadgen|forceload|idle) ;; *) echo "unknown mode: $MODE" >&2; usage ;; esac
case "$FORMAT" in LINEAR|B_LINEAR|ANVIL) FLAG="-Dlinearmc.format=$FORMAT" ;; CONFIG) FLAG="" ;; *) usage ;; esac
cd "$DIR" || exit 2
[ -f "$JAR" ] || { echo "missing $DIR/$JAR" >&2; exit 2; }
FRESH=1; [ -d world ] && FRESH=0
rm -f server.log result.txt
# shellcheck disable=SC2086
server_start server.log -Xms512M -Xmx1G ${EXTRA_JAVA_OPTS:-} $FLAG -jar "$JAR" --nogui
if ! server_wait server.log 'Done (' 150; then
  { echo "SERVER_NOT_READY"; tail -n 80 server.log; } > result.txt
  kill -9 "$SERVER_PID" 2>/dev/null; server_stop 1; cat result.txt; exit 1
fi
IFS=';' read -r -a EXTRA <<< "${SMOKE_CMDS:-}"
for c in "${EXTRA[@]}"; do server_send "$c"; sleep 1; done
case "$MODE" in
  loadgen)   server_send "loadgen start"; sleep "$DURATION"; server_send "loadgen status" ;;
  forceload) server_send "forceload add 0 0 256 256"; sleep "$DURATION"; server_send "forceload query" ;;
  idle)      sleep "$DURATION" ;;
esac
server_send save-all
server_wait server.log 'Saved the game' 60 || true
server_stop 90
cnt() { find . -path "*/$1/*" -name "*.$2" 2>/dev/null | wc -l; }
MCA=$(cnt region mca)
{
  echo "dir=$DIR mode=$MODE format=$FORMAT duration=${DURATION}s exit=$SERVER_RC killed=$SERVER_KILLED fresh=$FRESH"
  for f in region entities poi; do echo "$f: linear=$(cnt "$f" linear) b_linear=$(cnt "$f" b_linear) mca=$(cnt "$f" mca)"; done
  echo "--- key log ---"
  grep -E 'Done \(|LinearMC:|LoadGen|\[region-format\]|Exception|ERROR|Mixin|host=' server.log | tail -n 50
} > result.txt
cat result.txt
[ "$SERVER_KILLED" = 0 ] || exit 1
if [ "$FRESH" = 1 ] && [ "$FORMAT" != ANVIL ] && [ "$FORMAT" != CONFIG ] && [ "$MCA" -gt 0 ]; then
  echo "FAIL: fresh $FORMAT world wrote $MCA .mca region file(s)"; exit 1
fi
