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
}
