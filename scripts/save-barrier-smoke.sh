#!/usr/bin/env bash
# Live save-all barrier, save-off lease and coupled-save order smoke (one host per run).
#
# usage: scripts/save-barrier-smoke.sh <template-dir> <server.jar|horizon.jar> [seconds] [work-root]
#   <template-dir>: server dir with the launch jar, eula.txt, configs and plugins/
#     (LoadGen plugin optional: without it the run uses forceload). Its world/ is ignored.
#   [seconds]: load phase length, default 300 (the 5-minute loadgen run)
#   [work-root]: default proof/barrier (git-ignored)
#   env COUPLED=1 (default) turns linear coupled-save on (interval 5 s); COUPLED=0 leaves it off.
#   env BARRIER=0 skips phase B (a host without a save-all barrier; every supported host has one now).
#   env JVM_EXTRA: extra JVM flags, e.g. -Dlinearmc.coupled-save.debug=true (debug lines for scripts/coupled-order-check.py).
#   env PORT (default 25585) so the run can share the machine with one other smoke server.
# Phases on a fresh copy, LINEAR world, server running the whole time:
#   B barrier (5 rounds while the load runs): save-off, edit, `save-all flush`,
#     wait for <level>/linear-checkpoint/save-all.done to get a new id, then
#     check: failed=0, no region/entities/poi .linear renamed after the
#     marker (saving is off, so nothing may land later), no leftover temp
#     files, and at least one round renamed a region inside [startMs,endMs].
#     save-on after each round.
#   L lease (every host: Horizon config.yml, Folia paper-global.yml, Canvas
#     canvas-server.yml): save-off with save-off-max-duration=20 and no save-on;
#     the expiry WARN must appear within 45 s, then a save-all flush must write a marker.
#   C coupled order (COUPLED=1): every 5 s sample player .dat mtimes and the
#     mtime of the .linear region holding the player's saved Pos; a fresh .dat
#     write whose region file does not exist yet counts as a violation, and one
#     whose region was renamed later than the .dat is listed as "later region
#     rename" (allowed: the region may be dirtied again after the capture).
#     LoadGen bots are not saved players, so with no real client the
#     sample count is 0 and the phase is reported as vacuous, never as PASS.
# Stops with `stop` only (never kills as a test step). Exit 0 = PASS.
set -u
export LC_ALL=C  # stat/awk must use "." decimals (a pt_BR locale prints ",")
. "$(dirname "$0")/lib/server.sh"
[ $# -ge 2 ] || { sed -n '2,9p' "$0" >&2; exit 2; }
[ -d "$1" ] || { echo "template dir not found: $1" >&2; exit 2; }
TEMPLATE="$(cd "$1" && pwd)"; LAUNCH="$2"; LOAD_SECS="${3:-300}"; COUPLED="${COUPLED:-1}"
[ -f "$TEMPLATE/$LAUNCH" ] || { echo "launch jar not found: $TEMPLATE/$LAUNCH" >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${4:-$ROOT/proof/barrier}/$(basename "$TEMPLATE")-c$COUPLED-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$WORK" || exit 2
WORK="$(cd "$WORK" && pwd)" || exit 2  # absolute: the script cds into it; a relative [work-root] broke every later path
( cd "$TEMPLATE" && tar --exclude=./world --exclude=./world_nether --exclude=./world_the_end \
    --exclude=./logs --exclude=./server.log --exclude=./result.txt --exclude=./cmd.pipe -cf - . ) | ( cd "$WORK" && tar -xf - )
cd "$WORK" || exit 2
REPORT="$WORK/barrier-report.txt"; : > "$REPORT"
FAIL=0
say() { echo "$*" | tee -a "$REPORT"; }
bad() { say "FAIL: $*"; FAIL=1; }

COUPLED_BOOL=false; [ "$COUPLED" = 1 ] && COUPLED_BOOL=true
if [ "$LAUNCH" = horizon.jar ]; then
  mkdir -p plugins/LinearMC
  cat > plugins/LinearMC/config.yml <<EOF
region-format:
  format: LINEAR
  linear:
    flush-frequency: 10
    save-off-max-duration: 20
    durable-player-data: true
    coupled-save:
      enabled: $COUPLED_BOOL
      interval: 5
EOF
else
  # Forks: the same keys in the fork's global config (Folia: config/paper-global.yml
  # region-format.linear; Canvas: config/canvas-server.yml linear). The file must
  # already hold the linear section (a template booted once); the keys are set or added.
  python3 -I - "$WORK/config" "$COUPLED_BOOL" > "$WORK/fork-config.txt" 2>&1 <<'PY' || { bad "fork config: could not set lease/coupled keys ($(cat "$WORK/fork-config.txt"))"; cat "$REPORT"; exit 1; }
import os, re, sys
cfg, coupled = sys.argv[1], sys.argv[2]
canvas = os.path.join(cfg, 'canvas-server.yml'); folia = os.path.join(cfg, 'paper-global.yml')
if os.path.exists(canvas):
    path, header = canvas, re.compile(r'^linear:\s*$')
elif os.path.exists(folia):
    path, header = folia, re.compile(r'^  linear:\s*$')
else:
    sys.exit('no canvas-server.yml or paper-global.yml under ' + cfg)
lines = open(path).read().split('\n')
start = next((i for i, l in enumerate(lines) if header.match(l)), None)
if start is None:
    sys.exit('no linear section in ' + path)
base = len(lines[start]) - len(lines[start].lstrip())
end = start + 1
while end < len(lines) and (not lines[end].strip() or lines[end].lstrip().startswith('#') or len(lines[end]) - len(lines[end].lstrip()) > base):
    end += 1
block = lines[start + 1:end]
ind = next((len(l) - len(l.lstrip()) for l in block if l.strip() and not l.lstrip().startswith('#')), base + 2)
step = ind - base
def drop(key):
    out, skip = [], None
    for l in block:
        d = len(l) - len(l.lstrip())
        if skip is not None and l.strip() and d > skip:
            continue
        skip = None
        if l.strip().startswith(key + ':') and d == ind:
            skip = d; continue
        out.append(l)
    return out
for key in ('save-off-max-duration', 'durable-player-data', 'coupled-save'):
    block = drop(key)
pad = ' ' * ind
new = [pad + 'save-off-max-duration: 20', pad + 'durable-player-data: true', pad + 'coupled-save:',
       pad + ' ' * step + 'enabled: ' + coupled, pad + ' ' * step + 'interval: 5']
lines[start + 1:end] = new + block
open(path, 'w').write('\n'.join(lines))
print('set lease=20 coupled=%s in %s' % (coupled, os.path.basename(path)))
PY
  say "fork config: $(cat "$WORK/fork-config.txt")"
fi

sed -i "s/^server-port=.*/server-port=${PORT:-25585}/; s/^enable-rcon=.*/enable-rcon=false/; s/^enable-query=.*/enable-query=false/" server.properties
LOADGEN=0; [ -s plugins/LoadGen/config.yml ] && LOADGEN=1
# shellcheck disable=SC2086  # JVM_EXTRA: optional extra JVM flags (e.g. -Dlinearmc.coupled-save.debug=true)
server_start server.log -Xms512M -Xmx1G -Dlinearmc.format=LINEAR ${JVM_EXTRA:-} -jar "$LAUNCH" --nogui
if ! server_wait server.log 'Done (' 150; then
  bad "SERVER_NOT_READY"; server_stop 90 || bad "SERVER_STUCK pid=$SERVER_PID (hung; killed for cleanup)"
  cat "$REPORT"; exit 1
fi
LEVEL="$WORK/world"
MARKER="$LEVEL/linear-checkpoint/save-all.done"
# 16 chunks around the origin (all four regions r.-1.-1 .. r.0.0) stay loaded for the round edits.
server_send "forceload add -32 -32 31 31"
[ "$LOADGEN" = 1 ] && server_send "loadgen start"
sleep 20

marker_id() { [ -f "$MARKER" ] && grep -o '"id" *: *[0-9]*' "$MARKER" | grep -o '[0-9]*$' || echo 0; }
mjson() { grep -o "\"$1\" *: *[0-9]*" "$MARKER" | grep -o '[0-9]*$'; }
regions() { find "$LEVEL" \( -path '*/region/*' -o -path '*/entities/*' -o -path '*/poi/*' \) -name '*.linear' -printf '%T@ %p\n'; }

# Coupled sampler (background, own state file): runs until $WORK/stop-sampler exists.
python3 -I - "$LEVEL" "$WORK/coupled.txt" "$WORK/stop-sampler" > "$WORK/sampler.log" 2>&1 <<'PY' &
import gzip, os, struct, sys, time
level, out, stop = sys.argv[1], sys.argv[2], sys.argv[3]
def nbt_pos(path):
    # Minimal NBT reader: returns the root compound's Pos [x, y, z] or None.
    with open(path, 'rb') as f:
        raw = f.read()
    try:
        raw = gzip.decompress(raw)
    except OSError:
        pass
    p = [0]
    def rd(n):
        b = raw[p[0]:p[0] + n]; p[0] += n; return b
    def u2(): return struct.unpack('>H', rd(2))[0]
    def i4(): return struct.unpack('>i', rd(4))[0]
    def payload(t):
        if t == 1: return rd(1)
        if t == 2: return rd(2)
        if t == 3: return i4()
        if t == 4: return rd(8)
        if t == 5: return struct.unpack('>f', rd(4))[0]
        if t == 6: return struct.unpack('>d', rd(8))[0]
        if t == 7: n = i4(); return rd(n)
        if t == 8: return rd(u2())
        if t == 9:
            et = rd(1)[0]; n = i4(); return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                ct = rd(1)[0]
                if ct == 0: return d
                name = rd(u2()).decode('utf-8', 'replace'); d[name] = payload(ct)
        if t == 11: n = i4(); return rd(4 * n)
        if t == 12: n = i4(); return rd(8 * n)
        raise ValueError(t)
    t = rd(1)[0]; rd(u2())
    root = payload(t)
    return root.get('Pos'), root.get('Dimension', b'minecraft:overworld')
seen = {}; samples = 0; writes = 0; missing = 0; later = 0; lines = []
while not os.path.exists(stop):
    for dirpath, _, files in os.walk(level):
        if not (dirpath.endswith('playerdata') or dirpath.endswith(os.path.join('players', 'data'))):
            continue
        for fn in files:
            if not fn.endswith('.dat'):
                continue
            fp = os.path.join(dirpath, fn)
            try:
                st = os.stat(fp)
                if seen.get(fp) == st.st_mtime_ns:
                    continue
                seen[fp] = st.st_mtime_ns; samples += 1
                pos, dim = nbt_pos(fp)
                if not pos or not str(dim).endswith('overworld\''):
                    continue
                writes += 1
                rx, rz = int(pos[0]) >> 9, int(pos[2]) >> 9
                cands = [os.path.join(d, 'region', 'r.%d.%d.linear' % (rx, rz)) for d, _, _ in os.walk(level) if d.endswith('overworld')]
                reg = next((c for c in cands if os.path.exists(c)), None)
                if reg is None:
                    missing += 1; lines.append('VIOLATION %s region r.%d.%d missing' % (fn, rx, rz))
                elif os.stat(reg).st_mtime_ns > st.st_mtime_ns:
                    later += 1
            except (OSError, ValueError, IndexError, struct.error) as e:
                lines.append('read-skip %s %s' % (fn, e))
    with open(out, 'w') as f:
        f.write('samples=%d dat_writes=%d violations=%d later_region_rename=%d\n' % (samples, writes, missing, later))
        f.write('\n'.join(lines[-50:]) + '\n')
    time.sleep(5)
PY
SAMPLER=$!

# Phase B: barrier rounds.
ROUNDS=5; WROTE_IN_WINDOW=0; [ "${BARRIER:-1}" = 1 ] || { ROUNDS=0; WROTE_IN_WINDOW=1; say "barrier: SKIPPED (BARRIER=0: host has no save-all barrier)"; }
for r in $(seq 1 $ROUNDS); do
  before=$(marker_id)
  server_send "save-off"; sleep 2
  for sx in 1 -1; do for sz in 1 -1; do
    server_send "setblock $((sx * r * 3)) 100 $((sz * r * 2)) minecraft:stone"
  done; done
  sleep 1
  server_send "save-all flush"
  ok=0
  for _ in $(seq 1 90); do [ "$(marker_id)" -gt "$before" ] && { ok=1; break; }; sleep 1; done
  if [ "$ok" = 0 ]; then bad "round $r: marker id stayed $before for 90 s"; server_send "save-on"; continue; fi
  sleep 3
  mm=$(stat -c %.9Y "$MARKER"); id=$(marker_id); st=$(mjson startMs); en=$(mjson endMs); fl=$(mjson failed); rg=$(mjson regions)
  after=$(regions | awk -v m="$mm" '$1 > m' | wc -l)
  inwin=$(regions | awk -v s="$st" -v e="$en" '$1*1000 >= s && $1*1000 <= e' | wc -l)
  tmps=$(find "$LEVEL" \( -path '*/region/*' -o -path '*/entities/*' -o -path '*/poi/*' \) \( -name '*.tmp' -o -name '*.wip' -o -name '*.linear.*' \) ! -name '*.b_linear.swp' | wc -l)
  say "round $r: id=$id regions=$rg failed=$fl startMs=$st endMs=$en renamed_in_window=$inwin renamed_after_marker=$after temp_files=$tmps"
  [ "${fl:-1}" = 0 ] || bad "round $r: marker failed=$fl"
  [ "$after" = 0 ] || { bad "round $r: $after region file(s) renamed after the marker"; regions | awk -v m="$mm" '$1 > m' >> "$REPORT"; }
  [ "$tmps" = 0 ] || bad "round $r: $tmps temp file(s) left after the barrier"
  [ "$inwin" -gt 0 ] && WROTE_IN_WINDOW=1
  server_send "save-on"
  sleep 15
done
[ "$WROTE_IN_WINDOW" = 1 ] || bad "no round renamed a region inside the save-all window (barrier not exercised)"
grep -q 'Saved the game' server.log || say "NOTE: no 'Saved the game' line (expected on Folia/Canvas consoles)"

# Phase L: save-off lease (configured 20 s), every host (Horizon and forks).
server_send "save-off"
if server_wait server.log 'longer than linear.save-off-max-duration' 45; then
  say "lease: expiry WARN seen"
  sleep 2
  # After the expiry saving must be on again: a save-all flush completes with a new marker.
  before=$(marker_id); server_send "save-all flush"; ok=0
  for _ in $(seq 1 60); do [ "$(marker_id)" -gt "$before" ] && { ok=1; break; }; sleep 1; done
  if [ "${BARRIER:-1}" = 1 ]; then
    [ "$ok" = 1 ] && say "lease: saving back on (post-expiry save-all wrote marker $(marker_id))" || bad "lease: no save-all marker after the expiry"
  fi
else
  bad "lease: no expiry WARN within 45 s"; server_send "save-on"
fi

# Phase C: keep the load running for the rest of the window.
spent=$(( 20 + ROUNDS * 25 + 45 )); rest=$(( LOAD_SECS - spent )); [ "$rest" -gt 0 ] && sleep "$rest"
[ "$LOADGEN" = 1 ] && server_send "loadgen status"
touch "$WORK/stop-sampler"; wait "$SAMPLER" 2>/dev/null
server_stop 90 || bad "SERVER_STUCK pid=$SERVER_PID (hung after stop; killed for cleanup)"
[ "$SERVER_OOM" = 1 ] && { server_oom_report; FAIL=1; }
if [ "$COUPLED" = 1 ]; then
  say "coupled: $(head -1 "$WORK/coupled.txt" 2>/dev/null || echo 'no sampler output')"
  grep -q '^VIOLATION' "$WORK/coupled.txt" 2>/dev/null && { bad "coupled order violation(s)"; grep '^VIOLATION' "$WORK/coupled.txt" >> "$REPORT"; }
  grep -q '^samples=0 ' "$WORK/coupled.txt" 2>/dev/null && say "coupled: VACUOUS (no player .dat written; LoadGen bots are not saved players)"
fi
grep -E 'Coupled save|withheld|LinearMC: .*(barrier|save-all|lease)|Exception|ERROR' server.log | tail -n 30 >> "$REPORT"
say "exit=$SERVER_RC stuck=$SERVER_STUCK work=$WORK"
[ "$FAIL" = 0 ] && say "RESULT: PASS" || say "RESULT: FAIL"
grep -q '^RESULT: PASS' "$REPORT" 2>/dev/null || { grep -q '^RESULT:' "$REPORT" 2>/dev/null || echo "FAIL: no report produced at $REPORT" >&2; exit 1; }
exit "$FAIL"
