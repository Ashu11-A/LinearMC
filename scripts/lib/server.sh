# shellcheck shell=bash
# shellcheck disable=SC2034  # SERVER_* are read by the sourcing script
# Shared Minecraft server driver for the smoke scripts (source it).
#   server_start <log> <java args...>  start with a FIFO stdin held on fd 3 (sets SERVER_PID)
#   server_wait <log> <pattern> <secs> 0 once <pattern> is logged, 1 on timeout/exit
#   server_send <command>              write a console command if the server is alive
#   server_stop <secs>                 "stop" and wait; returns 1 and sets SERVER_STUCK=1 on timeout
#                                      (sets SERVER_RC, SERVER_STUCK, SERVER_OOM)
#   server_oom_report                  prints an OOM FAIL line (dump path) when SERVER_OOM=1
# TEST RULE: a test runs to completion; a server is never killed as a test step (no
# crash simulation, no `timeout` cutting a run short). It is stopped with "stop".
# Only when it is already hung after "stop" does server_stop kill it for cleanup
# (TERM, then -9), and the test is then FAILED and says so.
# Every JVM gets -XX:+ExitOnOutOfMemoryError (exit 3) plus a heap dump in $OOM_DIR.
trap '' PIPE   # a server exiting between the alive check and a write must not kill the script

server_start() {
  local log="$1"; shift
  SERVER_LOG="$log"
  rm -f cmd.pipe; mkfifo cmd.pipe
  OOM_DIR="${OOM_DIR:-$PWD/oom}"; mkdir -p "$OOM_DIR"; SERVER_OOM=0
  java -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError "-XX:HeapDumpPath=$OOM_DIR" "$@" < cmd.pipe > "$log" 2>&1 &
  SERVER_PID=$!
  exec 3> cmd.pipe
}

server_wait() {
  local i
  for ((i = 0; i < $3; i++)); do
    grep -q "$2" "$1" 2>/dev/null && return 0
    kill -0 "$SERVER_PID" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}

server_send() { kill -0 "$SERVER_PID" 2>/dev/null && printf '%s\n' "$1" >&3; return 0; }

server_stop() {
  local i
  server_send stop
  for ((i = 0; i < $1; i++)); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
  SERVER_STUCK=0
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    SERVER_STUCK=1
    echo "TEST FAILED: SERVER_STUCK pid $SERVER_PID did not exit $1 s after \"stop\"; killing for cleanup (TERM, then -9)" >&2
    kill -TERM "$SERVER_PID" 2>/dev/null
    for ((i = 0; i < 30; i++)); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    if kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "TEST FAILED: pid $SERVER_PID ignored TERM; sending -9 (cleanup)" >&2
      kill -9 "$SERVER_PID" 2>/dev/null
    fi
    wait "$SERVER_PID" 2>/dev/null
    SERVER_RC=255
    exec 3>&-; rm -f cmd.pipe
    return 1
  fi
  wait "$SERVER_PID" 2>/dev/null
  SERVER_RC=$?
  exec 3>&-; rm -f cmd.pipe
  server_oom_report >&2
  [ "$SERVER_OOM" = 1 ] && return 1
  return 0
}

# Exit code 3 comes from -XX:+ExitOnOutOfMemoryError.
server_oom_report() {
  SERVER_OOM=0
  if [ "${SERVER_RC:-0}" = 3 ] || grep -qs 'java.lang.OutOfMemoryError' "${SERVER_LOG:-/dev/null}"; then
    SERVER_OOM=1
    echo "TEST FAILED: SERVER_OOM pid $SERVER_PID exit=$SERVER_RC heap dump in $OOM_DIR: $(find "$OOM_DIR" -maxdepth 1 -name "*.hprof" -printf "%p ")"
  fi
  return 0
}
