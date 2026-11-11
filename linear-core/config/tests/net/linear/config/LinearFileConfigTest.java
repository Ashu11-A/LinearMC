package net.linear.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import net.linear.RegionFileFormat;
import org.junit.After;
import org.junit.Test;
import net.linear.LinearImplementation;

/**
 * Cache + no-arg resolution + override-line coverage for the core-owned
 * file layer.
 */
public class LinearFileConfigTest {

    @After
    public void clearState() {
        LinearFileConfig.setFileValues(null, null);
        LinearFileConfig.setFileWriterValues(null, null);
        LinearFileConfig.setFileSymlinkGuard(null);
        LinearFileConfig.setWorldValues(null);
        LinearFileConfig.resetWriterLogForTests();
        System.clearProperty(LinearPolicy.SYSPROP_FORMAT);
        System.clearProperty(LinearPolicy.SYSPROP_IMPLEMENTATION);
        System.clearProperty(LinearPolicy.SYSPROP_GRID_SIZE);
        System.clearProperty(LinearPolicy.SYSPROP_LEVEL);
        System.clearProperty(LinearPolicy.SYSPROP_IMPLEMENTATION);
        System.clearProperty(LinearPolicy.SYSPROP_GRID_SIZE);
    }

    @Test
    public void cacheSetAndGet() {
        assertNull(LinearFileConfig.fileFormat());
        assertNull(LinearFileConfig.fileLevel());
        LinearFileConfig.setFileValues("LINEAR", 3);
        assertEquals("LINEAR", LinearFileConfig.fileFormat());
        assertEquals(Integer.valueOf(3), LinearFileConfig.fileLevel());
        LinearFileConfig.setFileValues(null, null);
        assertNull(LinearFileConfig.fileFormat());
        assertNull(LinearFileConfig.fileLevel());
    }

    @Test
    public void resolvePrecedenceSyspropOverFileOverDefault() {
        // Defaults: ANVIL / 6.
        LinearFileConfig.setFileValues(null, null);
        assertEquals(RegionFileFormat.ANVIL, LinearFileConfig.resolveFormat());
        assertEquals(6, LinearFileConfig.resolveLevel());

        // File layer.
        LinearFileConfig.setFileValues("LINEAR", 9);
        assertEquals(RegionFileFormat.LINEAR, LinearFileConfig.resolveFormat());
        assertEquals(9, LinearFileConfig.resolveLevel());

        // Sysprop beats file.
        System.setProperty(LinearPolicy.SYSPROP_FORMAT, "ANVIL");
        System.setProperty(LinearPolicy.SYSPROP_LEVEL, "3");
        assertEquals(RegionFileFormat.ANVIL, LinearFileConfig.resolveFormat());
        assertEquals(3, LinearFileConfig.resolveLevel());

        // Unknown sysprop falls back to the file value.
        System.setProperty(LinearPolicy.SYSPROP_FORMAT, "BOGUS");
        System.setProperty(LinearPolicy.SYSPROP_LEVEL, "BOGUS");
        assertEquals(RegionFileFormat.LINEAR, LinearFileConfig.resolveFormat());
        assertEquals(9, LinearFileConfig.resolveLevel());

        // Unknown sysprop with no file falls back to the default.
        LinearFileConfig.setFileValues(null, null);
        assertEquals(RegionFileFormat.ANVIL, LinearFileConfig.resolveFormat());
        assertEquals(6, LinearFileConfig.resolveLevel());
    }

    @Test
    public void overrideLinePresentWhenSyspropDiffers() {
        LinearFileConfig.setFileValues("ANVIL", 6);
        System.setProperty(LinearPolicy.SYSPROP_FORMAT, "LINEAR");
        Optional<String> formatLine =
            LinearFileConfig.overrideLineIfNeeded(LinearPolicy.SYSPROP_FORMAT);
        assertTrue(formatLine.isPresent());
        assertEquals(
            LinearPolicy.overrideLine(LinearPolicy.SYSPROP_FORMAT, "LINEAR",
                "ANVIL", RegionFileFormat.LINEAR.name(), "server"),
            formatLine.get());

        System.setProperty(LinearPolicy.SYSPROP_LEVEL, "9");
        Optional<String> levelLine =
            LinearFileConfig.overrideLineIfNeeded(LinearPolicy.SYSPROP_LEVEL);
        assertTrue(levelLine.isPresent());
        assertEquals(
            LinearPolicy.overrideLine(LinearPolicy.SYSPROP_LEVEL, "9",
                "6", String.valueOf(LinearFileConfig.resolveLevel()), "server"),
            levelLine.get());
    }

