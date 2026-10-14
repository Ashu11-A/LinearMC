package net.minecraft.world.level.chunk.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Broken-symlink guard fixture.
 *
 * <p>The guard must compare the format enum by identity:
 * {@code if (this.format != RegionFileFormat.LINEAR) return;}. Comparing an
 * enum against the String {@code "LINEAR"} via {@code equals} is always
 * false, so the condition held on every call and broken Linear symlinks
 * were never detected.
 *
 * <p>Fixed form: {@code if (this.format != RegionFileFormat.LINEAR) return;}
 * (enum identity). The guard method itself lives in {@code RegionFileStorage};
 * this test mirrors the FIXED guard body with a local enum of identical
 * shape, plus a real broken-symlink fixture on disk, so the
 * symlink-detection logic (plain JDK, no server needed) is verified directly:
 *
 * <pre>
 *   if (!crashOnBrokenSymlink) return;
 *   if (format != LINEAR) return;                          // identity check
 *   if (!Files.isSymbolicLink(path)) return;
 *   Path link = Files.readSymbolicLink(path);
 *   if (!Files.exists(link) || !Files.isReadable(link)) throw new IOException(...);
 * </pre>
 *
 * <p>NOTE: the production guard also halts the server before throwing. The
 * test helper deliberately omits the halt (it would kill the test JVM) and
 * asserts only the {@link IOException} path.
 *
 * <p>Target path when integrated: the server {@code src/test/java} tree (mirrors
 * the JUnit-Jupiter layout used by the other storage tests).
 */
public class BrokenSymlinkGuardTest {

    /** Local stand-in for the production format enum (ANVIL, LINEAR). */
    private enum Format {
        ANVIL, LINEAR
    }

    @TempDir
    private Path tempDir;

    // --- Fixture: symlink detection body (mirrors fixed guard, minus halt) ---

    private static boolean isBrokenSymlinkTarget(Path path) throws IOException {
        if (!Files.isSymbolicLink(path)) {
            return false;
        }
        Path link = Files.readSymbolicLink(path);
        return !Files.exists(link) || !Files.isReadable(link);
    }

    /** Fixed guard logic. Throws on broken symlink only when LINEAR + flag set. */
    private static void fixedGuard(Path path, Format format, boolean crashOnBrokenSymlink) throws IOException {
        if (!crashOnBrokenSymlink) {
            return;
        }
        if (format != Format.LINEAR) {
            return;
        }
        if (!Files.isSymbolicLink(path)) {
            return;
        }
        Path link = Files.readSymbolicLink(path);
        if (!Files.exists(link) || !Files.isReadable(link)) {
            throw new IOException("Linear region file " + path + " is a broken symbolic link, crashing to prevent data loss");
        }
    }

    /** Buggy guard logic: enum .equals(String) is always false -> always returns. */
    private static void buggyGuard(Path path, Format format, boolean crashOnBrokenSymlink) throws IOException {
        if (!crashOnBrokenSymlink) {
            return;
        }
        if (!format.equals("LINEAR")) { // BUG: enum vs String, always true -> dead code below
            return;
        }
        if (!Files.isSymbolicLink(path)) {
            return;
        }
        Path link = Files.readSymbolicLink(path);
        if (!Files.exists(link) || !Files.isReadable(link)) {
            throw new IOException("Linear region file " + path + " is a broken symbolic link, crashing to prevent data loss");
        }
    }

    @Test
    public void buggyEnumEqualsStringIsAlwaysTrue() {
        // Pin the root cause: no enum instance ever .equals() the String "LINEAR".
        for (Format f : Format.values()) {
            assertTrue(f.equals("LINEAR") == false, "enum must never equal String LINEAR, but guard relied on it");
            assertTrue(!f.equals("LINEAR"), "buggy guard early-returns for " + f);
        }
        // ...while identity comparison discriminates correctly.
        assertTrue(Format.LINEAR == Format.LINEAR);
        assertTrue(Format.ANVIL != Format.LINEAR);
    }

    @Test
    public void brokenSymlinkFixtureIsDetected() throws IOException {
        Path target = tempDir.resolve("real-target.bin");
        Files.write(target, new byte[]{1, 2, 3});

        Path validLink = tempDir.resolve("r.0.0.linear");
        Path brokenLink = tempDir.resolve("r.0.1.linear");
        Files.createSymbolicLink(validLink, target);
        Files.createSymbolicLink(brokenLink, tempDir.resolve("does-not-exist.bin"));

        assertTrue(Files.isSymbolicLink(validLink));
        assertTrue(Files.isSymbolicLink(brokenLink));

        assertFalse(isBrokenSymlinkTarget(target), "regular file is not a broken symlink");
        assertFalse(isBrokenSymlinkTarget(validLink), "valid symlink is not broken");
        assertTrue(isBrokenSymlinkTarget(brokenLink), "dangling symlink must be detected");
    }

    @Test
    public void fixedGuardThrowsForLinearBrokenSymlink() throws IOException {
        Path brokenLink = tempDir.resolve("r.1.1.linear");
        Files.createSymbolicLink(brokenLink, tempDir.resolve("missing.bin"));

        IOException ex = assertThrows(
            IOException.class,
            () -> fixedGuard(brokenLink, Format.LINEAR, true),
            "LINEAR + flag + broken link must throw"
        );
        assertTrue(ex.getMessage().contains("broken symbolic link"));
    }

    @Test
    public void fixedGuardIgnoresNonLinearAndDisabledFlag() throws IOException {
        Path brokenLink = tempDir.resolve("r.2.2.linear");
        Files.createSymbolicLink(brokenLink, tempDir.resolve("missing2.bin"));

        // ANVIL format: guard must no-op even on a broken link.
        assertDoesNotThrow(() -> fixedGuard(brokenLink, Format.ANVIL, true));
        // Disabled flag: guard must no-op even for LINEAR.
        assertDoesNotThrow(() -> fixedGuard(brokenLink, Format.LINEAR, false));
    }

    @Test
    public void fixedGuardPassesForHealthyLinks() throws IOException {
        Path target = tempDir.resolve("healthy.bin");
        Files.write(target, new byte[]{9});
        Path validLink = tempDir.resolve("r.3.3.linear");
        Files.createSymbolicLink(validLink, target);

        assertDoesNotThrow(() -> fixedGuard(validLink, Format.LINEAR, true));
        assertDoesNotThrow(() -> fixedGuard(target, Format.LINEAR, true));
    }

    @Test
    public void buggyGuardNeverThrowsEvenForLinearBrokenSymlink() throws IOException {
        Path brokenLink = tempDir.resolve("r.4.4.linear");
        Files.createSymbolicLink(brokenLink, tempDir.resolve("missing3.bin"));

        // Demonstrates the defect: the buggy guard stays silent.
        assertDoesNotThrow(() -> buggyGuard(brokenLink, Format.LINEAR, true));
    }
}
