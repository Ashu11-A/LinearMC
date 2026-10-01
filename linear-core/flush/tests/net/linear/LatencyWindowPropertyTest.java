package net.linear;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Windowed-p99 properties over synthetic flush-latency streams.
 *
 * <p>Bucket map (micros): fast {@code 2_000} lands in the {@code 5_000}
 * bucket; slow {@code 8_000_000} lands in the unbounded top bucket reported
 * as the {@code 5_000_001} sentinel. Streams:
 *
 * <ul>
 *   <li>all-fast: p50/p99 stay green;</li>
 *   <li>steady-2%-slow-forever: p99 stays red (sentinel) in the current
 *       generation AND after a rotation, i.e. a persistent tail never looks
 *       clean;</li>
 *   <li>single-spike-then-clean: a 5% spike reads red, then after a forced
 *       rotation the fresh window falls back to the previous (still red
 *       while small) and goes green once {@code >= 10} clean samples land —
 *       the spike decays instead of pinning p99;</li>
 *   <li>failures-excluded: slow failures count in lifetime totals but never
 *       enter the buckets;</li>
 *   <li>sample-bound auto-rotation: past {@code 1024} success samples the
 *       window rotates on its own and the old spike drops without a forced
 *       rotate.</li>
 * </ul>
 */
public class LatencyWindowPropertyTest {

    private static final long FAST_MICROS = 2_000L;
    private static final long SLOW_MICROS = 8_000_000L;
    private static final long FAST_BUCKET = 5_000L;
    private static final long SLOW_SENTINEL = 5_000_001L;

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
    public void allFastStaysGreen() throws Exception {
        LinearFlushCoordinator coordinator = freshCoordinator("allfast");
        for (int i = 0; i < 200; i++) {
            coordinator.recordFlush(FAST_MICROS, true);
        }
        LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
        assertEquals(FAST_BUCKET, snap.flushP50Micros());
        assertEquals(FAST_BUCKET, snap.flushP99Micros());
    }

    @Test
    public void steadyTwoPercentSlowForeverStaysRed() throws Exception {
        LinearFlushCoordinator coordinator = freshCoordinator("steady2pct");
        // Deterministic 2%: every 50th sample is slow (2 per 100).
        for (int i = 0; i < 200; i++) {
            coordinator.recordFlush(i % 50 == 49 ? SLOW_MICROS : FAST_MICROS, true);
        }
        assertEquals(SLOW_SENTINEL, coordinator.snapshot().flushP99Micros());

        // A rotation must not launder a persistent tail: the next window has
        // the same 2% shape and stays red once trusted (>= 10 samples).
        coordinator.linear$forceRotateFlushWindowForTests();
        for (int i = 0; i < 200; i++) {
            coordinator.recordFlush(i % 50 == 49 ? SLOW_MICROS : FAST_MICROS, true);
        }
        LinearRegionTimings.LinearFolderSnapshot after = coordinator.snapshot();
        assertEquals(SLOW_SENTINEL, after.flushP99Micros());
    }

    @Test
    public void singleSpikeDecaysAfterRotation() throws Exception {
        LinearFlushCoordinator coordinator = freshCoordinator("spike");
        // 5% spike: 99th percentile lands in the top bucket -> red.
        for (int i = 0; i < 95; i++) {
            coordinator.recordFlush(FAST_MICROS, true);
        }
        for (int i = 0; i < 5; i++) {
            coordinator.recordFlush(SLOW_MICROS, true);
        }
        assertEquals(SLOW_SENTINEL, coordinator.snapshot().flushP99Micros());

        coordinator.linear$forceRotateFlushWindowForTests();
        // Fresh window is empty: the previous (red) generation backs the read.
        assertEquals(SLOW_SENTINEL, coordinator.snapshot().flushP99Micros());
        // Still under the 10-sample trust minimum: previous still wins.
        for (int i = 0; i < 9; i++) {
            coordinator.recordFlush(FAST_MICROS, true);
        }
        assertEquals(SLOW_SENTINEL, coordinator.snapshot().flushP99Micros());
        // At 10 clean samples the current generation takes over: decayed.
        coordinator.recordFlush(FAST_MICROS, true);
        LinearRegionTimings.LinearFolderSnapshot decayed = coordinator.snapshot();
        assertEquals(FAST_BUCKET, decayed.flushP50Micros());
        assertEquals(FAST_BUCKET, decayed.flushP99Micros());
    }

    @Test
    public void failuresExcludedFromBuckets() throws Exception {
        LinearFlushCoordinator coordinator = freshCoordinator("failures");
        for (int i = 0; i < 50; i++) {
            coordinator.recordFlush(FAST_MICROS, true);
        }
        for (int i = 0; i < 5; i++) {
            coordinator.recordFlush(SLOW_MICROS, false);
        }
        LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
        assertEquals(FAST_BUCKET, snap.flushP99Micros());
        assertEquals(55L, snap.flush().count());
    }

    @Test
    public void sampleBoundAutoRotationDropsOldSpike() throws Exception {
        LinearFlushCoordinator coordinator = freshCoordinator("autorotate");
        coordinator.recordFlush(SLOW_MICROS, true);
        // Fill past the 1024-sample burst bound with clean samples; the
        // window rotates on its own (no forced rotate). The rotation copies
        // cur->{slow + 1023 fast} to prev and restarts cur, so immediately
        // after the boundary the read still falls back to prev (red) while
        // cur is small — then 10+ further clean samples flip it green.
        for (int i = 0; i < LinearFlushCoordinator.FLUSH_WINDOW_SAMPLES; i++) {
            coordinator.recordFlush(FAST_MICROS, true);
        }
        for (int i = 0; i < 50; i++) {
            coordinator.recordFlush(FAST_MICROS, true);
        }
        LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
        assertEquals(FAST_BUCKET, snap.flushP99Micros());
    }
}
