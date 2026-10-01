package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Stuck-p99 regression tests: windowed flush-latency histogram.
 *
 * <p>Covers: old slow samples stop affecting p99 after rotation (with
 * previous-generation fallback while the fresh window is small), failed
 * flushes excluded from buckets, 1s vs 8s distinguishable via split top
 * buckets, and top-bucket granularity. Lock-wait billing is NOT covered
 * here by construction: {@code flushGuard} is private to the codec's
 * {@code LinearRegionFile}, so no thread in this module can hold it to
 * measure contention; the post-lock timer placement is verified by
 * inspection of {@code doFlush} (timer starts after
 * {@code flushGuard.lock()}, success flag gates bucket insertion).
 */
public class FlushLatencyWindowTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void pinOverrides() {
        LinearFlushCoordinator.linear$setFlushThreadsForTests(1);
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(3600L);
    }

    @After
    public void resetOverrides() {
        LinearFlushCoordinator.evictAll();
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    private LinearFlushCoordinator freshCoordinator(String name) throws Exception {
        Path folder = temporaryFolder.newFolder(name).toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        coordinator.resetForTests();
        return coordinator;
    }

    @Test
    public void fallbackToPreviousWhileWindowSmall() throws Exception {
        LinearFlushCoordinator coordinator = freshCoordinator("fallback");
        coordinator.recordFlush(8_000_000L, true);
        coordinator.linear$forceRotateFlushWindowForTests();

        // Fresh window is empty: p99 falls back to the previous generation.
        assertEquals(5_000_001L, coordinator.snapshot().flushP99Micros());

        // Still under the 10-sample minimum: previous generation still wins.
        for (int i = 0; i < 3; i++) {
            coordinator.recordFlush(2_000L, true);
        }
        assertEquals(5_000_001L, coordinator.snapshot().flushP99Micros());

        // At 10 samples the current generation takes over; the slow sample is gone.
        for (int i = 0; i < 7; i++) {
            coordinator.recordFlush(2_000L, true);
        }
        LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
        assertEquals(5_000L, snap.flushP50Micros());
        assertEquals(5_000L, snap.flushP99Micros());
    }

    @Test
    public void rotationDropsOldSlowSamples() throws Exception {
        LinearFlushCoordinator coordinator = freshCoordinator("rotation");
        coordinator.recordFlush(8_000_000L, true);
        coordinator.linear$forceRotateFlushWindowForTests();
        for (int i = 0; i < 100; i++) {
            coordinator.recordFlush(2_000L, true);
        }
        LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
        assertEquals(5_000L, snap.flushP99Micros());

        // A second rotation drops the previous generation entirely.
        coordinator.linear$forceRotateFlushWindowForTests();
        for (int i = 0; i < 100; i++) {
            coordinator.recordFlush(2_000L, true);
        }
        assertEquals(5_000L, coordinator.snapshot().flushP99Micros());
    }

    @Test
    public void failedFlushExcludedFromBuckets() throws Exception {
        LinearFlushCoordinator coordinator = freshCoordinator("failexcl");
        for (int i = 0; i < 50; i++) {
            coordinator.recordFlush(2_000L, true);
        }
        // Slow failure: counted in lifetime totals, must not enter buckets.
        coordinator.recordFlush(8_000_000L, false);
        LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
        assertEquals(5_000L, snap.flushP99Micros());
        assertEquals(51L, snap.flush().count());
    }

    @Test
    public void p99Distinguishes1sVs8s() throws Exception {
        LinearFlushCoordinator coordinator = freshCoordinator("split");
        for (int i = 0; i < 100; i++) {
            coordinator.recordFlush(1_000_000L, true);
        }
        assertEquals(1_000_000L, coordinator.snapshot().flushP99Micros());

        coordinator.resetForTests();
        for (int i = 0; i < 100; i++) {
            coordinator.recordFlush(8_000_000L, true);
        }
        long slowP99 = coordinator.snapshot().flushP99Micros();
        assertEquals(5_000_001L, slowP99);
        assertTrue(slowP99 != 1_000_000L);
    }

    @Test
    public void topBucketsSplit1s2s5s() throws Exception {
        // Pins the documented window choice and bucket split.
        assertEquals(1024, LinearFlushCoordinator.FLUSH_WINDOW_SAMPLES);
        assertEquals(10, LinearFlushCoordinator.FLUSH_WINDOW_MIN_SAMPLES);

        LinearFlushCoordinator coordinator = freshCoordinator("topsplit");
        for (int i = 0; i < 100; i++) {
            coordinator.recordFlush(1_500_000L, true);
        }
        assertEquals(2_000_000L, coordinator.snapshot().flushP99Micros());

        coordinator.resetForTests();
        for (int i = 0; i < 100; i++) {
            coordinator.recordFlush(3_000_000L, true);
        }
        assertEquals(5_000_000L, coordinator.snapshot().flushP99Micros());
    }
}
