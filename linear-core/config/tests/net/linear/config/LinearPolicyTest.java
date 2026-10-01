package net.linear.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.linear.RegionFileFormat;
import org.junit.Test;

/**
 * CorePolicyTest-style coverage for the unified sysprop &gt; file &gt;
 * default policy.
 */
public class LinearPolicyTest {

    @Test
    public void syspropBeatsFile() {
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFormat("LINEAR", "ANVIL", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.ANVIL,
                LinearPolicy.resolveFormat("anvil", "LINEAR", RegionFileFormat.LINEAR));
        assertEquals(9, LinearPolicy.resolveLevel("9", 3, 6));
    }

    @Test
    public void unknownSyspropFallsToFileValue() {
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFormat("BOGUS", "LINEAR", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.ANVIL,
                LinearPolicy.resolveFormat("BOGUS", "ANVIL", RegionFileFormat.LINEAR));
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFormat("BOGUS", "linear", RegionFileFormat.LINEAR));
        assertEquals(12, LinearPolicy.resolveLevel("BOGUS", 12, 6));
        assertEquals(12, LinearPolicy.resolveLevel("99", 12, 6));
        assertEquals(12, LinearPolicy.resolveLevel("0", 12, 6));
        assertEquals(6, LinearPolicy.resolveLevel("BOGUS", null, 6));
        assertEquals(6, LinearPolicy.resolveLevel("99", 99, 6));
    }

    @Test
    public void blankSyspropFallsThroughToFileLikeBogus() {
        // Blank is present-but-bad: falls through to the file layer like
        // BOGUS, never straight to the default (live override/file still wins).
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFormat("", "LINEAR", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFormat("   ", "LINEAR", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFormat("   ", "BOGUS", RegionFileFormat.LINEAR));
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFormat(null, "LINEAR", RegionFileFormat.ANVIL));
        // Blank with no file still yields the default.
        assertEquals(RegionFileFormat.ANVIL,
                LinearPolicy.resolveFormat("", null, RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.ANVIL,
                LinearPolicy.resolveFormat("   ", "BOGUS", RegionFileFormat.ANVIL));
        assertEquals(12, LinearPolicy.resolveLevel("", 12, 6));
        assertEquals(12, LinearPolicy.resolveLevel("   ", 12, 6));
        assertEquals(6, LinearPolicy.resolveLevel("", null, 6));
        assertEquals(12, LinearPolicy.resolveLevel(null, 12, 6));
    }

    @Test
    public void blankSyspropFallsThroughToLiveOverride() {
        LinearFormatOverride.resetForTests();
        try {
            LinearFormatOverride.set("world", "ANVIL");
            // BOGUS already falls to the override; blank must match it.
            assertEquals(RegionFileFormat.ANVIL,
                    LinearPolicy.resolveFormatWithOverride("BOGUS", "LINEAR", RegionFileFormat.LINEAR, "world"));
            assertEquals(RegionFileFormat.ANVIL,
                    LinearPolicy.resolveFormatWithOverride("", "LINEAR", RegionFileFormat.LINEAR, "world"));
            assertEquals(RegionFileFormat.ANVIL,
                    LinearPolicy.resolveFormatWithOverride("   ", "LINEAR", RegionFileFormat.LINEAR, "world"));
            // Blank level falls to the file value too.
            assertEquals(12, LinearPolicy.resolveLevel("", 12, 6));
            assertEquals(12, LinearPolicy.resolveLevel("   ", 12, 6));
        } finally {
            LinearFormatOverride.resetForTests();
        }
    }

    @Test
    public void fileLayerIsCaseSensitive() {
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFileFormat("LINEAR", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.ANVIL,
                LinearPolicy.resolveFileFormat("ANVIL", RegionFileFormat.LINEAR));
        assertEquals(RegionFileFormat.ANVIL,
                LinearPolicy.resolveFileFormat("linear", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFileFormat("linear", RegionFileFormat.LINEAR));
        assertEquals(RegionFileFormat.ANVIL,
                LinearPolicy.resolveFileFormat(null, RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.ANVIL,
                LinearPolicy.resolveFileFormat("BOGUS", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFileFormat(" LINEAR ", RegionFileFormat.ANVIL));
        // Full composition: sysprop absent goes through the file layer.
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFormat(null, null, RegionFileFormat.LINEAR));
        assertEquals(RegionFileFormat.ANVIL,
                LinearPolicy.resolveFormat(null, null, RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFormat(null, "linear", RegionFileFormat.LINEAR));
        assertEquals(RegionFileFormat.LINEAR,
                LinearPolicy.resolveFormat("linear", "ANVIL", RegionFileFormat.ANVIL));
    }

    @Test
    public void syspropRejectedMessage() {
        assertEquals("[region-format] Ignoring invalid sysprop linearmc.format=BOGUS, using file value.",
                LinearPolicy.syspropRejected("linearmc.format", "BOGUS"));
    }

    @Test
    public void resolveFlagUsesFileOrDefault() {
        assertTrue(LinearPolicy.resolveFlag(null, true, false));
        assertFalse(LinearPolicy.resolveFlag(null, false, true));
        assertTrue(LinearPolicy.resolveFlag(null, null, true));
        assertFalse(LinearPolicy.resolveFlag(null, null, false));
        // Sysprop reserved for forward-compat: accepted but ignored.
        assertTrue(LinearPolicy.resolveFlag("true", null, true));
        assertFalse(LinearPolicy.resolveFlag("true", false, true));
        assertTrue(LinearPolicy.resolveFlag("false", true, false));
        // Shipped defaults.
        assertTrue(LinearPolicy.resolveFlag(null, null,
                LinearPolicy.DEFAULT_CRASH_ON_BROKEN_SYMLINK));
        assertFalse(LinearPolicy.resolveFlag(null, null,
                LinearPolicy.DEFAULT_LOG_FLUSH_BATCHES));
    }

    @Test
    public void levelOutOfRangeOrNaNFallsToDefault() {
        assertEquals(6, LinearPolicy.resolveLevel("0", null, 6));
        assertEquals(6, LinearPolicy.resolveLevel("23", null, 6));
        assertEquals(6, LinearPolicy.resolveLevel("-3", null, 6));
        assertEquals(6, LinearPolicy.resolveLevel("fast", null, 6));
        assertEquals(6, LinearPolicy.resolveLevel("", null, 6));
        assertEquals(6, LinearPolicy.resolveLevel(null, 0, 6));
        assertEquals(6, LinearPolicy.resolveLevel(null, 99, 6));
        assertEquals(6, LinearPolicy.resolveLevel(null, null, 6));
        assertEquals(1, LinearPolicy.resolveLevel("1", null, 6));
        assertEquals(22, LinearPolicy.resolveLevel("22", null, 6));
        assertEquals(22, LinearPolicy.resolveLevel(" 22 ", null, 6));
        assertEquals(22, LinearPolicy.resolveLevel(null, 22, 6));
    }

    @Test
    public void frequencyThreadsWorkersClamps() {
        assertEquals(10, LinearPolicy.clampFrequency(0));
        assertEquals(10, LinearPolicy.clampFrequency(-5));
        assertEquals(10, LinearPolicy.clampFrequency(10));
        assertEquals(30, LinearPolicy.clampFrequency(30));
        assertEquals(Integer.MAX_VALUE, LinearPolicy.clampFrequency(Long.MAX_VALUE));

        int ncpu = Runtime.getRuntime().availableProcessors();
        assertEquals(Math.max(ncpu - 1, 1), LinearPolicy.resolveThreads(-1, ncpu));
        assertEquals(1, LinearPolicy.resolveThreads(0, ncpu));
        assertEquals(1, LinearPolicy.resolveThreads(1, ncpu));
        assertEquals(4, LinearPolicy.resolveThreads(4, ncpu));
        assertEquals(1, LinearPolicy.resolveThreads(-ncpu - 10, ncpu));
        assertEquals(4, LinearPolicy.resolveThreads(4, 0));
        assertEquals(4, LinearPolicy.resolveThreads(4, -2));
        assertEquals(1, LinearPolicy.resolveThreads(-1, 0));

        assertEquals(0, LinearPolicy.clampNonNegative(-1, 0));
        assertEquals(0, LinearPolicy.clampNonNegative(0, 0));
        assertEquals(3, LinearPolicy.clampNonNegative(3, 0));
        assertEquals(10, LinearPolicy.clampNonNegative(-2, 10));

        // Defaults match the shipped config.
        assertEquals(RegionFileFormat.LINEAR, LinearPolicy.DEFAULT_FORMAT);
        assertEquals(RegionFileFormat.ANVIL, LinearPolicy.FALLBACK_FORMAT);
        assertEquals(6, LinearPolicy.DEFAULT_LEVEL);
        assertEquals(10, LinearPolicy.DEFAULT_FLUSH_FREQUENCY);
        assertEquals(1, LinearPolicy.DEFAULT_FLUSH_THREADS);
        assertEquals(0, LinearPolicy.DEFAULT_WORKERS);
        assertEquals(0, LinearPolicy.DEFAULT_LDM);
    }

    @Test
    public void overrideWinsOverFileSyspropWinsOverall() {
        LinearFormatOverride.resetForTests();
        try {
            // No override: file value flows through.
            assertEquals(RegionFileFormat.LINEAR,
                    LinearPolicy.resolveFormatWithOverride(null, "LINEAR", RegionFileFormat.ANVIL, "world"));
            // Override beats the file value.
            LinearFormatOverride.set("world", "ANVIL");
            assertEquals(RegionFileFormat.ANVIL,
                    LinearPolicy.resolveFormatWithOverride(null, "LINEAR", RegionFileFormat.ANVIL, "world"));
            // First non-null scope wins (folder path before world name).
            LinearFormatOverride.set("/srv/world/region", "LINEAR");
            assertEquals(RegionFileFormat.LINEAR,
                    LinearPolicy.resolveFormatWithOverride(null, "ANVIL", RegionFileFormat.ANVIL,
                        "/srv/world/region", "world"));
            // Sysprop still wins over the override.
            assertEquals(RegionFileFormat.LINEAR,
                    LinearPolicy.resolveFormatWithOverride("LINEAR", "ANVIL", RegionFileFormat.ANVIL, "world"));
            // Unknown sysprop falls back to the override (not straight to default).
            assertEquals(RegionFileFormat.ANVIL,
                    LinearPolicy.resolveFormatWithOverride("BOGUS", "LINEAR", RegionFileFormat.LINEAR, "world"));
            // Null/empty scopes behave like no override.
            assertEquals(RegionFileFormat.LINEAR,
                    LinearPolicy.resolveFormatWithOverride(null, "LINEAR", RegionFileFormat.ANVIL,
                        (String) null));
            assertEquals(RegionFileFormat.LINEAR,
                    LinearPolicy.resolveFormatWithOverride(null, "LINEAR", RegionFileFormat.ANVIL));
        } finally {
            LinearFormatOverride.resetForTests();
        }
    }

    @Test
    public void messageFormatsExact() {        assertEquals("[region-format] sysprop linearmc.format=LINEAR overrides file ANVIL -> effective LINEAR (server)",
                LinearPolicy.overrideLine("linearmc.format", "LINEAR", "ANVIL", "LINEAR", "server"));
        assertEquals("[region-format] Unknown region format, expected ANVIL or LINEAR. Falling back to ANVIL.",
                LinearPolicy.unknownFormatMessage());
        assertEquals("[region-format] linear.compression-level must be 1-22, got 99. Falling back to 6.",
                LinearPolicy.badLevelMessage(99));
        assertEquals("[region-format] linear.flush-frequency must be >= 1, got 0. Falling back to 10.",
                LinearPolicy.badFrequencyMessage(0));
        assertEquals("[region-format] linear.compression-workers must be >= 0, got -1. Falling back to 0.",
                LinearPolicy.badWorkersMessage(-1));
        assertEquals("[region-format] linear.long-distance-matching must be >= 0, got -2. Falling back to 0.",
                LinearPolicy.badLdmMessage(-2));
    }
}
