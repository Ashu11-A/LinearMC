package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measurement counters (bytes, flush latency, flush age).
 *
 * <p>NMS-light (TempDir + bootstrap): verifies compressed vs raw bytes,
 * flush p50/p99, millis-since-flush, and the empty-set visibility fix (markDirty +
 * cache hits/misses separate "never invoked" from "empty set"). Lock-free
 * only (LongAdder/LongAccumulator, no synchronized on hot paths -- verified
 * by inspection; this test pins behaviour).
 */
public class LinearMeasurementCountersTest {

    private static final int COMPRESSION = 3;

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void resetCoordinator() {
        // Clear age/pool overrides (frequency 0 forced below).
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    private static byte[] pattern(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) ((seed * 31 + i * 17) & 0xFF);
        }
        return out;
    }

    @Test
    public void emptySnapshotHasSentinels() {
        Path dir = this.tempDir.resolve("emptymeas");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
        assertEquals(0L, snap.rawBytes(), "rawBytes starts 0");
        assertEquals(0L, snap.compressedBytes(), "compressedBytes starts 0");
        assertEquals(0L, snap.flushP50Micros(), "p50 starts 0");
        assertEquals(0L, snap.flushP99Micros(), "p99 starts 0");
        assertEquals(-1L, snap.millisSinceLastFlush(), "never-flushed sentinel -1");
        assertEquals(0L, snap.markDirty(), "markDirty starts 0 (never invoked)");
        assertEquals(0L, snap.cacheHits(), "cacheHits starts 0");
        assertEquals(0L, snap.cacheMisses(), "cacheMisses starts 0");
    }

    @Test
    public void flushRecordsBytesAndLatency() throws Exception {
        Path dir = this.tempDir.resolve("byteslat");
        dir.toFile().mkdirs();
        Path file = dir.resolve("r.0.0.linear");
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        // Force immediate so coordinator.flushDirty drains.
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);

        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        try {
            region.write(ChunkKey.of(0, 0), ByteBuffer.wrap(pattern(512, 5)));
            // Mark-dirty via coordinator so markDirty count is exercised too.
            coordinator.markDirty(region);
            region.flush();
            // Ensure folder success timestamp via coordinator path as well.
            coordinator.flushDirty();
        } finally {
            region.close();
        }

        LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
        assertTrue(snap.rawBytes() > 0L, "rawBytes>0 after flush, was " + snap.rawBytes());
        assertTrue(snap.compressedBytes() > 0L, "compressedBytes>0 after flush");
        assertTrue(snap.compressedBytes() <= snap.rawBytes() + 8192L,
            "compressed should be near raw for small region (raw=" + snap.rawBytes()
            + " compressed=" + snap.compressedBytes() + ")");
        assertTrue(snap.flush().count() >= 1L, "flush count>=1");
        assertTrue(snap.flushP50Micros() >= 0L, "p50>=0");
        assertTrue(snap.flushP99Micros() >= snap.flushP50Micros(),
            "p99>=p50 (p50=" + snap.flushP50Micros() + " p99=" + snap.flushP99Micros() + ")");
        assertTrue(snap.millisSinceLastFlush() >= 0L,
            "millisSince>=0 after success, was " + snap.millisSinceLastFlush());
        assertTrue(snap.millisSinceLastFlush() < 60_000L, "millisSince recent (<60s)");
        assertTrue(snap.markDirty() >= 1L, "markDirty>=1 after markDirty()");
        assertTrue(snap.filesFlushed() >= 1L, "filesFlushed>=1");
    }

    @Test
    public void markDirtyAndCacheCountsSeparateNeverFromEmpty() throws Exception {
        Path dir = this.tempDir.resolve("blindspot");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();

        // Empty set: snapshot entry exists (via forFolder) but counts zero.
        LinearRegionTimings.LinearFolderSnapshot empty = coordinator.snapshot();
        assertEquals(0L, empty.markDirty(), "empty set markDirty 0");
        assertEquals(0L, empty.cacheHits() + empty.cacheMisses(), "empty set cache 0");

        // Invoke markDirty + cache hit/miss bridges, then verify separation.
        Path file = dir.resolve("r.5.5.linear");
        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        try {
            region.write(ChunkKey.of(5 * 32, 5 * 32), ByteBuffer.wrap(pattern(64, 9)));
            coordinator.markDirty(region);
            coordinator.reportCacheHit();
            coordinator.reportCacheMiss();
            coordinator.reportCacheMiss();
        } finally {
            region.close();
        }
        LinearRegionTimings.LinearFolderSnapshot after = coordinator.snapshot();
        assertTrue(after.markDirty() >= 1L, "never-invoked (0) vs invoked (>=1)");
        assertTrue(after.cacheHits() >= 1L, "cacheHits>=1");
        assertTrue(after.cacheMisses() >= 2L, "cacheMisses>=2");
    }

    @Test
    public void statsApiExposesMeasurementFields() throws Exception {
        Path dir = this.tempDir.resolve("apicov");
        dir.toFile().mkdirs();
        Path file = dir.resolve("r.0.0.linear");
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        // Force immediate age so coordinator.flushDirty() drains (default
        // 10s frequency would leave freshly-dirtied files pending and record
        // no coordinator flush success). Mirrors flushRecordsBytesAndLatency.
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);

        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        try {
            region.write(ChunkKey.of(0, 0), ByteBuffer.wrap(pattern(512, 5)));
            coordinator.markDirty(region);
            region.flush();
            coordinator.flushDirty();
        } finally {
            region.close();
        }

        LinearRegionTimings.LinearFolderSnapshot nms = coordinator.snapshot();
        assertTrue(nms.rawBytes() > 0L, "rawBytes>0 after flush, was " + nms.rawBytes());
        assertTrue(nms.compressedBytes() > 0L, "compressedBytes>0 after flush");
        assertTrue(nms.flushP99Micros() >= nms.flushP50Micros(),
            "p99>=p50 (p50=" + nms.flushP50Micros() + " p99=" + nms.flushP99Micros() + ")");
        assertTrue(nms.millisSinceLastFlush() >= 0L,
            "millisSince>=0 after success, was " + nms.millisSinceLastFlush());
        // Paper-side LinearStats lives in the Paper source-set; NMS-light tests
        // must not hard-depend on it. Mirror LinearStatsCommandTest's
        // eventShapeIfVisible pattern: reflect when visible, else the NMS
        // assertions above already pin the values.
        final String apiClass = "io.papermc.paper.linear.LinearStats";
        Class<?> clazz;
        try {
            clazz = Class.forName(apiClass);
        } catch (ClassNotFoundException e) {
            return;
        }
        try {
            String key = dir.toAbsolutePath().toString();
            Object opt = clazz.getMethod("snapshot", String.class).invoke(null, key);
            assertTrue(((Optional<?>) opt).isPresent(), "LinearStats.snapshot present for " + key);
            Object dto = ((Optional<?>) opt).get();
            // The delegate DTO carries the measurement components straight
            // through (paper-0009 LinearStats: rawBytes, compressedBytes,
            // flushP50Micros, flushP99Micros, millisSinceLastFlush appended
            // after dirtyNow, mapped 1:1 from this NMS snapshot shape), so pin
            // both the overlapping fields and the five measurement fields.
            assertEquals(nms.read().count(),
                ((Number) dto.getClass().getMethod("reads").invoke(dto)).longValue(), "reads passthrough");
            assertEquals(nms.write().count(),
                ((Number) dto.getClass().getMethod("writes").invoke(dto)).longValue(), "writes passthrough");
            assertEquals(nms.flush().count(),
                ((Number) dto.getClass().getMethod("flushes").invoke(dto)).longValue(), "flushes passthrough");
            assertEquals(nms.filesFlushed(),
                ((Number) dto.getClass().getMethod("filesFlushed").invoke(dto)).longValue(),
                "filesFlushed passthrough");
            assertEquals(nms.failures(),
                ((Number) dto.getClass().getMethod("failures").invoke(dto)).longValue(), "failures passthrough");
            // Cumulative byte/latency counters: no I/O happens between the NMS
            // snapshot above and the delegate mapping, so exact equality holds.
            assertEquals(nms.rawBytes(),
                ((Number) dto.getClass().getMethod("rawBytes").invoke(dto)).longValue(),
                "rawBytes passthrough");
            assertEquals(nms.compressedBytes(),
                ((Number) dto.getClass().getMethod("compressedBytes").invoke(dto)).longValue(),
                "compressedBytes passthrough");
            assertEquals(nms.flushP50Micros(),
                ((Number) dto.getClass().getMethod("flushP50Micros").invoke(dto)).longValue(),
                "flushP50Micros passthrough");
            assertEquals(nms.flushP99Micros(),
                ((Number) dto.getClass().getMethod("flushP99Micros").invoke(dto)).longValue(),
                "flushP99Micros passthrough");
            // Wall-clock age: the delegate snapshot is taken after the NMS one
            // from the same last-flush timestamp, so its value is monotonic
            // non-decreasing (integer-millis truncation is monotonic too) and
            // never the -1 never-flushed sentinel after a success.
            long apiMillis = ((Number) dto.getClass().getMethod("millisSinceLastFlush").invoke(dto))
                .longValue();
            assertTrue(apiMillis >= 0L, "delegate millisSince wired, not -1, was " + apiMillis);
            assertTrue(apiMillis >= nms.millisSinceLastFlush(),
                "delegate millisSince monotonic vs NMS (api=" + apiMillis + " nms="
                    + nms.millisSinceLastFlush() + ")");
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            fail("LinearStats delegate shape mismatch: " + e.getMessage());
        }
    }

    @Test
    public void statsApiEmptySentinels() throws Exception {
        Path dir = this.tempDir.resolve("apiempty");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
        assertEquals(0L, snap.rawBytes(), "rawBytes starts 0");
        assertEquals(0L, snap.compressedBytes(), "compressedBytes starts 0");
        assertEquals(0L, snap.flushP50Micros(), "p50 starts 0");
        assertEquals(0L, snap.flushP99Micros(), "p99 starts 0");
        assertEquals(-1L, snap.millisSinceLastFlush(), "never-flushed sentinel -1");
        // Same reflection pattern as above: the delegate must expose the same
        // empty-folder sentinels when the Paper source-set is visible.
        final String apiClass = "io.papermc.paper.linear.LinearStats";
        Class<?> clazz;
        try {
            clazz = Class.forName(apiClass);
        } catch (ClassNotFoundException e) {
            return;
        }
        try {
            String key = dir.toAbsolutePath().toString();
            Object opt = clazz.getMethod("snapshot", String.class).invoke(null, key);
            assertTrue(((Optional<?>) opt).isPresent(), "LinearStats.snapshot present for " + key);
            Object dto = ((Optional<?>) opt).get();
            assertEquals(0L,
                ((Number) dto.getClass().getMethod("rawBytes").invoke(dto)).longValue(),
                "delegate rawBytes starts 0");
            assertEquals(0L,
                ((Number) dto.getClass().getMethod("compressedBytes").invoke(dto)).longValue(),
                "delegate compressedBytes starts 0");
            assertEquals(0L,
                ((Number) dto.getClass().getMethod("flushP50Micros").invoke(dto)).longValue(),
                "delegate p50 starts 0");
            assertEquals(0L,
                ((Number) dto.getClass().getMethod("flushP99Micros").invoke(dto)).longValue(),
                "delegate p99 starts 0");
            assertEquals(-1L,
                ((Number) dto.getClass().getMethod("millisSinceLastFlush").invoke(dto)).longValue(),
                "delegate never-flushed sentinel -1");
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            fail("LinearStats delegate shape mismatch: " + e.getMessage());
        }
    }
}
