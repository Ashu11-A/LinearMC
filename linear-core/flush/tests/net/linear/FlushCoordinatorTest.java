package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Coordinator policy: age-gated vs forced flush, eviction, listener fan-out
 * and the {@link LinearFlushCoordinator#MAX_DIRTY} bound. Same package
 * ({@code net.linear}) so the {@code linear$*} test hooks are reachable
 * without widening production visibility.
 */
public class FlushCoordinatorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final List<String> flushEvents = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger lastAttempted = new AtomicInteger(-1);

    @Before
    public void pinOverrides() {
        LinearFlushCoordinator.linear$setFlushThreadsForTests(1);
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(3600L);
        LinearFlushCoordinator.linear$setFlushListener((folderKey, filesAttempted, elapsedMicros) -> {
            flushEvents.add(folderKey);
            lastAttempted.set(filesAttempted);
        });
    }

    @After
    public void resetOverrides() {
        LinearFlushCoordinator.evictAll();
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
        LinearFlushCoordinator.linear$setFlushListener((folderKey, filesAttempted, elapsedMicros) -> {
        });
        flushEvents.clear();
        lastAttempted.set(-1);
    }

    @Test
    public void forcedFlushDrainsYoungFilesButAgeGateHolds() throws Exception {
        Path folder = temporaryFolder.newFolder("young").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        StubRegionFile file = new StubRegionFile();
        coordinator.markDirty(file);
        assertFalse(coordinator.isEmpty());
        assertEquals(1, coordinator.dirtyCount());

        // Young file: age-gated flush leaves it tracked and unflushed.
        coordinator.flushDirty();
        assertEquals(0, file.flushCalls);
        assertEquals(1, coordinator.dirtyCount());

        // Forced flush drains regardless of age.
        coordinator.flushDirty(true);
        assertEquals(1, file.flushCalls);
        assertTrue(coordinator.isEmpty());
        assertEquals(0, coordinator.dirtyCount());
    }

    @Test
    public void dirtyCountTracksMarks() throws Exception {
        Path folder = temporaryFolder.newFolder("counts").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        assertTrue(coordinator.isEmpty());
        assertEquals(0, coordinator.dirtyCount());
        StubRegionFile first = new StubRegionFile();
        StubRegionFile second = new StubRegionFile();
        coordinator.markDirty(first);
        coordinator.markDirty(second);
        assertEquals(2, coordinator.dirtyCount());
        assertFalse(coordinator.isEmpty());
        coordinator.flushDirty(true);
        assertTrue(coordinator.isEmpty());
    }

    @Test
    public void redirtiedDuringFlushRequeuesInsteadOfDropping() throws Exception {
        // A file still marked after flush() (write landed after the snapshot)
        // must be requeued, not dropped: first drain reflushes, second clears.
        Path folder = temporaryFolder.newFolder("requeue").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        StubRegionFile file = new StubRegionFile() {
            int calls;
            @Override
            public void flush() {
                calls++;
                super.flush();
                if (calls == 1) {
                    // Simulate a write racing the flush: re-mark after the
                    // snapshot clear so the tail sees a dirty file.
                    marked = true;
                }
            }
        };
        coordinator.markDirty(file);
        coordinator.flushDirty(true);
        assertEquals(1, file.flushCalls);
        assertEquals("redirtied file must be requeued", 1, coordinator.dirtyCount());
        assertEquals("clean requeue must not count a failure",
            0, coordinator.snapshot().failures());
        coordinator.flushDirty(true);
        assertEquals(2, file.flushCalls);
        assertTrue(coordinator.isEmpty());    }

    @Test
    public void shutdownModeDefersVictimsToForcedDrain() throws Exception {
        // Post-halt saves re-dirty hundreds of files at once: per-mark
        // pressure/age victim batches would serialize multi-second rebuilds
        // behind the shutdown saves. Shutdown mode tracks only; the tail
        // forced barrier persists everything once.
        Path folder = temporaryFolder.newFolder("shutdownmode").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        LinearFlushCoordinator.linear$beginShutdown();
        java.util.List<StubRegionFile> files = new java.util.ArrayList<>();
        for (int i = 0; i < LinearFlushCoordinator.MAX_DIRTY + 50; i++) {
            StubRegionFile file = new StubRegionFile();
            files.add(file);
            coordinator.markDirty(file);
        }
        assertEquals("no victim may drain during shutdown saves",
            LinearFlushCoordinator.MAX_DIRTY + 50, coordinator.dirtyCount());
        for (StubRegionFile file : files) {
            assertEquals(0, file.flushCalls);
        }
        coordinator.flushDirty(true);
        for (StubRegionFile file : files) {
            assertEquals(1, file.flushCalls);
        }
        assertTrue(coordinator.isEmpty());
    }

    @Test
    public void shutdownModeDefersAgeVictim() throws Exception {
        // Age branch needs a really-old head (frequency is live-read): the
        // removal from the dirty set happens synchronously in markDirty, so
        // asserting the count right after the trigger is deterministic.
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(1L);
        Path folder = temporaryFolder.newFolder("shutdownage").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        StubRegionFile head = new StubRegionFile();
        coordinator.markDirty(head);
        Thread.sleep(1100L);
        LinearFlushCoordinator.linear$beginShutdown();
        coordinator.markDirty(new StubRegionFile());
        assertEquals("age victim must not drain during shutdown", 2, coordinator.dirtyCount());
        assertEquals(0, head.flushCalls);
        coordinator.flushDirty(true);
        assertTrue(coordinator.isEmpty());
    }

    @Test
    public void forcedDrainUsesThreadFloor() throws Exception {        // Forced drains (explicit save, eviction, shutdown) run at least at
        // the floor even when flush-max-threads is 1; periodic saves don't.
        LinearFlushCoordinator.linear$setFlushThreadsForTests(1);
        assertTrue(LinearFlushCoordinator.linear$forcedThreadsFloor() >= 2);
        LinearFlushCoordinator.linear$setForcedThreadsForTests(1);
        assertEquals(1, LinearFlushCoordinator.linear$forcedThreadsFloor());
        LinearFlushCoordinator.linear$setForcedThreadsForTests(null);
        assertTrue(LinearFlushCoordinator.linear$forcedThreadsFloor() >= 2);
    }

    @Test
    public void evictRedrainsRaceRemark() throws Exception {
        // A write landing between the evict snapshot-clear and the tail check
        // re-marks after the drain: evict's bounded re-pass must persist it
        // instead of stranding it in the dropped registry.
        Path folder = temporaryFolder.newFolder("evictrace").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        StubRegionFile file = new StubRegionFile() {
            int calls;
            @Override
            public void flush() {
                calls++;
                super.flush();
                if (calls == 1) {
                    marked = true;
                }
            }
        };
        coordinator.markDirty(file);
        LinearFlushCoordinator.evict(folder);
        assertEquals(2, file.flushCalls);
        assertEquals(0, LinearFlushCoordinator.forFolder(folder).dirtyCount());
    }

    @Test
    public void flushSnapshotFansOutAndJoins() throws Exception {
        // Leg-owned storage loops share this barrier: every file flushed
        // exactly once, joined before return, failures isolated per file.
        Path folder = temporaryFolder.newFolder("snapshot").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        java.util.List<StubRegionFile> files = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            StubRegionFile file = new StubRegionFile();
            files.add(file);
        }
        coordinator.linear$flushSnapshot(
            new java.util.ArrayList<AbstractRegionFile>(files), true);
        for (StubRegionFile file : files) {
            assertEquals(1, file.flushCalls);
        }
    }

    @Test
    public void flushSnapshotIsolatesFailures() throws Exception {
        Path folder = temporaryFolder.newFolder("snapshotfail").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        StubRegionFile ok1 = new StubRegionFile();
        StubRegionFile bad = new StubRegionFile() {
            @Override
            public void flush() {
                throw new RuntimeException("boom");
            }
        };
        StubRegionFile ok2 = new StubRegionFile();
        java.util.List<AbstractRegionFile> snapshot = new java.util.ArrayList<>();
        snapshot.add(ok1);
        snapshot.add(bad);
        snapshot.add(ok2);
        // Must not throw: failures are logged + counted + requeued.
        coordinator.linear$flushSnapshot(snapshot, true);
        assertEquals(1, ok1.flushCalls);
        assertEquals(1, ok2.flushCalls);
        assertEquals("failure must be counted", 1, coordinator.snapshot().failures());
        assertEquals("failed file must be requeued", 1, coordinator.dirtyCount());
    }

    @Test
    public void flushSnapshotSerialWhenUnforced() throws Exception {
        // forceBarrier=false keeps the configured serial default: with
        // threads=1 the files flush inline in order.
        LinearFlushCoordinator.linear$setFlushThreadsForTests(1);
        Path folder = temporaryFolder.newFolder("snapshotserial").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        java.util.List<StubRegionFile> files = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            files.add(new StubRegionFile());
        }
        coordinator.linear$flushSnapshot(
            new java.util.ArrayList<AbstractRegionFile>(files), false);
        for (StubRegionFile file : files) {
            assertEquals(1, file.flushCalls);
        }
    }

    @Test
    public void evictForceFlushesAndDropsRegistry() throws Exception {        Path folder = temporaryFolder.newFolder("evict").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        StubRegionFile file = new StubRegionFile();
        coordinator.markDirty(file);
        LinearFlushCoordinator.evict(folder);
        assertEquals(1, file.flushCalls);
        LinearFlushCoordinator fresh = LinearFlushCoordinator.forFolder(folder);
        assertNotSame(coordinator, fresh);
        assertTrue(fresh.isEmpty());
    }

    @Test
    public void maxDirtyBoundPressureLosesNothing() throws Exception {
        LinearFlushCoordinator.linear$setFlushThreadsForTests(4);
        Path folder = temporaryFolder.newFolder("pressure").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        List<StubRegionFile> files = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            StubRegionFile file = new StubRegionFile();
            files.add(file);
            coordinator.markDirty(file);
        }
        // Bound holds: only MAX_DIRTY stay tracked; the rest drain async
        // (the forced drain below joins every in-flight batch).
        assertEquals(LinearFlushCoordinator.MAX_DIRTY, coordinator.dirtyCount());
        LinearFlushCoordinator.flushAllDirty(true);
        for (StubRegionFile file : files) {
            assertTrue("unflushed file lost under bound pressure", file.flushCalls >= 1);
        }
        assertTrue(coordinator.isEmpty());
    }

    @Test
    public void listenerFiresOnFlushSync() throws Exception {
        Path folder = temporaryFolder.newFolder("listener").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        coordinator.markDirty(new StubRegionFile());
        // Age-gated no-op drains nothing, so no sync event fires.
        coordinator.flushDirty();
        assertTrue(flushEvents.isEmpty());
        coordinator.flushDirty(true);
        assertEquals(1, flushEvents.size());
        assertEquals(1, lastAttempted.get());
    }

    /** Park bound for a gated flush: bounds a never-drained failure, never hangs. */
    private static final long GATE_PARK_SECONDS = 15L;
    /** Age gate for the opportunistic-driver test: head must be this old to drain. */
    private static final long AGED_FREQ_SECONDS = 1L;
    /** Sleep past the age gate on a monotonic clock; overshoot only helps. */
    private static final long AGED_SLEEP_MILLIS = AGED_FREQ_SECONDS * 1000L + 100L;
    /**
     * Caller-block budget: a non-blocking {@code markDirty} returns in
     * microseconds, while the parked flush it (correctly) leaves behind runs
     * at least {@link #GATE_PARK_SECONDS} — 5s separates the two regimes with
     * room for loaded CI runners.
     */
    private static final long CALLER_BLOCK_BUDGET_MICROS = 5_000_000L;

    @Test(timeout = 120_000)
    public void boundPressureDrainsAsyncWithoutBlockingCaller() throws Exception {
        // The MAX_DIRTY victim must leave the dirty set and drain on the pool
        // WITHOUT joining: the caller (Moonrise I/O thread) returns while the
        // slow flush is still parked. The forced barrier below joins it.
        LinearFlushCoordinator.linear$setFlushThreadsForTests(2);
        Path folder = temporaryFolder.newFolder("pressure-async").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        GatedRegionFile victim = new GatedRegionFile();
        coordinator.markDirty(victim);
        for (int i = 1; i < LinearFlushCoordinator.MAX_DIRTY; i++) {
            coordinator.markDirty(new StubRegionFile());
        }
        assertDrainsAsyncWithoutBlockingCaller(coordinator, victim,
            () -> coordinator.markDirty(new StubRegionFile()),
            LinearFlushCoordinator.MAX_DIRTY);
    }

    @Test(timeout = 120_000)
    public void agedHeadDrainsAsyncWithoutBlockingCaller() throws Exception {
        // The opportunistic aged-head driver must behave like the bound path:
        // untrack under the lock, drain on the pool, never join the caller.
        // No clock control exists (frequency is live-read), so the 1s gate
        // needs a real 1.1s age on a monotonic clock; overshoot only helps.
        LinearFlushCoordinator.linear$setFlushThreadsForTests(2);
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(AGED_FREQ_SECONDS);
        Path folder = temporaryFolder.newFolder("aged-async").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        GatedRegionFile head = new GatedRegionFile();
        coordinator.markDirty(head);
        assertEquals(1, coordinator.dirtyCount());
        Thread.sleep(AGED_SLEEP_MILLIS);
        assertDrainsAsyncWithoutBlockingCaller(coordinator, head,
            () -> coordinator.markDirty(new StubRegionFile()),
            1);
    }

    /**
     * Shared non-blocking-drain proof: triggers the hygiene path, asserts the
     * caller returned while the gated flush was still parked, then releases
     * and proves the forced barrier joins it with nothing lost and both
     * flush-sync events fired (async victim batch + forced drain).
     */
    private void assertDrainsAsyncWithoutBlockingCaller(
            LinearFlushCoordinator coordinator, GatedRegionFile victim,
            Runnable trigger, int trackedAfterTrigger) throws Exception {
        long start = System.nanoTime();
        trigger.run();
        long elapsedMicros = (System.nanoTime() - start) / 1000L;
        assertTrue("victim never reached the pool",
            victim.entered.await(GATE_PARK_SECONDS, TimeUnit.SECONDS));
        assertEquals("caller blocked on hygiene flush", 0, victim.flushCalls);
        assertTrue("markDirty blocked for " + elapsedMicros + "us",
            elapsedMicros < CALLER_BLOCK_BUDGET_MICROS);
        // Victim untracked (in flight); the trigger's file stays tracked.
        assertEquals(trackedAfterTrigger, coordinator.dirtyCount());
        victim.release.countDown();
        coordinator.flushDirty(true);
        assertEquals(1, victim.flushCalls);
        assertFalse(victim.timedOut);
        assertTrue(coordinator.isEmpty());
        assertEquals(2, flushEvents.size());
    }

    @Test
    public void batchTimerStartsAfterSnapshotLock() throws Exception {
        // Behavioral pin: a forced drain reports a sane batch elapsed that
        // covers only the flush work, never the snapshot lock hold.
        Path folder = temporaryFolder.newFolder("timer").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        final java.util.List<Long> elapsed = Collections.synchronizedList(new ArrayList<>());
        LinearFlushCoordinator.linear$setFlushListener((folderKey, filesAttempted, elapsedMicros) -> {
            flushEvents.add(folderKey);
            lastAttempted.set(filesAttempted);
            elapsed.add(elapsedMicros);
        });
        coordinator.markDirty(new StubRegionFile());
        coordinator.flushDirty(true);
        assertEquals(1, lastAttempted.get());
        assertEquals(1, elapsed.size());
        assertTrue("negative batch elapsed: " + elapsed.get(0), elapsed.get(0) >= 0L);

        // Code-level pin: batchStartNanos must be assigned AFTER the snapshot
        // lock is acquired/released, so the reported elapsed excludes lock
        // hold time. Strongest assertion the module allows without timers.
        String source = readCoordinatorSource();
        if (source != null) {
            int method = source.indexOf("void flushDirty(boolean force)");
            assertTrue("flushDirty(boolean) not found in coordinator source", method >= 0);
            String body = source.substring(method);
            int lock = body.indexOf("synchronized (this)");
            int timer = body.indexOf("batchStartNanos = System.nanoTime()");
            assertTrue("snapshot lock not found in flushDirty", lock >= 0);
            assertTrue("batch timer not found in flushDirty", timer >= 0);
            assertTrue("batch timer must start after snapshot lock acquisition", timer > lock);
        }
    }

    private static String readCoordinatorSource() {
        String[] candidates = {
            "flush/src/net/linear/LinearFlushCoordinator.java",
            "linear-core/flush/src/net/linear/LinearFlushCoordinator.java",
            System.getProperty("user.dir") + "/flush/src/net/linear/LinearFlushCoordinator.java",
            System.getProperty("user.dir")
                + "/linear-core/flush/src/net/linear/LinearFlushCoordinator.java",
        };
        for (String candidate : candidates) {
            try {
                java.nio.file.Path p = java.nio.file.Paths.get(candidate);
                if (java.nio.file.Files.isRegularFile(p)) {
                    return java.nio.file.Files.readString(p);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** In-memory region file: payload map plus flush/dirty bookkeeping, no IO. */
    private static class StubRegionFile implements AbstractRegionFile {
        final Map<Long, byte[]> chunks = new HashMap<>();
        int flushCalls;
        boolean marked = true;
        boolean closed;

        @Override
        public DataInputStream getChunkDataInputStream(long chunk) {
            byte[] payload = chunks.get(chunk);
            if (payload == null) {
                return null;
            }
            return new DataInputStream(new ByteArrayInputStream(payload.clone()));
        }

        @Override
        public void write(long chunk, ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.duplicate().get(copy);
            chunks.put(chunk, copy);
            marked = true;
        }

        @Override
        public boolean hasChunk(long chunk) {
            return chunks.containsKey(chunk);
        }

        @Override
        public void flush() {
            // Mirrors the real files (LinearRegionFile/BufferedLinearRegionFile
            // clear the flag when they snapshot): a flush without concurrent
            // writes leaves nothing marked. Concurrent writes re-mark after.
            marked = false;
            flushCalls++;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isMarkedToSave() {
            return marked;
        }

        @Override
        public void clearMarkedToSave() {
            marked = false;
        }
    }

    /**
     * Region file whose {@code flush()} parks until released: proves the
     * {@code markDirty} caller never blocks on file I/O. The park is bounded
     * by {@link #GATE_PARK_SECONDS} (a never-drained failure fails the test,
     * never hangs it; {@code @Test(timeout)} is the second net).
     */
    private static final class GatedRegionFile extends StubRegionFile {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean timedOut;

        @Override
        public void flush() {
            entered.countDown();
            try {
                if (!release.await(GATE_PARK_SECONDS, TimeUnit.SECONDS)) {
                    timedOut = true;
                    return;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                timedOut = true;
                return;
            }
            // Same snapshot-clear contract as StubRegionFile.flush().
            marked = false;
            flushCalls++;
        }
    }
}
