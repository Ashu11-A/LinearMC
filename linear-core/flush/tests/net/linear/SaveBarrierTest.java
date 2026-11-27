package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.linear.io.DirectoryFsync;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Forced flushes are real durability barriers: a forced flush never returns
 * while a flush of the same folder that started earlier on another thread
 * (dirty flag already cleared, rename not done) is still running. The
 * in-flight flush is held inside its directory fsync, which runs after the
 * rename, through the {@link DirectoryFsync} seam.
 */
public class SaveBarrierTest {

    private static final long REGION_CHUNK = ChunkKey.of(0, 0);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void pin() {
        LinearFlushCoordinator.linear$setFlushThreadsForTests(1);
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(3600L);
    }

    @After
    public void reset() {
        DirectoryFsync.setSyncerForTests(null);
        LinearFlushCoordinator.evictAll();
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    /** Blocks the first directory fsync of {@code dir} until released. */
    private static final class GatedSync implements DirectoryFsync.DirSyncer {
        final Path dir;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger syncs = new AtomicInteger();
        final AtomicBoolean gated = new AtomicBoolean(true);

        GatedSync(Path dir) {
            this.dir = dir.toAbsolutePath();
        }

        @Override
        public void sync(Path directory) throws IOException {
            if (directory.toAbsolutePath().equals(this.dir) && this.gated.compareAndSet(true, false)) {
                this.entered.countDown();
                try {
                    this.release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            this.syncs.incrementAndGet();
        }
    }

    private static Thread start(String name, AtomicReference<Throwable> failure, ThrowingRunnable body) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static byte[] readChunk(Path file) throws IOException {
        try (LinearRegionFile reopened = new LinearRegionFile(file, 3);
             DataInputStream in = reopened.getChunkDataInputStream(REGION_CHUNK)) {
            assertNotNull("chunk must be on disk", in);
            return in.readAllBytes();
        }
    }

    @Test
    public void forcedSnapshotWaitsForFlushRunningOnAnotherThread() throws Exception {
        Path folder = temporaryFolder.newFolder("dim", "region").toPath();
        Path file = folder.resolve("r.0.0.linear");
        GatedSync gate = new GatedSync(folder);
        DirectoryFsync.setSyncerForTests(gate);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (LinearRegionFile region = new LinearRegionFile(file, 3)) {
            region.write(REGION_CHUNK, ByteBuffer.wrap(new byte[] {1, 2, 3}));

            // A flush outside the barrier (e.g. a heartbeat drain) clears
            // dirty, renames, then sits in the directory fsync.
            Thread inFlight = start("in-flight", failure, region::flush);
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
            assertFalse("dirty is already cleared by the in-flight flush", region.isMarkedToSave());

            CountDownLatch barrierDone = new CountDownLatch(1);
            Thread barrier = start("barrier", failure, () -> {
                LinearFlushCoordinator.forFolder(folder)
                    .linear$flushSnapshot(Collections.singletonList(region), true);
                barrierDone.countDown();
            });
            assertFalse("forced snapshot returned before the in-flight flush was durable",
                barrierDone.await(300, TimeUnit.MILLISECONDS));

            gate.release.countDown();
            assertTrue(barrierDone.await(10, TimeUnit.SECONDS));
            inFlight.join(10_000);
            barrier.join(10_000);
            assertEquals(null, failure.get());
            assertEquals("directory fsync ran before the barrier returned", 1, gate.syncs.get());
        }
        assertEquals(3, readChunk(file).length);
    }

    @Test
    public void nonForcedSnapshotDoesNotWait() throws Exception {
        // Control: the join is specific to forced barriers.
        Path folder = temporaryFolder.newFolder("dim2", "region").toPath();
        Path file = folder.resolve("r.0.0.linear");
        GatedSync gate = new GatedSync(folder);
        DirectoryFsync.setSyncerForTests(gate);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (LinearRegionFile region = new LinearRegionFile(file, 3)) {
            region.write(REGION_CHUNK, ByteBuffer.wrap(new byte[] {4}));
            Thread inFlight = start("in-flight", failure, region::flush);
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
            LinearFlushCoordinator.forFolder(folder)
                .linear$flushSnapshot(Collections.singletonList(region), false);
            gate.release.countDown();
            inFlight.join(10_000);
            assertEquals(null, failure.get());
        }
    }

    @Test
    public void forcedFlushDirtyWithEmptyDirtySetWaitsForDetachedBatch() throws Exception {
        // Another thread's age-gated flushDirty detached the file (dirty set
        // now empty) and is mid-flush: the forced flush used to return on the
        // empty set at once.
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);
        Path folder = temporaryFolder.newFolder("dim3", "region").toPath();
        Path file = folder.resolve("r.0.0.linear");
        GatedSync gate = new GatedSync(folder);
        DirectoryFsync.setSyncerForTests(gate);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        LinearRegionFile region = new LinearRegionFile(file, 3);
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        region.write(REGION_CHUNK, ByteBuffer.wrap(new byte[] {5, 6}));
        assertEquals(1, coordinator.dirtyCount());

        Thread ageGated = start("age-gated", failure, coordinator::flushDirty);
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
        assertTrue("the batch is detached", coordinator.isEmpty());
        assertEquals(1, coordinator.linear$inFlightCountForTests());

        CountDownLatch barrierDone = new CountDownLatch(1);
        Thread barrier = start("barrier", failure, () -> {
            coordinator.flushDirty(true);
            barrierDone.countDown();
        });
        assertFalse("forced flushDirty returned before the detached flush was durable",
            barrierDone.await(300, TimeUnit.MILLISECONDS));
        gate.release.countDown();
        assertTrue(barrierDone.await(10, TimeUnit.SECONDS));
        ageGated.join(10_000);
        barrier.join(10_000);
        assertEquals(null, failure.get());
        assertEquals("in-flight registry drains", 0, coordinator.linear$inFlightCountForTests());
        region.close();
        assertEquals(2, readChunk(file).length);
    }

    @Test
    public void forcedFlushAllDirtyWaitsForBackgroundDrain() throws Exception {
        // flushAllDirty(true) is the save-all path: a fire-and-forget drain
        // started just before must be durable when it returns.
        Path folder = temporaryFolder.newFolder("dim4", "region").toPath();
        Path file = folder.resolve("r.0.0.linear");
        GatedSync gate = new GatedSync(folder);
        DirectoryFsync.setSyncerForTests(gate);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        LinearRegionFile region = new LinearRegionFile(file, 3);
        region.write(REGION_CHUNK, ByteBuffer.wrap(new byte[] {7}));
        LinearFlushCoordinator.flushDirtyAsync(true);
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS));

        CountDownLatch barrierDone = new CountDownLatch(1);
        Thread barrier = start("barrier", failure, () -> {
            LinearFlushCoordinator.flushAllDirty(true);
            barrierDone.countDown();
        });
        assertFalse(barrierDone.await(300, TimeUnit.MILLISECONDS));
        gate.release.countDown();
        assertTrue(barrierDone.await(10, TimeUnit.SECONDS));
        barrier.join(10_000);
        assertEquals(null, failure.get());
        assertEquals(0, LinearFlushCoordinator.forFolder(folder).linear$inFlightCountForTests());
        region.close();
        assertEquals(1, readChunk(file).length);
    }

