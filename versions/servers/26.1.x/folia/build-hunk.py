#!/usr/bin/env python3
"""Augment Folia's build.gradle.kts.patch with the Linear build wiring.

Two insertions, both byte-identical to the proven local build:
1. Test source dir: lets the new net.linear/net.minecraft tests compile
   (`srcDir("src/test/java")` alongside the paper-server test tree).
2. zstd-dep hunk: paperweight-convention @@ header stock git cannot parse,
   so it is spliced in (never git-applied).

Fails loudly if anchors moved (upstream drift) or already applied (idempotent).
"""
import pathlib
import sys

ANCHOR = "@@ -171,14 +_,14 @@"
MARKER = 'implementation("com.github.luben:zstd-jni:1.5.6-8")'

TEST_SRCDIR_OLD = 'java { srcDir("../paper-server/src/test/java") }'
TEST_SRCDIR_NEW = 'java { srcDir("../paper-server/src/test/java"); srcDir("src/test/java") }'

HUNK = """@@ -157,6 +_,11 @@ dependencies {
     // Spark
     implementation("me.lucko:spark-api:0.1-20240720.200737-2")
     implementation("me.lucko:spark-paper:1.10.152")
+
+    // folia-linear A6 (codec/native owner): zstd chunk codec. Pinned to the proven
+    // Linear plugin classpath (packages/core uses 1.5.6-8); the fork pins no zstd
+    // version itself and lz4 arrives via vanilla, so no lz4 line is added here.
+    implementation("com.github.luben:zstd-jni:1.5.6-8")
 }
 
 tasks.jar {
"""


def main() -> int:
    target = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else None
    if target is None:
        print("usage: apply-deps-hunk.py <folia-server/build.gradle.kts.patch>")
        return 2
    text = target.read_text()
    if MARKER in text:
        print("zstd hunk already applied")
    else:
        if text.count(ANCHOR) != 1:
            print(f"ABORT: anchor {ANCHOR!r} found {text.count(ANCHOR)}x (expected 1x) -- "
                  "upstream moved, update the hunk placement by hand")
            return 1
        text = text.replace(ANCHOR, HUNK.rstrip("\n") + "\n" + ANCHOR, 1)
    if TEST_SRCDIR_NEW in text:
        print("test srcDir already wired")
    else:
        if text.count(TEST_SRCDIR_OLD) != 1:
            print(f"ABORT: test srcDir anchor found {text.count(TEST_SRCDIR_OLD)}x "
                  "(expected 1x) -- upstream moved")
            return 1
        text = text.replace(TEST_SRCDIR_OLD, TEST_SRCDIR_NEW, 1)
    target.write_text(text)
    check = target.read_text()
    assert check.count(MARKER) == 1, "post-insert marker check failed"
    assert check.count(TEST_SRCDIR_NEW) == 1, "post-insert srcdir check failed"
    print(f"patched {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
