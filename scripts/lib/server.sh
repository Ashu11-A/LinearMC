# shellcheck shell=bash
# shellcheck disable=SC2034  # SERVER_* are read by the sourcing script
# Shared Minecraft server driver for the smoke scripts (source it).
#   server_start <log> <java args...>  start with a FIFO stdin held on fd 3 (sets SERVER_PID)
#   server_wait <log> <pattern> <secs> 0 once <pattern> is logged, 1 on timeout/exit
#   server_send <command>              write a console command if the server is alive
#   server_stop <secs>                 "stop", wait, SIGKILL on timeout (sets SERVER_RC, SERVER_KILLED)
trap '' PIPE   # a server exiting between the alive check and a write must not kill the script

server_start() {
  local log="$1"; shift
  rm -f cmd.pipe; mkfifo cmd.pipe
  java "$@" < cmd.pipe > "$log" 2>&1 &
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
  SERVER_KILLED=0
  if kill -0 "$SERVER_PID" 2>/dev/null; then kill -9 "$SERVER_PID"; SERVER_KILLED=1; fi
  wait "$SERVER_PID" 2>/dev/null
  SERVER_RC=$?
  exec 3>&-; rm -f cmd.pipe
}