    @Test
    public void forcedBarrierJoinsAnEvictionCloseThatADrainDetached() throws Exception {
        // RegionFileStorage evicts a cached file and calls close() outside the
        // coordinator. While close() sits in its directory fsync (dirty already
        // cleared), an aged drain detaches the file's stale coordinator entry.
        // The drain must keep the in-flight entry until close() is durable, or
        // a forced barrier started now would find the file nowhere and return
        // before the rename is on disk.
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);
        Path folder = temporaryFolder.newFolder("evict", "region").toPath();
        Path file = folder.resolve("r.0.0.linear");
        GatedSync gate = new GatedSync(folder);
        DirectoryFsync.setSyncerForTests(gate);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        LinearRegionFile region = new LinearRegionFile(file, 3);
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        region.write(REGION_CHUNK, ByteBuffer.wrap(new byte[] {9, 8, 7}));
        assertEquals(1, coordinator.dirtyCount());

        Thread closing = start("eviction-close", failure, region::close);
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
        assertFalse(region.isMarkedToSave());

        Thread drain = start("aged-drain", failure, () -> coordinator.flushDirty(false));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!coordinator.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("the drain detached the stale entry", coordinator.isEmpty());
        Thread.sleep(100);
        assertEquals("the drain keeps its in-flight entry until close() is durable",
            1, coordinator.linear$inFlightCountForTests());

