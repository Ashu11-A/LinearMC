#!/usr/bin/env python3
"""Coupled-save order validator for a running local server (world snapshots).

usage: scripts/coupled-order-check.py <server-dir> [--seconds N] [--interval S]
           [--level world] [--log PATH] [--report PATH] [--wait N]
           [--chest X,Y,Z=item_id] [--chest-total N] [--lenient] [--snapshots DIR]

The guarantee under test: a player file is never written before the chunks
of its region are on disk. The server's own debug lines are not trusted as
the proof; they only say WHICH .dat writes were coupled captures.

Every --interval seconds the validator samples <level>/players/data/*.dat
(and <level>/playerdata/*.dat). On each new .dat write (mtime change) it at
once copies the .dat and the region file for the player's saved Pos (+ the
.swp of a .b_linear) into a snapshot folder, keeping mtimes. Afterwards it
checks every snapshot:

  CHUNK-MISSING   the chunk (floor(x)>>4, floor(z)>>4) is absent from the
                  snapshotted region file (.mca: header location entry;
                  .linear/.b_linear: scripts/RegionChunkProbe.java on the
                  linear-core reader). Chunks are never removed, so a later
                  region copy missing the chunk is also a violation.
  REGION-MISSING  no region file exists at the player's position.
  (REGION-AFTER-DAT was removed: the debug write ms is logged after the
  .dat rename, so region <= dat <= write ms always held and it could never
  fire. The conservation check below is the real ordering oracle.)
  WRITE-BEFORE-BARRIER  debug write ms < barrierEndMs, or a write whose
                  barrier line was never logged (self-reported; secondary).
  UNCAPTURED      a .dat write with no debug write line within 2 s; a
                  violation by default except within 3 s of a logout / stop
                  log line (direct, not coupled, save paths). --lenient makes
                  it a note.
  ITEM-NOT-CONSERVED  with --chest: for every snapshot, the copied .dat
                  (Inventory + EnderItems) and the chest's region copied at
                  the same instant are paired; count the item in both. The
                  pair is attributable when the chest region mtime <= the
                  .dat mtime and, when the debug log names the capture, <=
                  its barrierEndMs (a region flushed between barrier and
                  rename may hold a newer chest: counted as ambiguous, not
                  checked). FAIL when inventory + chest is below the highest
                  total seen so far (item in neither place: the 2026-11-25
                  incident shape) or, with --chest-total N, differs from N
                  in either direction (lost or duplicated). Move the item
                  between inventory and chest several times both ways; do
                  not keep it on the cursor, in a crafting grid or dropped.
  CHEST           (secondary) the item was never seen in the chest in any
                  snapshot: the deposit step did not happen or the chest
                  coordinates are wrong, so conservation tested nothing.

Coverage: at start every region file of the level is copied to
<snapshots>/baseline. A .dat snapshot whose chunk is absent from that
baseline is "fresh" (generated during the session): only those make
CHUNK-MISSING a real test, so the report prints fresh_chunk_checks.
Logout/quit writes ('lost connection', 'Saving players', 'Disconnecting',
'Stopping server' within 3 s) are direct saves, excluded from UNCAPTURED by
design: the logout and stop steps test nothing about coupled ordering.

A snapshot is "attributable" when its region copy has mtime <= the .dat
mtime: then it is the region state the .dat was written against. Regions
renamed again after the .dat (normal under play) are still checked for chunk
presence but not counted as attributable.

Exit codes: 0 PASS, 1 FAIL (any violation), 3 INCONCLUSIVE (debug log off,
no captured .dat write, or no attributable chunk-presence check: never a
PASS), 2 usage error. Read-only on the server folder; snapshots go to
--snapshots (default a temp dir). Start it before or after the server: it
waits up to --wait seconds for the level folder and the log.
"""
import argparse
import gzip
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zlib

DEBUG_BARRIER = re.compile(r'\[coupled-save-debug\] barrier capture=(\d+) endMs=(\d+) files=(\d+)')
DEBUG_WRITE = re.compile(r'\[coupled-save-debug\] write capture=(\d+) barrierEndMs=(\d+) ms=(\d+) file=(\S+)')
DIRECT_SAVE = re.compile(r'left the game|lost connection|Stopping server|Saving players|Disconnecting')
DIMS = {
    'minecraft:overworld': ['dimensions/minecraft/overworld/region', 'region'],
    'minecraft:the_nether': ['dimensions/minecraft/the_nether/region', 'DIM-1/region'],
    'minecraft:the_end': ['dimensions/minecraft/the_end/region', 'DIM1/region'],
}
EXTS = ('linear', 'b_linear', 'mca')
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)


