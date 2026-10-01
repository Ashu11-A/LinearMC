#!/usr/bin/env bash
# Audit that every `new RegionFile` site in a target RegionFileStorage class
# is covered by a mixin @Redirect in RegionFileStorageMixin.
# A covered-everywhere class cannot silently parse .linear as Anvil
# (giant out-of-bounds sectors, Negative position, header-rewriting loss).
#
# Usage: scripts/audit-mixin-sites.sh <server.jar> [mixin-source-dir]
#   server.jar: e.g. ~/Downloads/folia/versions/26.1.2/canvas-26.1.2.jar
# Exit 0 = every NEW site has a matching redirect; else lists gaps.
set -u
JAR="${1:?usage: audit-mixin-sites.sh <server.jar> [mixin-source-dir]}"
MIXIN_DIR="${2:-$(cd "$(dirname "$0")/.." && pwd)/versions/plugins/horizon/1.0.0/src/main/java/io/linearmc/horizon/mixins}"
MIXIN="$MIXIN_DIR/RegionFileStorageMixin.java"

OUT="$(mktemp -d /tmp/linearmc-audit.XXXXXX)"
trap 'rm -rf "$OUT"' EXIT
unzip -o -q "$JAR" \
  'net/minecraft/world/level/chunk/storage/RegionFileStorage.class' -d "$OUT" \
  || { echo "FAIL: RegionFileStorage.class not in $JAR"; exit 1; }

python3 - "$OUT" "$MIXIN" <<'EOF'
import re, subprocess, sys

classes, mixin = sys.argv[1], sys.argv[2]
javap = subprocess.run(
    ["javap", "-p", "-c", "-cp", classes,
     "net.minecraft.world.level.chunk.storage.RegionFileStorage"],
    capture_output=True, text=True)
if javap.returncode != 0:
    print("FAIL: javap failed"); sys.exit(1)
lines = javap.stdout.split("\n")

bounds = []
for i, l in enumerate(lines):
    m = re.match(
        r"  (?:public|private|protected|static|final|synchronized|\s)*"
        r"(?:[\w.$\[\]]+\s+)+([\w$]+)\([^)]*\).*;\s*$", l)
    if m and "Field" not in l and "//" not in l:
        bounds.append((i, m.group(1)))

sites = []
for i, l in enumerate(lines):
    if re.search(
            r"new\s+#\d+\s+// class net/minecraft/world/level/chunk/storage/RegionFile$",
            l):
        enc = [b for b in bounds if b[0] < i][-1]
        sites.append(enc[1])

src = open(mixin).read()
redirects = re.findall(r'method = "([^"]+)"', src)
covered = {r.split("(")[0] for r in redirects}

print(f"NEW RegionFile sites: {len(sites)}")
fail = 0
for s in sites:
    mark = "COVERED" if s in covered else "GAP"
    if s not in covered:
        fail = 1
    print(f"  {mark}: {s}")
if fail:
    print("FAIL: uncovered construction sites (silent Anvil-parse risk)")
    sys.exit(1)
print("audit OK: every construction site has a redirect")
EOF
