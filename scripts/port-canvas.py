#!/usr/bin/env python3
"""Port Folia feature-patch hunks to Canvas per-file patches.

SUPERSEDED: the emitted files lived in
versions/<line>/canvas/patches/sources/ and have been replaced by the
post-apply overlay (versions/<line>/canvas/overlay/, see OVERLAY.md).
Fifteen NMS ports survive verbatim as overlay/nms/*.patch; three files
diverged by proof (shutdown Linear-only rewrite, ServerChunkCache +
ServerLevel canvasConfig rewire) and paper/config/tests ship in their own
overlay homes. Kept for provenance only: DO NOT regenerate (output would
be Folia-verbatim and miss the proven divergences; --check is unwired).

Original docstring follows for the record:

Reads the ordered stack (common/ + folia/ patches), groups hunks by target file,
and emits versions/<line>/canvas/patches/sources/<path>/<File>.java.patch in
Canvas per-file convention (applied downstream via `git am -3`). New
net/linear/* files are NOT patches: they sync verbatim to
versions/<line>/canvas/src/net/linear/ (proven standalone-compiling).

Files with existing Canvas patches (ChunkMap, ServerChunkCache, ServerLevel,
RegionShutdownThread, paper-side) are emitted too but flagged NEEDS-RECONCILE:
their hunks apply against vanilla context and must be hand-checked where the
Canvas base patch touches the same lines (verify with git apply --check).

Usage: python3 scripts/port-canvas.py [--mc 26.1.x] [--check]
  --check: verify emitted files match a fresh extraction (CI-friendly).
"""
import re
import sys
from pathlib import Path

SUP = Path(__file__).resolve().parent.parent
HUNK_SPLIT = re.compile(r"(?m)^(?=diff --git )")

# Hand-triaged files: generated ONCE by port-canvas.py, then reconciled by hand
# against the Canvas base (see PORT-PLAN.md drift notes). The generator must
# NEVER overwrite them (regeneration would drop the triage).
FROZEN = {
    "net/minecraft/world/level/chunk/storage/RegionFile.java",
    "net/minecraft/world/level/chunk/storage/RegionFileStorage.java",
    "net/minecraft/util/worldupdate/RegionStorageUpgrader.java",
    "io/papermc/paper/threadedregions/RegionShutdownThread.java",
}

# Canvas-base divergences vs the Folia base (verified by git apply --check
# against canvas-server post-base-apply tree). Each rule: hunk containing
# MATCH is dropped (None) or replaced. Rationale recorded per rule.
WRITE_COMMENT_HUNK = """@@ -791,6 +792,8 @@ public class RegionFile implements AutoCloseable, ca.spottedleaf.moonrise.patche
         }
     }
 
+    // Linear - vanilla write is already public on this base; the long-key
+    // bridge below satisfies AbstractRegionFile.write.
     public synchronized void write(final ChunkPos pos, final ByteBuffer data) throws IOException {
         int offsetIndex = getOffsetIndex(pos);
         int offset = this.offsets.get(offsetIndex);
"""
RECONCILE = {
    # Canvas base already declares write() public (Folia base had protected):
    # the visibility hunk and its 0018 comment-swap have no target.
    "net/minecraft/world/level/chunk/storage/RegionFile.java": [
        ("-    protected synchronized void write(final ChunkPos pos, final ByteBuffer data) throws IOException {", None),
        ("-    @Override // Linear - widened protected -> public to satisfy AbstractRegionFile.write", WRITE_COMMENT_HUNK),
    ],
}


def stack_patches(mc):
    v = SUP / "versions" / mc
    files = sorted((v / "common").glob("*.patch"))
    files += sorted((v / "folia/patches").glob("minecraft-*.patch"))
    files += sorted((v / "folia/patches").glob("paper-*.patch"))
    return files


