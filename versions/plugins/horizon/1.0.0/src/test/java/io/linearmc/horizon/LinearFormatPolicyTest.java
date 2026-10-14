package io.linearmc.horizon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.linear.RegionFileFormat;
import net.linear.config.LinearFormatOverride;
import net.linear.config.LinearPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * File-layer wiring: sysprop wins over config.yml, which wins over the
 * shipped fail-open defaults. NMS-free: runs on plain JUnit.
 */
public class LinearFormatPolicyTest {

    @AfterEach
    public void clearFileValues() {
        LinearFormatPolicy.setFileValues(null, null);
        LinearFormatPolicy.setFileWriterValues(null, null);
        System.clearProperty(LinearPolicy.SYSPROP_FORMAT);
        System.clearProperty(LinearPolicy.SYSPROP_LEVEL);
        System.clearProperty(LinearPolicy.SYSPROP_IMPLEMENTATION);
        System.clearProperty(LinearPolicy.SYSPROP_GRID_SIZE);
        LinearFormatOverride.clear("world");
        LinearFormatOverride.clear("/srv/world/region");
    }

    @Test
    public void fileValuesAreExposedRaw() {
        assertNull(LinearFormatPolicy.fileFormat());
        assertNull(LinearFormatPolicy.fileLevel());
        LinearFormatPolicy.setFileValues("LINEAR", 3);
        assertEquals("LINEAR", LinearFormatPolicy.fileFormat());
        assertEquals(3, LinearFormatPolicy.fileLevel());
    }

    @Test
    public void syspropBeatsFileBeatsDefault() {
        assertEquals(RegionFileFormat.ANVIL, LinearFormatPolicy.resolveFormat(
            null, null, LinearPolicy.FALLBACK_FORMAT));
        assertEquals(RegionFileFormat.LINEAR, LinearFormatPolicy.resolveFormat(
            null, "LINEAR", LinearPolicy.FALLBACK_FORMAT));
        assertEquals(RegionFileFormat.LINEAR, LinearFormatPolicy.resolveFormat(
            "LINEAR", "ANVIL", LinearPolicy.FALLBACK_FORMAT));
        assertEquals(RegionFileFormat.ANVIL, LinearFormatPolicy.resolveFormat(
            null, "bogus", LinearPolicy.FALLBACK_FORMAT));
    }

    @Test
    public void effectiveFormatHonoursFolderOverride() {
        LinearFormatPolicy.setFileValues("LINEAR", 6);
        // No override: file value flows through.
        assertEquals(RegionFileFormat.LINEAR, LinearFormatPolicy.resolveEffectiveFormat(
            LinearFormatPolicy.fileFormat(), "/srv/world/region", "world"));
        // Folder-scoped override beats the file value (reverse jobs).
        LinearFormatOverride.set("/srv/world/region", "ANVIL");
        assertEquals(RegionFileFormat.ANVIL, LinearFormatPolicy.resolveEffectiveFormat(
            LinearFormatPolicy.fileFormat(), "/srv/world/region", "world"));
        // Unknown scope falls back to the file value.
        assertEquals(RegionFileFormat.LINEAR, LinearFormatPolicy.resolveEffectiveFormat(
            LinearFormatPolicy.fileFormat(), "/srv/other/region", "other"));
    }

    @Test
    public void levelFallsBackToSix() {
        assertEquals(6, LinearFormatPolicy.resolveLevel(
            null, null, LinearFormatPolicy.FALLBACK_LEVEL));
        assertEquals(3, LinearFormatPolicy.resolveLevel(
            null, 3, LinearFormatPolicy.FALLBACK_LEVEL));
        assertEquals(9, LinearFormatPolicy.resolveLevel(
            "9", 3, LinearFormatPolicy.FALLBACK_LEVEL));
        assertEquals(3, LinearFormatPolicy.resolveLevel(
            "bogus", 3, LinearFormatPolicy.FALLBACK_LEVEL));
    }

    @Test
    public void bufferedFormatResolves() {
        assertEquals(RegionFileFormat.B_LINEAR, LinearFormatPolicy.resolveFormat("B_LINEAR"));
        assertEquals(RegionFileFormat.B_LINEAR, LinearPolicy.resolveFormat("B_LINEAR", null,
            LinearPolicy.FALLBACK_FORMAT));
        assertEquals(true, RegionFileFormat.isLinearFamily(RegionFileFormat.B_LINEAR));
    }

    @Test
    public void writerValuesResolve() {
        assertEquals(net.linear.LinearImplementation.V1, LinearFormatPolicy.resolveImplementation());
        assertEquals(8, LinearFormatPolicy.resolveGridSize());
        LinearFormatPolicy.setFileWriterValues("V3", 16);
        assertEquals(net.linear.LinearImplementation.V3, LinearFormatPolicy.resolveImplementation());
        assertEquals(16, LinearFormatPolicy.resolveGridSize());
        LinearFormatPolicy.setFileWriterValues("bogus", 7);
        assertEquals(net.linear.LinearImplementation.V1, LinearFormatPolicy.resolveImplementation());
        assertEquals(8, LinearFormatPolicy.resolveGridSize());
        LinearFormatPolicy.setFileWriterValues(null, null);
    }

    @Test
    public void flushSeamMappingMatchesDocsDefaults() {
        int ncpu = Runtime.getRuntime().availableProcessors();
        // Shipped config.yml values pass through unchanged.
        assertEquals(1, LinearFormatPolicy.resolveFlushThreads(1, ncpu));
        assertEquals(10, LinearFormatPolicy.resolveFlushFrequency(10));
        assertEquals(0, LinearFormatPolicy.resolveCompressionWorkers(0));
        assertEquals(0, LinearFormatPolicy.resolveLongDistanceMatching(0));
        // Bad file values fall back exactly like the fork config layer.
        assertEquals(10, LinearFormatPolicy.resolveFlushFrequency(0));
        assertEquals(10, LinearFormatPolicy.resolveFlushFrequency(-3));
        assertEquals(0, LinearFormatPolicy.resolveCompressionWorkers(-1));
        assertEquals(0, LinearFormatPolicy.resolveLongDistanceMatching(-1));
        assertEquals(1, LinearFormatPolicy.resolveFlushThreads(0, ncpu));
        // Kaiiju relative threads: negative means ncpu plus the value.
        assertEquals(Math.max(ncpu - 1, 1), LinearFormatPolicy.resolveFlushThreads(-1, ncpu));
        // Healthy values pass through.
        assertEquals(4, LinearFormatPolicy.resolveFlushThreads(4, ncpu));
        assertEquals(30, LinearFormatPolicy.resolveFlushFrequency(30));
        assertEquals(2, LinearFormatPolicy.resolveCompressionWorkers(2));
        assertEquals(12, LinearFormatPolicy.resolveLongDistanceMatching(12));
    }
}
