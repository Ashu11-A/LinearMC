package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.worldupdate.RegionStorageUpgrader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup auto-conversion NMS half (B2; minecraft-0015).
 *
 * <p>Pure-logic plus {@code TempDir} only: no NMS bootstrap is needed (no
 * chunk/registry access) and no Paper server classes are referenced — the
 * only config type used is the {@code RegionFileFormat} enum, mirroring the
 * {@code LinearDefaultFormatTest} precedent. Target path when integrated:
 * {@code paper-server/src/test/java/net/linear/LinearStartupConversionTest.java}.</p>
 *
 * <p>Placement note: this file pins the NMS side ({@code needsConversion},
 * {@code resolveTargetFormat}, clamp delegation, hook abort signature). The
 * Paper driver half (dimension walk, per-world config read via
 * {@code PaperConfigurations}, fail-closed skip on unreadable config,
 * terminal-alert logging, {@code halt} wiring) cannot run in a unit harness
 * — no harness boots a {@code MinecraftServer} in this tree — and is covered
 * Paper-side by boot-order inspection of the {@code DedicatedServer} call
 * site plus the hook's boolean contract pinned below.</p>
 */
public class LinearStartupConversionTest {

    @TempDir
    private Path tempDir;

    // --- resolveTargetFormat: config-only target resolution ---

    @Test
    public void explicitAnvilNeverConverts() {
        assertEquals(RegionFileFormat.ANVIL,
            RegionStorageUpgrader.resolveTargetFormat(this.tempDir,
                io.papermc.paper.configuration.type.RegionFileFormat.ANVIL),
            "P1 negative pin: explicit-ANVIL worlds must never convert");
    }

    @Test
    public void linearConfigSelectsLinear() {
        assertEquals(RegionFileFormat.LINEAR,
            RegionStorageUpgrader.resolveTargetFormat(this.tempDir,
                io.papermc.paper.configuration.type.RegionFileFormat.LINEAR),
            "only an explicit LINEAR world config selects LINEAR");
    }

    @Test
    public void nullConfigFailsClosedToAnvil() {
        // Null covers unset AND garbage: unknown strings deserialise to null
        // via the shared EnumValueSerializer (no garbage enum constant can
        // exist), and the world-side @PostProcess falls back to ANVIL.
        assertEquals(RegionFileFormat.ANVIL,
            RegionStorageUpgrader.resolveTargetFormat(this.tempDir, null),
            "fail closed: null/unreadable config must never convert");
    }

    // --- needsConversion filename edges ---

    private Path freshFolder(final String name) throws Exception {
        final Path folder = this.tempDir.resolve(name);
        Files.createDirectories(folder);
        return folder;
    }

    @Test
    public void detectsCanonicalMca() throws Exception {
        final Path folder = freshFolder("region-hit");
        Files.write(folder.resolve("r.0.0.mca"), new byte[]{0});
        assertTrue(RegionStorageUpgrader.needsConversion(folder),
            "canonical r.0.0.mca must trigger conversion");
    }

    @Test
    public void detectsNegativeCoordinates() throws Exception {
        final Path folder = freshFolder("region-neg");
        Files.write(folder.resolve("r.1.-2.mca"), new byte[]{0});
        assertTrue(RegionStorageUpgrader.needsConversion(folder),
            "negative region coordinates must trigger conversion");
    }

    @Test
    public void ignoresBackupUppercaseAndLevelDat() throws Exception {
        final Path folder = freshFolder("region-miss");
        Files.write(folder.resolve("r.0.0.mca.bak"), new byte[]{0});
        Files.write(folder.resolve("R.0.0.MCA"), new byte[]{0});
        Files.write(folder.resolve("level.dat"), new byte[]{0});
        assertFalse(RegionStorageUpgrader.needsConversion(folder),
            "backups, uppercase names and level.dat must not trigger conversion");
    }

    @Test
    public void ignoresLinearOnlyEmptyAndMissing() throws Exception {
        final Path linearOnly = freshFolder("region-linear");
        Files.write(linearOnly.resolve("r.0.0.linear"), new byte[]{0});
        assertFalse(RegionStorageUpgrader.needsConversion(linearOnly),
            "already-converted folders must not re-trigger");
        assertFalse(RegionStorageUpgrader.needsConversion(freshFolder("region-empty")),
            "empty folders must not trigger conversion");
        assertFalse(RegionStorageUpgrader.needsConversion(this.tempDir.resolve("does-not-exist")),
            "missing folders (null listing) must fail closed");
    }

    @Test
    public void perFolderGating() throws Exception {
        final Path world = freshFolder("world");
        final Path region = Files.createDirectories(world.resolve("region"));
        final Path entities = Files.createDirectories(world.resolve("entities"));
        final Path poi = Files.createDirectories(world.resolve("poi"));
        Files.write(region.resolve("r.1.-2.mca"), new byte[]{0});
        Files.write(poi.resolve("r.0.0.mca"), new byte[]{0});
        assertTrue(RegionStorageUpgrader.needsConversion(region), "region/ converts");
        assertFalse(RegionStorageUpgrader.needsConversion(entities), "entities/ without .mca is skipped");
        assertTrue(RegionStorageUpgrader.needsConversion(poi), "poi/ converts");
    }

    // --- F6: compression clamp is a single source of truth ---

    @Test
    public void clampIsSingleSourceOfTruth() {
        for (int level = 1; level <= AbstractRegionFileFactory.MAX_COMPRESSION_LEVEL; level++) {
            assertEquals(level, AbstractRegionFileFactory.clampCompressionLevel(level),
                "in-range level passes through");
        }
        assertEquals(AbstractRegionFileFactory.DEFAULT_COMPRESSION_LEVEL,
            AbstractRegionFileFactory.clampCompressionLevel(0), "0 falls back to default");
        assertEquals(AbstractRegionFileFactory.DEFAULT_COMPRESSION_LEVEL,
            AbstractRegionFileFactory.clampCompressionLevel(
                AbstractRegionFileFactory.MAX_COMPRESSION_LEVEL + 1),
            "above-max falls back to default");
    }

    // --- HIGH-4: halt-aborts-boot contract (signature pin, no server boot) ---

    @Test
    public void hookReturnsBooleanAbortSignal() throws Exception {
        // Contract pin without booting a server (and without invoking halt):
        // the hook must return boolean so the DedicatedServer call site can
        // abort initServer when a protection trip halts the server.
        final Class<?> hook = Class.forName("io.papermc.paper.linear.LinearStartupConversion");
        assertEquals(boolean.class,
            hook.getMethod("runPrePluginConversion", MinecraftServer.class).getReturnType(),
            "HIGH-4: runPrePluginConversion must return boolean (false = boot must abort)");
    }
}
