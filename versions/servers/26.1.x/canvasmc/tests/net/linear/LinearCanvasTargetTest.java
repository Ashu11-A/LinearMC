package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Canvas trigger config resolution (Phase 3): {@code LinearStartupConversion}
 * reads only two keys ({@code region-format.format},
 * {@code region-format.compression-level}) from
 * {@code <dimension>/canvas-patch.yml} over the defaults file, ignoring
 * everything else. Pure file logic (no server, no NMS bootstrap).
 */
public class LinearCanvasTargetTest {

    @TempDir
    private Path tempDir;

    private Path write(final String name, final String body) throws Exception {
        final Path file = this.tempDir.resolve(name);
        final Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, body);
        return file;
    }

    private io.papermc.paper.linear.LinearStartupConversion.CanvasTarget read(
        final String defaultsName, final String patchName
    ) {
        final Path defaults = this.tempDir.resolve(defaultsName);
        final Path world = this.tempDir.resolve("world");
        final Path patch = world.resolve(patchName);
        return io.papermc.paper.linear.LinearStartupConversion.readCanvasTarget(world, defaults);
    }

    @Test
    public void missingFilesFallBackToLiveDefaults() {
        final io.papermc.paper.linear.LinearStartupConversion.CanvasTarget target =
            read("no-such-defaults.yml", "no-such-patch.yml");
        assertNotNull(target, "absent files are defaults, not unreadable");
        assertEquals(RegionFileFormat.LINEAR, target.format());
        assertEquals(AbstractRegionFileFactory.DEFAULT_COMPRESSION_LEVEL, target.compressionLevel());
    }

    @Test
    public void explicitAnvilNeverConverts() throws Exception {
        write("defaults.yml", "region-format:\n  format: \"ANVIL\"\n  compression-level: 6\n");
        final io.papermc.paper.linear.LinearStartupConversion.CanvasTarget target =
            read("defaults.yml", "no-such-patch.yml");
        assertEquals(RegionFileFormat.ANVIL, target.format());
    }

    @Test
    public void patchOverridesDefaultsPerKey() throws Exception {
        write("defaults.yml", "region-format:\n  format: \"ANVIL\"\n  compression-level: 6\n");
        write("world/canvas-patch.yml", "region-format:\n  format: \"LINEAR\"\n");
        final io.papermc.paper.linear.LinearStartupConversion.CanvasTarget target =
            read("defaults.yml", "canvas-patch.yml");
        assertEquals(RegionFileFormat.LINEAR, target.format(), "patch format wins");
        assertEquals(6, target.compressionLevel(), "absent patch key keeps base value");
    }

    @Test
    public void garbageFailsClosedToAnvil() throws Exception {
        write("defaults.yml", "region-format:\n  format: [unclosed\n");
        assertNull(read("defaults.yml", "no-such-patch.yml"),
            "unparseable config must fail closed (null = ANVIL, no conversion)");
    }

    @Test
    public void unknownFormatFailsClosedToAnvil() throws Exception {
        write("defaults.yml", "region-format:\n  format: \"BOGUS\"\n");
        final io.papermc.paper.linear.LinearStartupConversion.CanvasTarget target =
            read("defaults.yml", "no-such-patch.yml");
        assertEquals(RegionFileFormat.ANVIL, target.format());
    }

    @Test
    public void levelOutOfRangeFallsBackToDefault() throws Exception {
        write("defaults.yml", "region-format:\n  format: \"LINEAR\"\n  compression-level: 99\n");
        final io.papermc.paper.linear.LinearStartupConversion.CanvasTarget target =
            read("defaults.yml", "no-such-patch.yml");
        assertEquals(RegionFileFormat.LINEAR, target.format());
        assertEquals(AbstractRegionFileFactory.DEFAULT_COMPRESSION_LEVEL, target.compressionLevel());
    }

    @Test
    public void unrelatedKeysIgnored() throws Exception {
        write("defaults.yml", "region-bars:\n  enabled: true\nchunk-system:\n  foo: 1\n");
        final io.papermc.paper.linear.LinearStartupConversion.CanvasTarget target =
            read("defaults.yml", "no-such-patch.yml");
        assertEquals(RegionFileFormat.LINEAR, target.format(), "no region-format section = defaults");
    }

    @Test
    public void emptyPatchFileMeansNoOverrides() throws Exception {
        // Canvas auto-creates empty per-dimension patch files: empty must
        // behave like absent, NOT like garbage (which fails closed).
        write("defaults.yml", "region-format:\n  format: \"LINEAR\"\n  compression-level: 6\n");
        write("world/canvas-patch.yml", "");
        final io.papermc.paper.linear.LinearStartupConversion.CanvasTarget target =
            read("defaults.yml", "canvas-patch.yml");
        assertNotNull(target, "empty patch must not fail closed");
        assertEquals(RegionFileFormat.LINEAR, target.format());
        assertEquals(6, target.compressionLevel());
    }
}
