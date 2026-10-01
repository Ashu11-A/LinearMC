package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import io.canvasmc.canvas.GlobalConfiguration;
import io.canvasmc.canvas.WorldConfig;

import org.junit.jupiter.api.Test;

/**
 * Canvas-native Linear config defaults (Part system).
 *
 * <p>Canvas port of the Folia {@code LinearGlobalConfigTest}: where Folia
 * nests Linear keys under Paper's {@code GlobalConfiguration.RegionFormat}
 * with {@code @PostProcess} clamps, Canvas declares them as
 * {@code io.canvasmc.canvas.GlobalConfiguration.Linear} and
 * {@code io.canvasmc.canvas.WorldConfig.RegionFormat} Parts with plain
 * field defaults. No server boot is needed: Part construction is
 * side-effect free (in-memory option maps only).</p>
 *
 * <p>Verifies defaults preserve today's behaviour (serial flush, no
 * workers, LDM off, log off, LINEAR format for new writes, level 6,
 * symlink guard on).</p>
 */
public class LinearGlobalConfigTest {

    @Test
    public void globalDefaultsPreserveTodaysBehavior() {
        GlobalConfiguration.Linear linear = new GlobalConfiguration.Linear();
        assertEquals(10L, linear.flushFrequency, "flush-frequency default 10");
        assertEquals(1, linear.flushMaxThreads, "flush-max-threads default 1 (serial)");
        assertEquals(0, linear.compressionWorkers, "compression-workers default 0 (inert)");
        assertEquals(0, linear.longDistanceMatching, "long-distance-matching default 0 (off)");
        assertFalse(linear.logFlushBatches, "log-flush-batches default false");
    }

    @Test
    public void worldDefaultsSelectLinearSafely() {
        WorldConfig.RegionFormat regionFormat = new WorldConfig.RegionFormat();
        assertEquals(RegionFileFormat.LINEAR, regionFormat.format,
            "new writes default to LINEAR; reads probe both extensions so flipping is safe");
        assertEquals(6, regionFormat.compressionLevel, "compression-level default 6");
        assertTrue(regionFormat.crashOnBrokenSymlink, "symlink guard defaults on (fail closed)");
    }

    @Test
    public void configSurfacePinned() throws Exception {
        // Pins the Linear config surface against accidental renames: every
        // key the NMS layer reads must exist with the exact field name.
        for (String name : new String[]{
            "flushFrequency", "flushMaxThreads",
            "compressionWorkers", "longDistanceMatching", "logFlushBatches"}) {
            assertNotNull(GlobalConfiguration.Linear.class.getDeclaredField(name),
                "GlobalConfiguration.Linear must declare " + name);
        }
        for (String name : new String[]{"format", "compressionLevel", "crashOnBrokenSymlink"}) {
            assertNotNull(WorldConfig.RegionFormat.class.getDeclaredField(name),
                "WorldConfig.RegionFormat must declare " + name);
        }
    }
}