def parse_nbt(raw):
    """Minimal NBT reader: the root compound (gzip, zlib or raw) as a dict."""
    for dec in (gzip.decompress, zlib.decompress):
        try:
            raw = dec(raw)
            break
        except (OSError, zlib.error):
            pass
    p = [0]

    def rd(n):
        b = raw[p[0]:p[0] + n]
        if len(b) != n:
            raise ValueError('truncated')
        p[0] += n
        return b

    def u2():
        return struct.unpack('>H', rd(2))[0]

    def i4():
        return struct.unpack('>i', rd(4))[0]

    def payload(t):
        if t == 1: return struct.unpack('>b', rd(1))[0]
        if t == 2: return struct.unpack('>h', rd(2))[0]
        if t == 3: return i4()
        if t == 4: return struct.unpack('>q', rd(8))[0]
        if t == 5: return struct.unpack('>f', rd(4))[0]
        if t == 6: return struct.unpack('>d', rd(8))[0]
        if t == 7: return rd(i4())
        if t == 8: return rd(u2()).decode('utf-8', 'replace')
        if t == 9:
            et = rd(1)[0]
            n = i4()
            return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                ct = rd(1)[0]
                if ct == 0:
                    return d
                name = rd(u2()).decode('utf-8', 'replace')
                d[name] = payload(ct)
        if t == 11: return rd(4 * i4())
        if t == 12: return rd(8 * i4())
        raise ValueError('tag %d' % t)

    t = rd(1)[0]
    rd(u2())
    return payload(t)


def player_dirs(level):
    return [d for d in (os.path.join(level, 'players', 'data'), os.path.join(level, 'playerdata')) if os.path.isdir(d)]


def region_file(level, dim, cx, cz):
    rx, rz = cx >> 5, cz >> 5
    for rel in DIMS.get(dim, ['dimensions/%s/region' % dim.replace(':', '/')]):
        for ext in EXTS:
            path = os.path.join(level, rel, 'r.%d.%d.%s' % (rx, rz, ext))
            if os.path.exists(path):
                return path, rx, rz
    return None, rx, rz


def mca_chunk(path, cx, cz):
    """Uncompressed chunk NBT bytes, b'' for an external/unknown-codec chunk, None if absent."""
    with open(path, 'rb') as f:
        hdr = f.read(4096)
        if len(hdr) < 4096:
            return None
        i = 4 * ((cx & 31) + (cz & 31) * 32)
        loc = struct.unpack('>I', hdr[i:i + 4])[0]
        off, sectors = loc >> 8, loc & 0xFF
        if off == 0 or sectors == 0:
            return None
        f.seek(off * 4096)
        head = f.read(5)
        if len(head) < 5:
            return None
        length, ctype = struct.unpack('>IB', head)
        if ctype & 0x80:
            return b''
        data = f.read(max(0, length - 1))
    if ctype == 1:
        return gzip.decompress(data)
    if ctype == 2:
        return zlib.decompress(data)
    if ctype == 3:
        return data
    return b''


