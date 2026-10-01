#!/usr/bin/env bash
# boot-and-wait.sh — start a paperclip jar, wait for `Done (` in its log, then
# kill it. Replaces `timeout 300 java -jar ... || true` (which idles the full
# 300s AFTER the server is already up): a boot that reaches Done in ~60s now
# costs ~60s instead of 300s.
#
# Usage: boot-and-wait.sh <jar> <workdir> <logfile> <max-wait-secs>
# Exit 0 iff `Done (` appears in the log within the wait window.
# The server is ALWAYS killed before exit (no stray Java on the runner).
set -u
JAR="$1"; DIR="$2"; LOG="$3"; MAXWAIT="${4:-240}"

cd "$DIR" || exit 1
echo "eula=true" > eula.txt
: > "$LOG"
java -jar "$JAR" --nogui > "$LOG" 2>&1 &
SRV=$!
echo "boot-and-wait: pid $SRV booting $JAR (cap ${MAXWAIT}s)"

elapsed=0
while [ "$elapsed" -lt "$MAXWAIT" ]; do
  if grep -q 'Done (' "$LOG" 2>/dev/null; then
    echo "boot-and-wait: Done after ~${elapsed}s"
    break
  fi
  if ! kill -0 "$SRV" 2>/dev/null; then
    echo "boot-and-wait: server exited early (no Done)"
    break
  fi
  sleep 5
  elapsed=$((elapsed + 5))
done

kill "$SRV" 2>/dev/null || true
sleep 5
kill -9 "$SRV" 2>/dev/null || true
wait "$SRV" 2>/dev/null || true

if grep -q 'Done (' "$LOG" 2>/dev/null; then
  echo "boot-and-wait: OK"
  grep -ciE '^\[.*ERROR\]' "$LOG" || true
  exit 0
fi
echo "boot-and-wait: FAIL — no 'Done (' within ${MAXWAIT}s; tail:"
tail -n 40 "$LOG"
exit 1