    @Test
    public void overrideLinesForWriterKnobs() {
        LinearFileConfig.setFileWriterValues("V1", 8);
        System.setProperty(LinearPolicy.SYSPROP_IMPLEMENTATION, "V3");
        System.setProperty(LinearPolicy.SYSPROP_GRID_SIZE, "16");
        assertTrue(LinearFileConfig.overrideLineIfNeeded(LinearPolicy.SYSPROP_IMPLEMENTATION).isPresent());
        assertTrue(LinearFileConfig.overrideLineIfNeeded(LinearPolicy.SYSPROP_GRID_SIZE).isPresent());
        assertTrue(LinearFileConfig.overrideLineIfNeeded(LinearPolicy.SYSPROP_IMPLEMENTATION).get()
            .contains("V3"));
        assertTrue(LinearFileConfig.overrideLineIfNeeded(LinearPolicy.SYSPROP_GRID_SIZE).get().contains("16"));
        assertFalse(LinearFileConfig.overrideLineIfNeeded("linearmc.unknown").isPresent());
    }

    @Test
    public void overrideLineAbsentWhenSameOrUnset() {
        // No sysprop -> absent.
        LinearFileConfig.setFileValues("ANVIL", 6);
        assertFalse(LinearFileConfig
            .overrideLineIfNeeded(LinearPolicy.SYSPROP_FORMAT).isPresent());
        assertFalse(LinearFileConfig
            .overrideLineIfNeeded(LinearPolicy.SYSPROP_LEVEL).isPresent());

        // Sysprop matches the file value (trimmed) -> absent.
        System.setProperty(LinearPolicy.SYSPROP_FORMAT, "ANVIL");
        System.setProperty(LinearPolicy.SYSPROP_LEVEL, "6");
        assertFalse(LinearFileConfig
            .overrideLineIfNeeded(LinearPolicy.SYSPROP_FORMAT).isPresent());
        assertFalse(LinearFileConfig
            .overrideLineIfNeeded(LinearPolicy.SYSPROP_LEVEL).isPresent());

        // Unknown key -> absent even when sysprops are set.
        assertFalse(LinearFileConfig.overrideLineIfNeeded("linearmc.unknown").isPresent());
    }