def main():
    mc = "26.1.x"
    check = "--check" in sys.argv
    if "--mc" in sys.argv:
        mc = sys.argv[sys.argv.index("--mc") + 1]
    v = SUP / "versions" / mc
    outbase = v / "canvas/patches/sources"
    srcbase = v / "canvas/src/net/linear"

    buckets = {}
    order = []
    for p in stack_patches(mc):
        for section in HUNK_SPLIT.split(p.read_text()):
            m = re.match(r"diff --git a/(\S+) b/\S+", section)
            if not m:
                continue
            target = m.group(1)
            if target.startswith("net/linear/"):
                continue
            buckets.setdefault(target, []).append((p.name, section))
            if target not in order:
                order.append(target)

    # new-file sources sync (verbatim, proven by core build.sh)
    for f in (SUP / "common/linear-core/src/net/linear").glob("*.java"):
        dest = srcbase / f.name
        if check:
            if not dest.is_file() or dest.read_text() != f.read_text():
                print(f"port-canvas CHECK FAIL: {dest}")
                return 1
        else:
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(f.read_text())

    written = []
    for target in order:
        if target in FROZEN:
            print(f"port-canvas: {target}: FROZEN (hand-triaged, skipping)")
            continue
        dest = outbase / (target + ".patch")
        hunks = []
        sources = []
        for pname, section in buckets[target]:
            sources.append(pname)
            # split the section into individual @@ hunks; drop diff/---/+++
            # headers and mail trailers (they corrupt git am if kept as body)
            for part in re.split(r"(?m)^(?=@@ -)", section):
                if not part.startswith("@@"):
                    continue
                # cut mail trailers / stray lines: a hunk is @@ + body lines only
                keep = []
                for ln, line in enumerate(part.splitlines(keepends=True)):
                    if ln == 0:
                        keep.append(line)  # @@ header
                        continue
                    if re.match(r"^(-- $|diff --git |2\.\d)", line):
                        break  # mail trailer / next section: not hunk body
                    if not line.strip("\n"):
                        keep.append(line)  # blank (kept; recount ignores)
                        continue
                    if line[0] in " +-\\":
                        keep.append(line)
                        continue
                    break
                hunks.append("".join(keep))
        # Canvas-base reconcile: drop/adapt hunks whose target already
        # differs (rules above; each application reported).
        rules = RECONCILE.get(target, [])
        live = []
        for h in hunks:
            replaced = False
            for match, repl in rules:
                if match in h:
                    if repl is None:
                        print(f"port-canvas: {target}: dropped diverged hunk ({match[:60]}...)")
                    else:
                        live.append(repl)
                        print(f"port-canvas: {target}: replaced diverged hunk")
                    replaced = True
                    break
            if not replaced:
                live.append(h)
        hunks = live
        # FUSION: git apply never searches backward, so a later hunk that
        # modifies lines added by an earlier hunk in this same file must be
        # fused into it (verified empirically: out-of-order hunks fail even
        # with matching context). Match B's `-` lines against A's `+` lines.
        def split_hunk(h):
            lines = h.splitlines(keepends=True)
            i = 0
            while i < len(lines) and not lines[i].startswith("@@"):
                i += 1
            return lines[:i], lines[i:i + 1], lines[i + 1:]

        fused = True
        while fused:
            fused = False
            for ai in range(len(hunks)):
                _, _, abody = split_hunk(hunks[ai])
                plus_lines = [l[1:] for l in abody if l.startswith("+") and not l.startswith("+++")]
                if not plus_lines:
                    continue
                for bi in range(ai + 1, len(hunks)):
                    _, _, bbody = split_hunk(hunks[bi])
                    minus_lines = [l[1:] for l in bbody if l.startswith("-") and not l.startswith("---")]
                    if not minus_lines:
                        continue
                    if all(m in plus_lines for m in minus_lines):
                        bplus = [l for l in bbody if l.startswith("+") and not l.startswith("+++")]
                        seg_out, pi = [], 0
                        for l in abody:
                            if (l.startswith("+") and not l.startswith("+++") and pi < len(minus_lines)
                                    and l[1:] == minus_lines[pi]):
                                seg_out.append(bplus[pi] if pi < len(bplus) else l)
                                pi += 1
                            else:
                                seg_out.append(l)
                        extra = bplus[pi:]
                        if extra:
                            k = len(seg_out)
                            while k > 0 and seg_out[k - 1].startswith(" "):
                                k -= 1
                            seg_out[k:k] = extra
                        ahead, ahead_hdr, _ = split_hunk(hunks[ai])
                        hunks[ai] = "".join(ahead) + "".join(ahead_hdr) + "".join(seg_out)
                        del hunks[bi]
                        print(f"port-canvas: {target}: fused dependent hunk")
                        fused = True
                        break
                if fused:
                    break
        # recount headers (fusion changes +/- counts) and sort ascending
        # (independent hunks only, by construction).
        recounted = []
        for h in hunks:
            head, hdr, body = split_hunk(h)
            # git counts a bare-empty line as context (both sides)
            o = sum(1 for l in body if l == "\n" or (l and l[0] in " -"))
            n = sum(1 for l in body if l == "\n" or (l and l[0] in " +"))
            m = re.match(r"@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$", hdr[0].rstrip("\n"))
            recounted.append("".join(head) + f"@@ -{m.group(1)},{o} +{m.group(2)},{n} @@{m.group(3)}\n" + "".join(body))

        # SIMULATED RENUMBER: git apply tracks cumulative offsets and fails
        # when headers drift behind the search point (proven empirically).
        # Rewrite every header to its TRUE post-application position:
        # true_start(hunk) = old_start + Σ(new-old deltas of prior non-
        # overlapping hunks). Requires ascending, non-overlapping hunks
        # (fusion above guarantees the latter for dependent pairs).
        def hunk_range(h):
            m = re.match(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$", h.splitlines()[0])
            s = int(m.group(1))
            o = int(m.group(2)) if m.group(2) else 1
            n = int(m.group(4)) if m.group(4) else 1
            return (s, o, n, m.group(5))

        ordered_idx = sorted(range(len(recounted)), key=lambda i: hunk_range(recounted[i])[0])
        shift = 0
        last_end = 0
        renumbered = [None] * len(recounted)
        for i in ordered_idx:
            s, o, n, tail = hunk_range(recounted[i])
            if s < last_end:
                print(f"port-canvas: {target}: OVERLAP remains (needs hand-triage)")
            true_s = s + shift
            lines = recounted[i].splitlines(keepends=True)
            lines[0] = f"@@ -{true_s},{o} +{true_s},{n} @@{tail}\n"
            renumbered[i] = "".join(lines)
            if s >= last_end:
                shift += n - o
                last_end = s + o
        hunks = [renumbered[i] for i in ordered_idx]
        header = (f"From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001\n"
                  f"From: LinearMC <linearmc@users.noreply.github.com>\n"
                  f"Date: Mon, 29 Sep 2026 00:00:00 -0300\n"
                  f"Subject: [PATCH] LinearMC: {target}\n\n"
                  f"Ported from the Folia feature stack ({', '.join(sources)}).\n"
                  f"Hunks kept in stack-apply order. Reconcile against Canvas base\n"
                  f"patches touching this file before staging (see PORT-PLAN.md).\n"
                  f"---\n"
                  f"diff --git a/{target} b/{target}\n"
                  f"--- a/{target}\n"
                  f"+++ b/{target}\n")
        text = header + "".join(hunks)
        if check:
            if not dest.is_file() or dest.read_text() != text:
                print(f"port-canvas CHECK FAIL: {dest}")
                return 1
        else:
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(text)
            written.append(f"{target} ({len(hunks)} hunks from {len(sources)} patches)")
    if check:
        print("port-canvas CHECK OK")
    else:
        print(f"port-canvas: {len(written)} per-file patches + core sources synced")
        for w in written:
            print(f"  {w}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