class Probe:
    """Compiles linear-core + RegionChunkProbe once, then answers per region copy."""

    def __init__(self, work):
        self.work = work
        self.cp = None

    def classpath(self):
        if self.cp is not None:
            return self.cp
        libs = next((d for d in (os.environ.get('CORE_LIBS'), os.path.join(REPO, 'proof', 'corelibs'), '/tmp/opencode/corelibs')
                     if d and os.path.isfile(os.path.join(d, 'zstd-jni.jar'))), None)
        if libs is None:
            raise RuntimeError('no linear-core libs (set CORE_LIBS or run linear-core/build.sh once)')
        deps = ':'.join(os.path.join(libs, j) for j in ('lz4-java.jar', 'zstd-jni.jar', 'openhft-hashing.jar', 'slf4j.jar', 'mojang-logging.jar'))
        out = os.path.join(self.work, 'probe-classes')
        os.makedirs(out, exist_ok=True)
        srcs = [os.path.join(r, f) for r, _, fs in os.walk(os.path.join(REPO, 'linear-core'))
                for f in fs if f.endswith('.java') and (os.sep + 'src' + os.sep) in os.path.join(r, f)]
        srcs.append(os.path.join(HERE, 'RegionChunkProbe.java'))
        subprocess.run(['javac', '-nowarn', '-d', out, '-cp', deps] + srcs, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.cp = out + ':' + deps
        return self.cp

    def chunk(self, region, cx, cz):
        out = os.path.join(os.path.dirname(region), 'probe-out')
        res = subprocess.run(['java', '-cp', self.classpath(), 'RegionChunkProbe', region, out, str(cx), str(cz)],
                             capture_output=True, text=True, check=True)
        for line in res.stdout.splitlines():
            parts = line.split()
            if len(parts) >= 3 and parts[:2] == [str(cx), str(cz)]:
                if parts[2] == 'absent':
                    return None
                with open(os.path.join(out, 'c.%d.%d.nbt' % (cx, cz)), 'rb') as f:
                    return f.read()
        raise RuntimeError('probe gave no answer: ' + res.stdout + res.stderr)


def chunk_at(probe, rcopy, cx, cz):
    return mca_chunk(rcopy, cx, cz) if rcopy.endswith('.mca') else probe.chunk(rcopy, cx, cz)


def count_items(items, item):
    n = 0
    for it in items or []:
        if isinstance(it, dict) and it.get('id') == item:
            n += int(it.get('count', it.get('Count', 1)))
    return n


def chest_count(chunk_nbt, x, y, z, item):
    """Count of item in the block entity at x,y,z; None if the chunk/chest is unreadable or absent."""
    if not chunk_nbt:
        return None
    try:
        root = parse_nbt(chunk_nbt)
    except (ValueError, IndexError, struct.error):
        return None
    for be in root.get('block_entities', []) or []:
        if isinstance(be, dict) and (be.get('x'), be.get('y'), be.get('z')) == (x, y, z):
            return count_items(be.get('Items'), item)
    return None


def player_count(dat_path, item):
    with open(dat_path, 'rb') as f:
        root = parse_nbt(f.read())
    return count_items(root.get('Inventory'), item) + count_items(root.get('EnderItems'), item)


def copy_baseline(level, dest, cap_bytes=2 << 30):
    """Copies every region file of the level once at start; returns {abs region path: copy} or None if over cap."""
    files = []
    for rels in DIMS.values():
        for rel in rels:
            d = os.path.join(level, rel)
            if os.path.isdir(d):
                files += [os.path.join(d, f) for f in os.listdir(d) if f.rsplit('.', 1)[-1] in EXTS]
    if sum(os.path.getsize(f) for f in files) > cap_bytes:
        return None
    out = {}
    for i, f in enumerate(files):
        dd = os.path.join(dest, '%04d' % i)
        os.makedirs(dd, exist_ok=True)
        c = os.path.join(dd, os.path.basename(f))
        shutil.copy2(f, c)
        if os.path.exists(f + '.swp'):
            shutil.copy2(f + '.swp', c + '.swp')
        out[os.path.realpath(f)] = c
    return out


class LogTail:
    """Follows the server log from its current end; re-resolves a missing log."""

    def __init__(self, candidates, from_start):
        self.candidates = candidates
        self.path = None
        self.from_start = from_start
        self.pos = 0
        self.barriers = {}   # capture id -> (endMs, files)
        self.writes = []     # (capture, barrierEndMs, ms, file)
        self.direct = []     # wall-clock seconds of logout/stop lines
        self.violations = []

    def poll(self):
        if self.path is None:
            self.path = next((p for p in self.candidates if os.path.exists(p)), None)
            if self.path is None:
                return
            self.pos = 0 if self.from_start else os.path.getsize(self.path)
        if not os.path.exists(self.path):
            return
        if os.path.getsize(self.path) < self.pos:
            self.pos = 0  # rotated
        now = time.time()
        with open(self.path, 'r', errors='replace') as f:
            f.seek(self.pos)
            for line in f:
                if DIRECT_SAVE.search(line):
                    self.direct.append(now)
                m = DEBUG_BARRIER.search(line)
                if m:
                    self.barriers[int(m.group(1))] = (int(m.group(2)), int(m.group(3)))
                    continue
                m = DEBUG_WRITE.search(line)
                if m:
                    cap, bend, ms, fp = int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)
                    self.writes.append((cap, bend, ms, fp))
                    if ms < bend:
                        self.violations.append('WRITE-BEFORE-BARRIER capture=%d %s ms=%d < barrierEndMs=%d' % (cap, os.path.basename(fp), ms, bend))
                    elif self.barriers.get(cap, (None,))[0] != bend:
                        self.violations.append('WRITE-BEFORE-BARRIER capture=%d %s: no barrier line logged before the write' % (cap, os.path.basename(fp)))
            self.pos = f.tell()