        CountDownLatch barrierDone = new CountDownLatch(1);
        Thread barrier = start("barrier", failure, () -> {
            coordinator.flushDirty(true);
            barrierDone.countDown();
        });
        assertFalse("forced barrier returned before the eviction close was durable",
            barrierDone.await(300, TimeUnit.MILLISECONDS));
        gate.release.countDown();
        assertTrue(barrierDone.await(10, TimeUnit.SECONDS));
        closing.join(10_000);
        drain.join(10_000);
        barrier.join(10_000);
        assertEquals(null, failure.get());
        assertEquals(0, coordinator.linear$inFlightCountForTests());
        assertEquals(3, readChunk(file).length);
    }

    /** In-memory stub whose flush can fail once, after clearing dirty (like a real flush). */
    private static final class FlakyFile implements AbstractRegionFile {
        volatile boolean marked = true;
        final AtomicInteger flushes = new AtomicInteger();
        final AtomicInteger awaits = new AtomicInteger();
        final CountDownLatch inFlush = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final java.util.concurrent.locks.ReentrantLock guard = new java.util.concurrent.locks.ReentrantLock();

        @Override
        public DataInputStream getChunkDataInputStream(long chunk) {
            return null;
        }

        @Override
        public void write(long chunk, ByteBuffer data) {
            this.marked = true;
        }

        @Override
        public boolean hasChunk(long chunk) {
            return false;
        }

        @Override
        public void flush() throws IOException {
            this.guard.lock();
            try {
                if (!this.marked) {
                    return;
                }
                this.marked = false;
                int n = this.flushes.incrementAndGet();
                if (n == 1) {
                    this.inFlush.countDown();
                    try {
                        this.release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    this.marked = true; // failed flush re-arms dirty
                    throw new IOException("disk full");
                }
            } finally {
                this.guard.unlock();
            }
        }

        @Override
        public void awaitDurable() {
            this.awaits.incrementAndGet();
            this.guard.lock();
            this.guard.unlock();
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isMarkedToSave() {
            return this.marked;
        }

        @Override
        public void clearMarkedToSave() {
            this.marked = false;
        }
    }

    @Test
    public void forcedBarrierRetriesWhenTheJoinedFlushFailed() throws Exception {
        Path folder = temporaryFolder.newFolder("flaky").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        FlakyFile file = new FlakyFile();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread inFlight = start("in-flight", failure, file::flush);
        assertTrue(file.inFlush.await(10, TimeUnit.SECONDS));

        CountDownLatch barrierDone = new CountDownLatch(1);
        Thread barrier = start("barrier", failure, () -> {
            coordinator.linear$flushSnapshot(List.of(file), true);
            barrierDone.countDown();
        });
        assertFalse(barrierDone.await(200, TimeUnit.MILLISECONDS));
        file.release.countDown();
        assertTrue(barrierDone.await(10, TimeUnit.SECONDS));
        barrier.join(10_000);
        inFlight.join(10_000);
        assertTrue("the in-flight flush failed as scripted", failure.get() instanceof IOException);
        assertEquals("barrier joined the running flush", 1, file.awaits.get());
        assertEquals("barrier re-flushed after the failure", 2, file.flushes.get());
        assertFalse(file.isMarkedToSave());
    }

    @Test
    public void awaitDurableOnIdleFileReturnsAtOnce() throws Exception {
        Path folder = temporaryFolder.newFolder("idle", "region").toPath();
        try (LinearRegionFile region = new LinearRegionFile(folder.resolve("r.0.0.linear"), 3)) {
            long start = System.nanoTime();
            region.awaitDurable();
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
        }
    }

    @Test
    public void inFlightRegistryDrainsAfterBackgroundBatches() throws Exception {
        Path folder = temporaryFolder.newFolder("drain", "region").toPath();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(folder);
        for (int i = 0; i < 4; i++) {
            try (LinearRegionFile region = new LinearRegionFile(folder.resolve("r." + i + ".0.linear"), 3)) {
                region.write(ChunkKey.of(i * 32, 0), ByteBuffer.wrap(new byte[] {(byte) i}));
                LinearFlushCoordinator.flushDirtyAsync(true);
                LinearFlushCoordinator.linear$joinInFlightAsync();
            }
        }
        assertEquals(0, coordinator.linear$inFlightCountForTests());
        assertTrue(Files.isRegularFile(folder.resolve("r.3.0.linear")));
    }
}
