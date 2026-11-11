#!/usr/bin/env bash
# Leg-parity contract gate for parity/capabilities.yml (format: see its header).
#
#   scripts/parity-check.sh            # branch gate: schema + evidence + tests; gaps listed
#   scripts/parity-check.sh --release  # release gate: additionally zero `gap` cells
#   scripts/parity-check.sh --matrix   # capability x leg matrix (markdown); exit 1 on errors
#
# Exit 0 = contract holds for the chosen mode. Needs python3 + PyYAML.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-branch}"
case "$MODE" in
  branch|--release|--matrix) ;;
  -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
  *) echo "usage: $0 [--release|--matrix]" >&2; exit 2 ;;
esac
python3 - "$ROOT" "$MODE" <<'EOF'
import os, re, sys
import yaml

root, mode = sys.argv[1], sys.argv[2]
path = os.path.join(root, "parity", "capabilities.yml")
try:
    doc = yaml.safe_load(open(path, encoding="utf-8")) or {}
except yaml.YAMLError as bad:
    print(f"FAIL: {path} is not valid YAML: {bad}")
    sys.exit(1)

legs = doc.get("legs") or []
if not legs:
    print("FAIL: `legs` missing or empty")
    sys.exit(1)
aliases = doc.get("aliases") or {}
errors, gaps, test_gaps, rows = [], [], [], []

# Real test classes (file stem). Suites (*Suite) never count: they prove nothing alone.
tests = set()
for base in ("linear-core", "versions"):
    for d, dirs, files in os.walk(os.path.join(root, base)):
        dirs[:] = [x for x in dirs if x not in ("build", ".gradle", "removed")]
        tests.update(f[:-5] for f in files if f.endswith("Test.java"))
TEST_REF = re.compile(r"\b([A-Z]\w*Test)\b")
cache = {}

def text(rel):
    if rel not in cache:
        full = os.path.join(root, rel)
        if not os.path.isfile(full):
            cache[rel] = None
        else:
            raw = open(full, encoding="utf-8", errors="replace").read()
            # In a patch only ADDED lines count: a removed or context line
            # must never prove that a feature exists.
            cache[rel] = ("\n".join(l[1:] for l in raw.splitlines() if l.startswith("+") and not l.startswith("+++"))
                          if rel.endswith(".patch") else raw)
    return cache[rel]

def check_evidence(where, items):
    if not isinstance(items, list) or not items:
        errors.append(f"{where}: `ev` must be a non-empty list of 'path#symbol'")
        return
    for item in items:
        if not isinstance(item, str) or "#" not in item:
            errors.append(f"{where}: evidence {item!r} is not 'path#symbol'")
            continue
        rel, sym = item.split("#", 1)
        for a, prefix in aliases.items():
            if rel.startswith(a):
                rel = prefix + rel[len(a):]
                break
        body = text(rel)
        if body is None:
            errors.append(f"{where}: evidence file missing: {rel}")
        elif sym not in body:
            errors.append(f"{where}: symbol {sym!r} not found in {rel}")

seen = set()
for cap in doc.get("capabilities") or []:
    if not isinstance(cap, dict) or not cap.get("id") or cap["id"] in seen:
        errors.append(f"capability id missing or duplicated: {cap!r:.60}")
        continue
    cid = cap["id"]
    seen.add(cid)
    if "core" in cap:
        check_evidence(f"{cid}/core", cap["core"])
    row = [cid]
    for leg in legs:
        cell, where = cap.get(leg), f"{cid}/{leg}"
        if isinstance(cell, dict) and set(cell) - {"ev", "test", "test_gap", "gap", "exempt", "approved"}:
            errors.append(f"{where}: unknown cell keys {sorted(set(cell) - {'ev', 'test', 'test_gap', 'gap', 'exempt', 'approved'})}")
        if not isinstance(cell, dict):
            errors.append(f"{where}: missing cell (every capability names every leg)")
            row.append("?")
        elif "gap" in cell:
            gaps.append(f"{where}: {cell['gap']}")
            row.append("❌")
        elif "exempt" in cell:
            if not cell.get("approved"):
                errors.append(f"{where}: exempt needs `approved` (who/when)")
            row.append("⚪")
        else:
            check_evidence(where, cell.get("ev"))
            refs = TEST_REF.findall(str(cell.get("test", "")))
            missing = [r for r in refs if r not in tests]
            if missing:
                errors.append(f"{where}: test class(es) not found: {', '.join(missing)}")
            if cell.get("test_gap"):
                test_gaps.append(f"{where}: {cell['test_gap']}")
            elif not refs:
                errors.append(f"{where}: no test class named (set test_gap with the plan)")
            row.append("✅*" if cell.get("test_gap") else "✅")
    extra = set(cap) - {"id", "title", "core"} - set(legs)
    if extra:
        errors.append(f"{cid}: unknown keys {sorted(extra)}")
    rows.append(row)

if mode == "--matrix":
    print("| capability | " + " | ".join(legs) + " |")
    print("| --- |" + " --- |" * len(legs))
    for r in rows:
        print("| " + " | ".join(r) + " |")
    print("\n✅* supported, with test debt (see the branch gate)")
    for e in errors:
        print(f"FAIL: {e}", file=sys.stderr)
else:
    for e in errors:
        print(f"FAIL: {e}")
    for g in gaps:
        print(f"{'FAIL' if mode == '--release' else 'GAP '}: {g}")
    for g in test_gaps:
        print(f"TEST: {g}")
    print(f"parity: {len(rows)} capabilities x {len(legs)} legs; "
          f"{len(gaps)} gap(s), {len(test_gaps)} test gap(s), {len(errors)} error(s)")
sys.exit(1 if errors or (mode == "--release" and gaps) else 0)
EOF