def snapshot(d, dat, reg):
    """Copies .dat + region (+ .swp) into d keeping mtimes; retries if the region moved mid-copy."""
    os.makedirs(d, exist_ok=True)
    shutil.copy2(dat, os.path.join(d, os.path.basename(dat)))
    if reg is None:
        return None
    rcopy = os.path.join(d, os.path.basename(reg))
    for _ in range(5):
        before = os.stat(reg).st_mtime_ns
        shutil.copy2(reg, rcopy)
        if os.path.exists(reg + '.swp'):
            shutil.copy2(reg + '.swp', rcopy + '.swp')
        if os.stat(reg).st_mtime_ns == before:
            break
    return rcopy


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('server_dir')
    ap.add_argument('--seconds', type=float, default=120)
    ap.add_argument('--interval', type=float, default=0.5)
    ap.add_argument('--level', default='world')
    ap.add_argument('--log', default=None, help='server log (default <dir>/logs/latest.log, else <dir>/server.log)')
    ap.add_argument('--from-start', action='store_true', help='read the whole log, not only lines written while sampling')
    ap.add_argument('--report', default=None)
    ap.add_argument('--wait', type=float, default=600, help='seconds to wait for the level folder to appear')
    ap.add_argument('--chest', default=None, help='X,Y,Z=minecraft:item  chest (overworld) the player fills during the session')
    ap.add_argument('--chest-total', type=int, default=None, help='exact item total (inventory + ender chest + chest) the player keeps all session')
    ap.add_argument('--lenient', action='store_true', help='UNCAPTURED .dat writes are notes, not violations')
    ap.add_argument('--snapshots', default=None, help='snapshot folder (default: a new temp dir, kept)')
    a = ap.parse_args()
    sdir = os.path.abspath(a.server_dir)
    level = os.path.join(sdir, a.level)
    chest = None
    if a.chest:
        try:
            pos, item = a.chest.split('=')
            x, y, z = (int(v) for v in pos.split(','))
            chest = (x, y, z, item)
        except ValueError:
            print('bad --chest, want X,Y,Z=minecraft:item', file=sys.stderr)
            return 2
    end_wait = time.time() + a.wait
    while not os.path.isdir(level):
        if time.time() >= end_wait:
            print('level not found after %gs: %s' % (a.wait, level), file=sys.stderr)
            return 2
        time.sleep(1)
    snapdir = a.snapshots or tempfile.mkdtemp(prefix='coupled-order-')
    os.makedirs(snapdir, exist_ok=True)
    tail = LogTail([a.log] if a.log else [os.path.join(sdir, 'logs', 'latest.log'), os.path.join(sdir, 'server.log')], a.from_start)
    seen = {}
    for d in player_dirs(level):
        for fn in os.listdir(d):
            if fn.endswith('.dat'):
                try:
                    seen[os.path.join(d, fn)] = os.stat(os.path.join(d, fn)).st_mtime_ns
                except OSError:
                    pass
    baseline = copy_baseline(level, os.path.join(snapdir, 'baseline'))
    snaps, chest_snaps, start_pairs = [], [], []
    if chest is not None:
        creg, _, _ = region_file(level, 'minecraft:overworld', chest[0] >> 4, chest[2] >> 4)
        for i, fp in enumerate(sorted(seen)):
            cd = os.path.join(snapdir, 'start', '%02d' % i)
            try:
                start_pairs.append((os.path.join(cd, os.path.basename(fp)), snapshot(cd, fp, creg) if creg else None))
            except OSError as e:
                notes.append('start-pair %s %s' % (os.path.basename(fp), e))
    notes, skipped = [], 0
    deadline = time.time() + a.seconds
    while True:
        tail.poll()
        for d in player_dirs(level):
            for fn in os.listdir(d):
                if not fn.endswith('.dat'):
                    continue
                fp = os.path.join(d, fn)
                try:
                    st = os.stat(fp)
                    if seen.get(fp) == st.st_mtime_ns:
                        continue
                    seen[fp] = st.st_mtime_ns
                    with open(fp, 'rb') as f:
                        root = parse_nbt(f.read())
                    pos, dim = root.get('Pos'), root.get('Dimension', 'minecraft:overworld')
                    if not pos:
                        skipped += 1
                        continue
                    cx, cz = math.floor(pos[0]) >> 4, math.floor(pos[2]) >> 4
                    reg, rx, rz = region_file(level, dim, cx, cz)
                    sd = os.path.join(snapdir, '%04d' % len(snaps))
                    rcopy = snapshot(sd, fp, reg)
                    if chest is not None:
                        creg, _, _ = region_file(level, 'minecraft:overworld', chest[0] >> 4, chest[2] >> 4)
                        if creg:
                            cd = os.path.join(sd, 'chest')
                            crc = snapshot(cd, fp, creg)
                            chest_snaps.append(dict(fn=fn, path=fp, dat=os.path.join(cd, fn), dat_ns=st.st_mtime_ns,
                                                    rcopy=crc, reg_ns=os.stat(crc).st_mtime_ns))
                    snaps.append(dict(fn=fn, path=fp, dat_ns=st.st_mtime_ns, dim=dim, pos=pos, cx=cx, cz=cz,
                                      rx=rx, rz=rz, region=reg, rcopy=rcopy, wall=time.time(),
                                      reg_ns=os.stat(rcopy).st_mtime_ns if rcopy else None))
                except (OSError, ValueError, IndexError, struct.error) as e:
                    skipped += 1
                    notes.append('read-skip %s %s' % (fn, e))
        if time.time() >= deadline:
            break
        time.sleep(a.interval)
    time.sleep(2.5)
    tail.poll()

    violations = list(tail.violations)
    probe = Probe(snapdir)
    attributable = presence_checked = uncaptured = direct_ok = fresh = 0
    debug_on = bool(tail.barriers or tail.writes)
    for s in snaps:
        dat_ms = s['dat_ns'] // 1_000_000
        real = os.path.realpath(s['path'])
        cap = next((w for w in tail.writes if os.path.realpath(w[3]) == real and abs(w[2] - dat_ms) <= 2000), None)
        s['captured'] = cap is not None
        if cap is None and debug_on:
            if any(abs(t - s['wall']) <= 3 + a.interval for t in tail.direct):
                direct_ok += 1
                notes.append('direct-save %s (logout/stop path, not a coupled capture)' % s['fn'])
            else:
                uncaptured += 1
                (notes if a.lenient else violations).append('UNCAPTURED %s mtime=%d' % (s['fn'], dat_ms))
        if s['region'] is None:
            violations.append('REGION-MISSING %s at %s (%.1f, %.1f) region r.%d.%d' % (s['fn'], s['dim'], s['pos'][0], s['pos'][2], s['rx'], s['rz']))
            continue
        try:
            data = chunk_at(probe, s['rcopy'], s['cx'], s['cz'])
        except Exception as e:  # noqa: BLE001 - reported as a violation, never a PASS
            violations.append('PROBE-ERROR %s %s' % (os.path.basename(s['rcopy']), e))
            continue
        attr = s['reg_ns'] <= s['dat_ns']
        presence_checked += 1
        if baseline is not None:
            b = baseline.get(os.path.realpath(s['region']))
            try:
                if b is None or chunk_at(probe, b, s['cx'], s['cz']) is None:
                    fresh += 1
            except Exception as e:  # noqa: BLE001 - coverage only
                notes.append('baseline-probe %s %s' % (os.path.basename(b), e))
        if data is None:
            violations.append('CHUNK-MISSING %s chunk (%d, %d) not in %s (%s copy)' % (
                s['fn'], s['cx'], s['cz'], os.path.basename(s['region']), 'attributable' if attr else 'later'))
            continue
        if attr and cap is not None:
            attributable += 1
    chest_line = 'chest=off'
    if chest is not None:
        item = chest[3]
        with_item = checked = ambiguous = unreadable = 0
        best = None
        for sdat, sreg in start_pairs:  # the state at start seeds "highest seen"
            try:
                t = player_count(sdat, item) + ((chest_count(chunk_at(probe, sreg, chest[0] >> 4, chest[2] >> 4), *chest[:3], item) or 0) if sreg else 0)
                best = t if best is None else max(best, t)
            except Exception as e:  # noqa: BLE001
                notes.append('start-pair unreadable %s' % e)
        start_total = best
        players = {c['fn'] for c in chest_snaps}
        if len(players) > 1:
            notes.append('chest: %d players wrote .dat files; conservation counts each pair alone (use one player)' % len(players))
        for c in chest_snaps:
            try:
                data = chunk_at(probe, c['rcopy'], chest[0] >> 4, chest[2] >> 4)
                inv = player_count(c['dat'], item)
            except Exception as e:  # noqa: BLE001
                violations.append('PROBE-ERROR %s %s' % (os.path.basename(c['rcopy']), e))
                continue
            in_chest = chest_count(data, *chest[:3], item)
            if in_chest is None:
                unreadable += 1
                in_chest = 0
            with_item += 1 if in_chest else 0
            cap = next((w for w in tail.writes if os.path.realpath(w[3]) == os.path.realpath(c['path'])
                        and abs(w[2] - c['dat_ns'] // 1_000_000) <= 2000), None)
            if c['reg_ns'] > c['dat_ns'] or (cap is not None and c['reg_ns'] // 1_000_000 > cap[1]):
                ambiguous += 1
                continue
            checked += 1
            total = inv + in_chest
            where = '%s inventory=%d chest=%d total=%d (dat %s)' % (c['fn'], inv, in_chest, total, os.path.relpath(c['dat'], snapdir))
            if a.chest_total is not None and total != a.chest_total:
                violations.append('ITEM-NOT-CONSERVED %s: %s expected %d' % ('lost' if total < a.chest_total else 'duplicated', where, a.chest_total))
            elif best is not None and total < best:
                violations.append('ITEM-NOT-CONSERVED lost: %s < highest seen %d' % (where, best))
            best = total if best is None else max(best, total)
        chest_line = 'chest=%s start_total=%s pairs=%d conservation_checked=%d ambiguous=%d chest_unreadable=%d with_item_in_chest=%d highest_total=%s' % (
            a.chest, start_total, len(chest_snaps), checked, ambiguous, unreadable, with_item, best)
        if chest_snaps and not with_item:
            violations.append('CHEST %s: item never seen in the chest in any snapshot (%s)' % (a.chest, chest_line))
    captured = sum(1 for s in snaps if s['captured'])
    nonempty = sum(1 for _, files in tail.barriers.values() if files > 0)
    capture_ids = len({w[0] for w in tail.writes})
    if nonempty != capture_ids:
        notes.append('barriers with files>0 (%d) != captures that wrote (%d): a capture withheld, failed or still writing' % (nonempty, capture_ids))
    lines = [
        'server=%s level=%s log=%s seconds=%g snapshots=%s' % (sdir, a.level, tail.path, a.seconds, snapdir),
        'dat_writes=%d captured=%d direct_saves=%d uncaptured=%d read_skips=%d' % (len(snaps), captured, direct_ok, uncaptured, skipped),
        'chunk_presence_checked=%d fresh_chunk_checks=%s attributable=%d violations=%d' % (
            presence_checked, fresh if baseline is not None else 'n/a(baseline over cap)', attributable, len(violations)),
        'debug_log=%s barriers=%d barriers_with_files=%d capture_ids_with_writes=%d captured_file_writes=%d' % (
            'on' if debug_on else 'none', len(tail.barriers), nonempty, capture_ids, len(tail.writes)),
        chest_line,
    ]
    lines += violations[:100]
    lines += notes[-50:]
    if violations:
        result, rc = 'FAIL', 1
    elif not debug_on or captured == 0 or attributable == 0:
        result, rc = 'INCONCLUSIVE (needs -Dlinearmc.coupled-save.debug=true, >=1 captured .dat write and >=1 attributable chunk check)', 3
    else:
        result, rc = 'PASS', 0
    lines.append('RESULT: %s' % result)
    text = '\n'.join(lines) + '\n'
    sys.stdout.write(text)
    if a.report:
        with open(a.report, 'w') as f:
            f.write(text)
    return rc


if __name__ == '__main__':
    sys.exit(main())
