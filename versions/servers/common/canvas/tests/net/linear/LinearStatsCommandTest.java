package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Linearstats command-hook coverage.
 *
 * <p>NMS-light TempDir style like {@link LinearTimingInstrumentationTest}:
 * {@code SharedConstants}/{@code Bootstrap} only, never {@code halt()}, never
 * touches upstream suites. Covers the NMS hooks the Paper
 * {@code linearstats} command pulls (no Bukkit imports here; the Paper event
 * shape is checked via reflection so this file compiles even when the
 * Paper-side event is absent):</p>
 * <ul>
 *   <li>{@code snapshots()} aggregation across folders,</li>
 *   <li>per-file {@code linear$stats()} deltas,</li>
 *   <li>batch of N = exactly N flush events; a failure tightens
 *       {@code filesFlushed},</li>
 *   <li>event-shape unit check if the Paper event class is visible.</li>
 * </ul>
 */
public class LinearStatsCommandTest {

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
        // Age-based flush defaults to 10s; immediate-flush
        // tests force frequency 0, cleared here (also clears pool override).
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    private static byte[] pattern(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) ((seed * 31 + i * 17) & 0xFF);
        }
        return out;
    }

    private static byte[] readAll(LinearRegionFile region, long chunk) throws IOException {
        try (DataInputStream in = region.getChunkDataInputStream(chunk)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Test
    public void snapshotsAggregationCoversFolders() throws IOException {
        Path dirA = this.tempDir.resolve("snapA");
        Path dirB = this.tempDir.resolve("snapB");
        dirA.toFile().mkdirs();
        dirB.toFile().mkdirs();
        LinearFlushCoordinator coordA = LinearFlushCoordinator.forFolder(dirA);
        LinearFlushCoordinator coordB = LinearFlushCoordinator.forFolder(dirB);
        coordA.resetForTests();
        coordB.resetForTests();

        LinearRegionFile a = new LinearRegionFile(dirA.resolve("r.0.0.linear"), COMPRESSION);
        LinearRegionFile b = new LinearRegionFile(dirB.resolve("r.0.0.linear"), COMPRESSION);
        try {
            a.write(ChunkKey.of(0, 0), ByteBuffer.wrap(pattern(64, 1)));
            a.flush();
            b.write(ChunkKey.of(0, 0), ByteBuffer.wrap(pattern(64, 2)));
            b.write(ChunkKey.of(1, 0), ByteBuffer.wrap(pattern(64, 3)));
            b.flush();
        } finally {
            a.close();
            b.close();
        }

        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = LinearFlushCoordinator.snapshots();
        assertFalse(snaps.isEmpty(), "snapshots() must be non-empty after I/O");
        // Keys are absolute folder-path strings (same key as BY_FOLDER).
        String keyA = dirA.toAbsolutePath().normalize().toString();
        String keyB = dirB.toAbsolutePath().normalize().toString();
        assertTrue(snaps.containsKey(keyA), "snapshots() must contain folder A: " + keyA);
        assertTrue(snaps.containsKey(keyB), "snapshots() must contain folder B: " + keyB);
        assertTrue(snaps.get(keyA).write().count() >= 1, "A write>=1");
        assertTrue(snaps.get(keyB).write().count() >= 2, "B write>=2");
        assertTrue(snaps.get(keyA).flush().count() >= 1, "A flush>=1");
    }

    @Test
    public void perFileStatsDeltas() throws IOException {
        Path dir = this.tempDir.resolve("perfile");
        dir.toFile().mkdirs();
        LinearFlushCoordinator.forFolder(dir).resetForTests();
        Path file = dir.resolve("r.0.0.linear");
        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        try {
            LinearRegionTimings.LinearRegionStats before = region.linear$stats();
            long pos = ChunkKey.of(5, 5);
            byte[] payload = pattern(128, 9);
            region.write(pos, ByteBuffer.wrap(payload));
            LinearRegionTimings.LinearRegionStats afterWrite = region.linear$stats();
            assertTrue(afterWrite.write().count() >= before.write().count() + 1, "write delta >=1");
            region.flush();
            LinearRegionTimings.LinearRegionStats afterFlush = region.linear$stats();
            assertTrue(afterFlush.flush().count() >= afterWrite.flush().count() + 1, "flush delta >=1 (didIo guard: clean-no-op would not count)");
            byte[] back = readAll(region, pos);
            assertArrayEquals(payload, back, "round-trip");
            LinearRegionTimings.LinearRegionStats afterRead = region.linear$stats();
            assertTrue(afterRead.read().count() >= afterFlush.read().count() + 1, "read delta >=1");
            // Folder snapshot mirrors the per-file forwarding.
            LinearRegionTimings.LinearFolderSnapshot snap = LinearFlushCoordinator.forFolder(dir).snapshot();
            assertTrue(snap.write().count() >= 1, "folder write>=1 via forwarding");
            assertTrue(snap.flush().count() >= 1, "folder flush>=1 via doFlush forwarding");
        } finally {
            region.close();
        }
    }

    @Test
    public void batchOfNIsExactlyNFlushEvents() throws IOException {
        final int n = 4;
        Path dir = this.tempDir.resolve("batch");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        // Force immediate (age 0) so flushDirty drains.
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);

        LinearRegionFile[] files = new LinearRegionFile[n];
        try {
            for (int i = 0; i < n; i++) {
                Path f = dir.resolve("r." + i + ".0.linear");
                files[i] = new LinearRegionFile(f, COMPRESSION);
                files[i].write(ChunkKey.of(i * 32, 0), ByteBuffer.wrap(pattern(64, 100 + i)));
                coordinator.markDirty(files[i]);
            }
            // flushDirty itself records nothing (no batch increment); each
            // doFlush records exactly one flush event, so batch of N == N.
            coordinator.flushDirty();
            LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
            assertEquals(n, snap.flush().count(), "batch of N must be exactly N flush events (no batch-level double-count)");
            assertEquals(n, snap.filesFlushed(), "filesFlushed == N on all-ok batch");
            assertEquals(0L, snap.failures(), "no failures on all-ok batch");
        } finally {
            for (LinearRegionFile f : files) {
                if (f != null) {
                    try {
                        f.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    @Test
    public void failureTightensFilesFlushed() throws IOException {
        Path dir = this.tempDir.resolve("failbatch");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        // Force immediate (age 0) so flushDirty drains.
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);

        LinearRegionFile good0 = new LinearRegionFile(dir.resolve("r.10.0.linear"), COMPRESSION);
        LinearRegionFile good1 = new LinearRegionFile(dir.resolve("r.11.0.linear"), COMPRESSION);
        FailingRegionFile bad = new FailingRegionFile();
        try {
            good0.write(ChunkKey.of(10 * 32, 0), ByteBuffer.wrap(pattern(64, 7)));
            good1.write(ChunkKey.of(11 * 32, 0), ByteBuffer.wrap(pattern(64, 8)));
            coordinator.markDirty(good0);
            coordinator.markDirty(good1);
            coordinator.markDirty(bad);
            coordinator.flushDirty();
            LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
            // 2 good files each contributed one doFlush flush event; the failing
            // file contributes a failure but no flush timing and no filesFlushed.
            assertTrue(snap.failures() >= 1, "failure must be recorded");
            assertTrue(snap.filesFlushed() < 3, "failure must tighten filesFlushed (< attempted 3), was " + snap.filesFlushed());
            assertEquals(2L, snap.filesFlushed(), "only the 2 good files count as flushed");
            assertEquals(2L, snap.flush().count(), "only the 2 good doFlush calls count as flush events");
        } finally {
            try {
                good0.close();
            } catch (IOException ignored) {
            }
            try {
                good1.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    public void cleanNoOpRecordsNothing() throws IOException {
        Path dir = this.tempDir.resolve("cleannoop");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        Path file = dir.resolve("r.0.0.linear");
        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        try {
            region.write(ChunkKey.of(0, 0), ByteBuffer.wrap(pattern(32, 5)));
            region.flush();
            LinearRegionTimings.LinearFolderSnapshot afterFirst = coordinator.snapshot();
            long flushAfterFirst = afterFirst.flush().count();
            assertTrue(flushAfterFirst >= 1, "first flush records");
            // Second flush is a clean-no-op (didIo false) and must record nothing.
            region.flush();
            LinearRegionTimings.LinearFolderSnapshot afterSecond = coordinator.snapshot();
            assertEquals(flushAfterFirst, afterSecond.flush().count(), "clean-no-op flush records nothing (didIo guard)");
            // Clean close likewise records nothing extra.
            region.close();
            LinearRegionTimings.LinearFolderSnapshot afterClose = coordinator.snapshot();
            assertEquals(flushAfterFirst, afterClose.flush().count(), "clean close records nothing");
        } finally {
            try {
                region.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    public void panelDataWiresMeasurementCounters() throws IOException {
        // Panel: per-world level + time-since-last-flush via measurement counters.
        // NMS side pins that the snapshot carries every field the Adventure
        // panel displays (raw/compressed, p50/p99, millisSince, dirtyDepth);
        // formatting (human units, bar, alignment) lives in the Paper command.
        Path dir = this.tempDir.resolve("paneldata");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        // Force immediate so flushDirty drains (age-based default 10s).
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);
        Path file = dir.resolve("r.0.0.linear");
        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        try {
            region.write(ChunkKey.of(0, 0), ByteBuffer.wrap(pattern(512, 77)));
            coordinator.markDirty(region);
            coordinator.flushDirty();
        } finally {
            try { region.close(); } catch (IOException ignored) {}
        }
        LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
        assertTrue(snap.rawBytes() > 0L, "panel needs rawBytes>0");
        assertTrue(snap.compressedBytes() > 0L, "panel needs compressedBytes>0");
        assertTrue(snap.flushP50Micros() >= 0L, "panel needs p50");
        assertTrue(snap.flushP99Micros() >= snap.flushP50Micros(), "panel needs p99>=p50");
        assertTrue(snap.millisSinceLastFlush() >= 0L, "panel needs millisSince>=0");
        assertTrue(snap.millisSinceLastFlush() < 60_000L, "millisSince recent");
        assertEquals(0, snap.dirtyDepth(), "panel dirty bar drained (barrier joined)");
        assertTrue(snap.filesFlushed() >= 1L, "panel files count");
        // Bridge call site: flushDirty with >=1 attempted must not throw without Bukkit…
        // (the sync overload catches and stays silent in NMS tests; on a server it fires the event).
        // Reaching here without exception pins the call site is wired and safe.
    }

    @Test
    public void eventShapeIfVisible() {
        // Paper-side event lives in the Paper source-set; NMS-light tests must
        // not hard-depend on it. Check shape via reflection when visible.
        final String eventClass = "org.bukkit.event.world.LinearRegionFlushCompletedEvent";
        Class<?> clazz;
        try {
            clazz = Class.forName(eventClass);
        } catch (ClassNotFoundException e) {
            // Not visible from the NMS test runtime — pass (Paper patch still
            // carries the shape; the integrator checks it on the Paper side).
            return;
        }
        try {
            assertNotNull(clazz.getMethod("getWorldName"), "worldName getter");
            assertNotNull(clazz.getMethod("getFolderType"), "folderType getter");
            assertNotNull(clazz.getMethod("getFilesFlushed"), "filesFlushed getter");
            assertNotNull(clazz.getMethod("getFailures"), "failures getter");
            assertNotNull(clazz.getMethod("getElapsedMicros"), "elapsedMicros getter");
            assertNotNull(clazz.getMethod("getTotals"), "totals getter");
            assertNotNull(clazz.getMethod("getHandlers"), "HandlerList getter");
            assertNotNull(clazz.getMethod("getHandlerList"), "static HandlerList getter");
            // folderType domain is documented as region|poi|entities (checked
            // in the Paper bridge; here we just pin the getters exist).
        } catch (NoSuchMethodException e) {
            fail("LinearRegionFlushCompletedEvent shape mismatch (flush-granularity contract): " + e.getMessage());
        }
    }

    /** Minimal failing file: throws on flush so flushOne records a failure. */
    private static final class FailingRegionFile implements AbstractRegionFile {
        @Override
        public DataInputStream getChunkDataInputStream(long chunk) {
            return null;
        }

        @Override
        public void write(long chunk, ByteBuffer data) {
        }

        @Override
        public boolean hasChunk(long chunk) {
            return false;
        }

        @Override
        public void flush() throws IOException {
            throw new IOException("injected failure for P1 regression");
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isMarkedToSave() {
            return true;
        }

        @Override
        public void clearMarkedToSave() {
        }

        @Override
        public void clear(long chunk) {
        }
    }
}
