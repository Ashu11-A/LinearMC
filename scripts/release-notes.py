#!/usr/bin/env python3
"""Inject the upstream build table into a GitHub release body.

The release workflow runs this after the jars are attached. The matrix comes
from build.yml discover and carries the exact upstream build for every leg,
so the release notes can never drift from the asset suffix.

Idempotent: the block is delimited by HTML comment markers, so a re-run
replaces it instead of appending duplicates.
"""

import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

START = "<!-- linearmc:upstream-builds:start -->"
END = "<!-- linearmc:upstream-builds:end -->"
REPO = os.environ.get("RELEASE_REPO", "Ashu11-A/LinearMC")

SERVER_ORDER = {"canvas": 0, "folia": 1, "horizon": 2}


def label(entry: dict) -> str:
    server = entry.get("server", "")
    if server == "canvas":
        return f"Canvas {entry.get('version', '')}".strip()
    if server == "folia":
        return "Folia"
    if server == "horizon":
        return f"Horizon {entry.get('version', '')}".strip()
    return server or "unknown"


def mc_cell(entry: dict) -> str:
    if entry.get("server") == "horizon":
        return entry.get("mc_range") or "26.x"
    return entry.get("mc", "")


def build_table(matrix: list[dict]) -> str:
    rows = sorted(
        matrix,
        key=lambda e: (
            SERVER_ORDER.get(e.get("server", ""), 9),
            e.get("version", ""),
            e.get("mc", ""),
        ),
    )
    lines = [
        "| Leg | Minecraft | Upstream build | Ref |",
        "| --- | --- | --- | --- |",
    ]
    for entry in rows:
        ref = str(entry.get("ref", ""))[:8]
        lines.append(
            f"| {label(entry)} | {mc_cell(entry)} | "
            f"#{entry.get('build', '?')} | `{ref}` |"
        )
    return "\n".join(lines)


def main() -> int:
    tag = os.environ.get("RELEASE_TAG") or os.environ.get("GITHUB_REF_NAME")
    matrix_json = os.environ.get("RELEASE_MATRIX")
    if not tag:
        print("release-notes: RELEASE_TAG/GITHUB_REF_NAME is empty", file=sys.stderr)
        return 2
    if not matrix_json:
        print("release-notes: RELEASE_MATRIX is empty", file=sys.stderr)
        return 2
    try:
        matrix = json.loads(matrix_json).get("include", [])
    except json.JSONDecodeError as exc:
        print(f"release-notes: invalid RELEASE_MATRIX: {exc}", file=sys.stderr)
        return 2
    if not matrix:
        print("release-notes: matrix has no entries; nothing to do")
        return 0

    table = build_table(matrix)
    block = (
        f"{START}\n"
        "## Upstream builds\n\n"
        f"{table}\n\n"
        "Generated from the pinned `upstream.*.properties` at build time. "
        "The `#N` value is also embedded in each asset name as `+build.N`.\n"
        f"{END}"
    )

    view = subprocess.run(
        ["gh", "release", "view", tag, "--repo", REPO, "--json", "body", "-q", ".body"],
        capture_output=True,
        text=True,
    )
    if view.returncode != 0:
        print(
            f"release-notes: release {tag} not found yet; skipping injection "
            f"({view.stderr.strip()})"
        )
        return 0

    body = view.stdout.rstrip()
    if START in body and END in body:
        body = re.sub(
            re.escape(START) + r".*?" + re.escape(END),
            block,
            body,
            flags=re.DOTALL,
        )
    else:
        body = (body + "\n\n" + block).strip()

    with tempfile.NamedTemporaryFile(
        "w", suffix=".md", delete=False, encoding="utf-8"
    ) as tmp:
        tmp.write(body + "\n")
        notes_path = Path(tmp.name)
    try:
        edit = subprocess.run(
            ["gh", "release", "edit", tag, "--repo", REPO, "--notes-file", str(notes_path)],
            capture_output=True,
            text=True,
        )
        if edit.returncode != 0:
            print(f"release-notes: gh release edit failed: {edit.stderr}", file=sys.stderr)
            return edit.returncode
        print(f"release-notes: injected upstream build table into {tag}")
        return 0
    finally:
        notes_path.unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
