#!/usr/bin/env python3
"""Import lint for LinearMC Java sources (no JDK tooling needed).

usage: scripts/lint-java.py [--fix] [path ...]   (default: every leg's sources)

Checks each .java file, and each Java file a .patch creates (new-file hunks):
  unused       import whose simple name never appears outside imports/comments
               (Javadoc {@link}/@see/@throws references count as uses)
  duplicate    the same import twice
  redundant    java.lang.* or same-package single-type import
  fqn          a fully qualified name of a type the file already imports
Exit 1 on any finding (one line each: file:line: kind: detail). --fix rewrites
plain .java files (drops the import line, or the qualifier); patches are only
reported (edit them by hand, then scripts/patch-recount.py).
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_ROOTS = ["linear-core", "versions/plugins", "versions/servers"]
SKIP = ("versions/servers/removed/",)

IMPORT = re.compile(r"^\s*import\s+(static\s+)?([\w.]+?)(\.\*)?\s*;", re.M)
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
JAVADOC_REF = re.compile(r"\{@(?:link|linkplain|value)\s+([^}]*)\}|@(?:see|throws|exception)\s+([\w.#]+(?:\([^)]*\))?)")


def strip(src, keep_javadoc_refs):
    """Blank out strings, chars and comments, keeping line numbers.

    Javadoc type references are re-emitted so they count as uses."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            body = src[i:j]
            kept = list(re.sub(r"[^\n]", " ", body))
            if keep_javadoc_refs:
                for ref in JAVADOC_REF.finditer(body):
                    g = 1 if ref.group(1) is not None else 2
                    kept[ref.start(g):ref.end(g)] = body[ref.start(g):ref.end(g)]
            out.append("".join(kept))
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(re.sub(r"[^\n]", " ", src[i:j]))
            i = j
        elif c in "\"'":
            j = i + 1
            while j < n and src[j] != c and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            out.append(" " * (min(j + 1, n) - i))
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def findings_of(src):
    """Return (offset, end, kind, detail, replacement) per finding; offsets index src."""
    out = []
    code = strip(src, keep_javadoc_refs=False)
    body = IMPORT.sub(lambda m: " " * len(m.group(0)), strip(src, keep_javadoc_refs=True))
    pkg = PACKAGE.search(code)
    pkg = pkg.group(1) if pkg else ""
    seen = set()
    for m in IMPORT.finditer(code):
        static, qname, star = m.group(1), m.group(2), m.group(3)
        start = code.rfind("\n", 0, m.start()) + 1
        end = code.find("\n", m.end())
        end = len(src) if end < 0 else end + 1
        key = (bool(static), qname, bool(star))
        if key in seen:
            out.append((start, end, "duplicate", qname, ""))
            continue
        seen.add(key)
        if star:
            continue
        owner, simple = qname.rsplit(".", 1)
        # A qualified use keeps the import: the fix drops the qualifier instead.
        qualified = [] if static else list(re.finditer(
            r"(?<![\w.])" + re.escape(owner) + r"\.(?=" + re.escape(simple) + r"\b)", body))
        if not qualified and not re.search(r"(?<![\w.])" + re.escape(simple) + r"\b", body):
            out.append((start, end, "unused", qname, ""))
        elif not static and owner in ("java.lang", pkg):
            out.append((start, end, "redundant", qname, ""))
        for f in qualified:
            out.append((f.start(), f.end(), "fqn", qname + " is imported", ""))
    return out


def lint(name, src, fix=False):
    found = findings_of(src)
    lines = [f"{name}:{src.count(chr(10), 0, o) + 1}: {k}: {d}" for o, _, k, d, _ in sorted(found)]
    if fix and found:
        for o, e, _, _, r in sorted(set(found), reverse=True):
            src = src[:o] + r + src[e:]
    return lines, src


def patch_new_files(path):
    """Yield (name, source) for every .java file a patch creates."""
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    for part in re.split(r"(?m)^diff --git ", text)[1:]:
        head = re.match(r"a/(\S+) b/(\S+)", part)
        if not head or not head.group(2).endswith(".java") or "\nnew file mode" not in part[:400]:
            continue
        lines = [l[1:] for l in part.split("\n") if l.startswith("+") and not l.startswith("+++")]
        yield f"{path}[{head.group(2)}]", "\n".join(lines) + "\n"


def tracked(roots):
    out = subprocess.run(["git", "-C", ROOT, "ls-files", "--cached", "--others", "--exclude-standard", "--", *roots],
                         capture_output=True, text=True, check=True).stdout.split("\n")
    return [p for p in out if p.endswith((".java", ".patch")) and not p.startswith(SKIP) and os.path.isfile(os.path.join(ROOT, p))]


def main(argv):
    os.chdir(ROOT)
    fix = "--fix" in argv
    files = tracked([a for a in argv if a != "--fix"] or DEFAULT_ROOTS)
    report = []
    for p in files:
        if p.endswith(".patch"):
            for name, src in patch_new_files(p):
                report += lint(name, src)[0]
            continue
        with open(p, encoding="utf-8") as fh:
            src = fh.read()
        lines, fixed = lint(p, src, fix)
        report += lines
        if fixed != src:
            with open(p, "w", encoding="utf-8") as fh:
                fh.write(fixed)
    print("\n".join(report) if report else f"lint-java: OK ({len(files)} files)")
    return 1 if report else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
