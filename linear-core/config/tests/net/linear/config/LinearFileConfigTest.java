package net.linear.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import net.linear.RegionFileFormat;
import org.junit.After;
import org.junit.Test;

/**
 * Cache + no-arg resolution + override-line coverage for the core-owned
 * file layer.
 */
public class LinearFileConfigTest {

    @After
    public void clearState() {
        LinearFileConfig.setFileValues(null, null);
        System.clearProperty(LinearPolicy.SYSPROP_FORMAT);
        System.clearProperty(LinearPolicy.SYSPROP_LEVEL);
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
}
