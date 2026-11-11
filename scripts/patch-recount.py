#!/usr/bin/env python3
"""Recount unified-diff hunk headers after hand-editing a patch.

usage: scripts/patch-recount.py [--check] <file.patch>...

`git am` (paperweight) rejects hunks whose `@@ -a,b +c,d @@` counts do not
match the body: old = context + '-' lines, new = context + '+' lines, and a
blank context line must be a single space. This rewrites every header's
counts from the body (start lines are kept: git apply tolerates offsets),
turns empty body lines into single-space context lines, and keeps the
paperweight `@@ -N,M +_,K @@` convention ('_' new start) intact.
--check only reports (exit 1 when any header is wrong).
"""
import re
import sys

HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(_|\d+)(?:,(\d+))? @@(.*)$")


VERSION = re.compile(r"^\d+(\.\d+)+")


def is_signature(lines, i):
    """git format-patch footer: "-- " then a version line (e.g. "2.43.0")."""
    return lines[i] in ("-- \n", "--\n") and i + 1 < len(lines) and VERSION.match(lines[i + 1])


def recount(lines):
    out = []
    fixed = 0
    i = 0
    while i < len(lines):
        m = HUNK.match(lines[i])
        if not m:
            out.append(lines[i])
            i += 1
            continue
        header_at = len(out)
        out.append(lines[i])
        i += 1
        old = new = 0
        normalized = False
        while i < len(lines):
            line = lines[i]
            if line.startswith(("@@ ", "diff --git ", "From ")) or is_signature(lines, i):
                break
            if line in ("\n", ""):
                # A blank line inside a hunk is a context line written without
                # its leading space (git am needs " "); a blank line that only
                # precedes the end of file / the next diff is trailing noise.
                j = i + 1
                while j < len(lines) and lines[j] in ("\n", ""):
                    j += 1
                if j >= len(lines) or is_signature(lines, j) or lines[j][:1] not in (" ", "-", "+", "\\"):
                    break
                line = " \n"
                normalized = True
            tag = line[0]
            if tag == " ":
                old += 1
                new += 1
            elif tag == "-":
                old += 1
            elif tag == "+":
                new += 1
            elif tag == "\\":
                pass  # "\ No newline at end of file"
            else:
                break
            out.append(line)
            i += 1
        header = f"@@ -{m.group(1)},{old} +{m.group(3)},{new} @@{m.group(5)}\n"
        if header != lines_header(m) or normalized:
            fixed += 1
        out[header_at] = header
    return out, fixed


def lines_header(m):
    a, b, c, d, tail = m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)
    return f"@@ -{a},{b if b is not None else 1} +{c},{d if d is not None else 1} @@{tail}\n"


def main(argv):
    check = "--check" in argv
    files = [a for a in argv if a != "--check"]
    if not files:
        print(__doc__)
        return 2
    bad = 0
    for path in files:
        with open(path, encoding="utf-8") as fh:
            lines = fh.readlines()
        # stop recounting at the trailing git signature ("-- \n" + version)
        out, fixed = recount(lines)
        if fixed:
            bad += 1
            print(f"{path}: {fixed} hunk header(s) {'wrong' if check else 'recounted'}")
            if not check:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.writelines(out)
    return 1 if (check and bad) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