    @Test
    public void bufferedFormatResolves() {
        assertEquals(RegionFileFormat.B_LINEAR, LinearPolicy.resolveFileFormat("B_LINEAR", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.ANVIL, LinearPolicy.resolveFileFormat("b_linear", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.B_LINEAR,
            LinearPolicy.resolveFormat("B_LINEAR", null, RegionFileFormat.ANVIL));
        LinearFileConfig.setFileValues("B_LINEAR", 6);
        assertEquals(RegionFileFormat.B_LINEAR, LinearFileConfig.resolveFormat());
    }

    @Test
    public void implementationSyspropOverFileOverDefault() {
        assertEquals(LinearImplementation.V1, LinearFileConfig.resolveImplementation());
        LinearFileConfig.setFileWriterValues("V3", null);
        assertEquals(LinearImplementation.V3, LinearFileConfig.resolveImplementation());
        System.setProperty(LinearPolicy.SYSPROP_IMPLEMENTATION, "V2");
        assertEquals(LinearImplementation.V2, LinearFileConfig.resolveImplementation());
        System.setProperty(LinearPolicy.SYSPROP_IMPLEMENTATION, "BOGUS");
        assertEquals(LinearImplementation.V3, LinearFileConfig.resolveImplementation());
    }

    @Test
    public void gridSizeWhitelistAndPrecedence() {
        assertEquals(8, LinearFileConfig.resolveGridSize());
        LinearFileConfig.setFileWriterValues(null, 16);
        assertEquals(16, LinearFileConfig.resolveGridSize());
        LinearFileConfig.setFileWriterValues(null, 7);
        assertEquals(8, LinearFileConfig.resolveGridSize());
        System.setProperty(LinearPolicy.SYSPROP_GRID_SIZE, "4");
        assertEquals(4, LinearFileConfig.resolveGridSize());
        System.setProperty(LinearPolicy.SYSPROP_GRID_SIZE, "9");
        assertEquals(8, LinearFileConfig.resolveGridSize());
    }

    @Test
    public void symlinkGuardDefaultsTrueAndFollowsFile() {
        assertNull(LinearFileConfig.fileSymlinkGuard());
        assertTrue(LinearFileConfig.resolveCrashOnBrokenSymlink());
        LinearFileConfig.setFileSymlinkGuard(Boolean.FALSE);
        assertEquals(Boolean.FALSE, LinearFileConfig.fileSymlinkGuard());
        assertFalse(LinearFileConfig.resolveCrashOnBrokenSymlink());
        LinearFileConfig.setFileSymlinkGuard(Boolean.TRUE);
        assertTrue(LinearFileConfig.resolveCrashOnBrokenSymlink());
    }
    @Test
    public void perWorldValuesOverrideTheGlobalFileValuesByDimensionKey() {
        LinearFileConfig.setFileValues("LINEAR", 6);
        LinearFileConfig.setWorldValues(java.util.Map.of(
            "minecraft:the_nether", new LinearFileConfig.WorldValues("ANVIL", null),
            "minecraft:the_end", new LinearFileConfig.WorldValues(null, 9)));
        final String nether = "/srv/world/dimensions/minecraft/the_nether/region";
        final String end = "/srv/world/dimensions/minecraft/the_end/entities";
        final String overworld = "/srv/world/dimensions/minecraft/overworld/poi";
        assertEquals(RegionFileFormat.ANVIL, LinearFileConfig.resolveFormatFor(nether));
        assertEquals(6, LinearFileConfig.resolveLevelFor(nether));
        assertEquals(RegionFileFormat.LINEAR, LinearFileConfig.resolveFormatFor(end));
        assertEquals(9, LinearFileConfig.resolveLevelFor(end));
        assertEquals(RegionFileFormat.LINEAR, LinearFileConfig.resolveFormatFor(overworld));
        assertEquals(6, LinearFileConfig.resolveLevelFor(overworld));
    }

    @Test
    public void syspropStillWinsOverPerWorldValues() {
        LinearFileConfig.setWorldValues(java.util.Map.of(
            "minecraft:the_nether", new LinearFileConfig.WorldValues("ANVIL", 3)));
        System.setProperty(LinearPolicy.SYSPROP_FORMAT, "LINEAR");
        System.setProperty(LinearPolicy.SYSPROP_LEVEL, "8");
        try {
            final String nether = "/srv/world/dimensions/minecraft/the_nether/region";
            assertEquals(RegionFileFormat.LINEAR, LinearFileConfig.resolveFormatFor(nether));
            assertEquals(8, LinearFileConfig.resolveLevelFor(nether));
        } finally {
            System.clearProperty(LinearPolicy.SYSPROP_LEVEL);
        }
    }

    @Test
    public void pushWriterValuesPublishesValidKeysAndWarnsOnceForBadOnes() {
        final java.util.List<String> warns = new java.util.ArrayList<>();
        final java.util.List<String> infos = new java.util.ArrayList<>();
        LinearFileConfig.pushWriterValues("v2", 16, warns::add, infos::add);
        assertEquals(LinearImplementation.V2, LinearFileConfig.resolveImplementation());
        assertEquals(16, LinearFileConfig.resolveGridSize());
        assertTrue(warns.isEmpty());

        LinearFileConfig.resetWriterLogForTests();
        LinearFileConfig.pushWriterValues("V9", 7, warns::add, infos::add);
        assertEquals(LinearImplementation.V1, LinearFileConfig.resolveImplementation());
        assertEquals(8, LinearFileConfig.resolveGridSize());
        assertEquals(2, warns.size());
        assertTrue(warns.get(0).contains("must be V1, V2 or V3"));
        assertTrue(warns.get(1).contains("must be one of 1, 2, 4, 8, 16, 32"));

        LinearFileConfig.pushWriterValues("V9", 7, warns::add, infos::add);
        assertEquals("bad values warn once per process", 2, warns.size());
    }

    @Test
    public void pushWriterValuesNamesSyspropOverridesOnce() {
        System.setProperty(LinearPolicy.SYSPROP_IMPLEMENTATION, "V3");
        final java.util.List<String> infos = new java.util.ArrayList<>();
        LinearFileConfig.pushWriterValues("V1", 8, null, infos::add);
        assertEquals(LinearImplementation.V3, LinearFileConfig.resolveImplementation());
        assertEquals(1, infos.size());
        assertTrue(infos.get(0).contains(LinearPolicy.SYSPROP_IMPLEMENTATION));
    }
}
