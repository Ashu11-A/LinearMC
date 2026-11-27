package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.linear.io.DirectoryFsync;
import net.linear.save.SaveAllBarrier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The save-all ticket: seal guard, per-region dedupe, the forced barrier
 * before the marker, the marker shape and id continuity, and the end-to-end
 * case where a real flush is still inside its directory fsync.
 */
public class SaveAllMarkerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void pin() {
        SaveAllBarrier.resetIdsForTests();
        LinearFlushCoordinator.linear$setFlushThreadsForTests(1);
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(3600L);
    }

    @After
    public void reset() {
        SaveAllBarrier.setBarrierForTests(null);
        SaveAllBarrier.resetIdsForTests();
        DirectoryFsync.setSyncerForTests(null);
        LinearFlushCoordinator.evictAll();
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    private static SaveAllBarrier.Result await(final AtomicReference<SaveAllBarrier.Result> ref,
                                               final CountDownLatch done) throws InterruptedException {
        assertTrue("save-all never completed", done.await(10, TimeUnit.SECONDS));
        return ref.get();
    }

    @Test
    public void taskFinishingBeforeSealNeverCompletesTheTicket() throws Exception {
        final Path level = this.temporaryFolder.newFolder("world").toPath();
        final SaveAllBarrier.Ticket ticket = SaveAllBarrier.begin(level, true);
        ticket.taskQueued();
        ticket.taskDone(); // a region finished while the leg was still scheduling
        ticket.taskQueued();
        Thread.sleep(100);
        assertFalse("marker written before seal", Files.exists(SaveAllBarrier.markerPath(level)));

        final AtomicReference<SaveAllBarrier.Result> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        ticket.seal(r -> {
            result.set(r);
            done.countDown();
        });
        assertFalse("completed while a task was still pending", done.await(150, TimeUnit.MILLISECONDS));
        ticket.taskDone();
        assertTrue(await(result, done).markerWritten());
    }

    @Test
    public void markerIsWrittenAfterTheBarrierWithTheExpectedFields() throws Exception {
        final Path level = this.temporaryFolder.newFolder("world").toPath();
        final AtomicBoolean markerPresentDuringBarrier = new AtomicBoolean(true);
        SaveAllBarrier.setBarrierForTests(
            () -> markerPresentDuringBarrier.set(Files.exists(SaveAllBarrier.markerPath(level))));
        final SaveAllBarrier.Ticket ticket = SaveAllBarrier.begin(level, true);
        final Object regionA = new Object();
        final Object regionB = new Object();
        for (final Object region : new Object[] {regionA, regionA, regionB}) {
            ticket.taskQueued();
            if (ticket.claimRegion(region) && region == regionB) {
                ticket.regionFailed();
            }
            ticket.taskDone();
        }
        final AtomicReference<SaveAllBarrier.Result> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        ticket.seal(r -> {
            result.set(r);
            done.countDown();
        });
        final SaveAllBarrier.Result r = await(result, done);
        assertFalse("marker existed before the barrier ran", markerPresentDuringBarrier.get());
        assertEquals(2, r.regions());
        assertEquals(1, r.failed());
        assertTrue(r.endMs() >= r.startMs());
        final String json = Files.readString(SaveAllBarrier.markerPath(level), StandardCharsets.UTF_8);
        assertEquals("{\"id\":1,\"startMs\":" + r.startMs() + ",\"endMs\":" + r.endMs()
            + ",\"regions\":2,\"failed\":1}\n", json);
        try (var siblings = Files.list(SaveAllBarrier.markerPath(level).getParent())) {
            assertEquals("no temp file left beside the marker", 1, siblings.count());
        }
    }

    /** A fake chunk I/O task list: fences whose state the test sets. */
    private static final class FenceHost implements net.linear.save.CoupledSave.Host {
        final java.util.List<int[]> tasks = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void snapshotInFlight(final java.util.List<Object> out) {
            out.addAll(this.tasks);
        }

        @Override
        public int fenceState(final Object task) {
            return ((int[]) task)[0];
        }

        @Override
        public void flushStorages() {
        }
    }

    private static SaveAllBarrier.Result sealAndAwait(final SaveAllBarrier.Ticket ticket, final long millis)
        throws InterruptedException {
        final AtomicReference<SaveAllBarrier.Result> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        ticket.seal(r -> {
            result.set(r);
            done.countDown();
        });
        return done.await(millis, TimeUnit.MILLISECONDS) ? result.get() : null;
    }

    @Test
    public void markerWaitsForChunkWritesStillQueuedWhenTheLastRegionFinished() throws Exception {
        // A region task that only queues chunk saves (a coupled capture) is done
        // before its writes reach the region files: the forced flush must not
        // run, and the marker must not appear, until those writes landed.
        final Path level = this.temporaryFolder.newFolder("world").toPath();
        final FenceHost fences = new FenceHost();
        final int[] queuedWrite = {net.linear.save.CoupledSave.PENDING};
        fences.tasks.add(queuedWrite);
        final AtomicBoolean flushed = new AtomicBoolean();
        SaveAllBarrier.setBarrierForTests(() -> flushed.set(true));
        final SaveAllBarrier.Ticket ticket = SaveAllBarrier.begin(level, true, fences, 10_000L);
        ticket.taskQueued();
        ticket.claimRegion(new Object());
        ticket.taskDone();

        final AtomicReference<SaveAllBarrier.Result> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        ticket.seal(r -> {
            result.set(r);
            done.countDown();
        });
        assertFalse("completed while a chunk write was still queued", done.await(200, TimeUnit.MILLISECONDS));
        assertFalse("forced flush ran before the chunk write landed", flushed.get());
        assertFalse(Files.exists(SaveAllBarrier.markerPath(level)));

        queuedWrite[0] = net.linear.save.CoupledSave.DONE;
        final SaveAllBarrier.Result r = await(result, done);
        assertTrue(flushed.get());
        assertEquals(0, r.failed());
        assertTrue(r.markerWritten());
    }

    @Test
    public void failedOrStuckChunkWriteFailsTheSaveAll() throws Exception {
        final Path level = this.temporaryFolder.newFolder("world").toPath();
        SaveAllBarrier.setBarrierForTests(() -> { });
        final FenceHost failing = new FenceHost();
        failing.tasks.add(new int[] {net.linear.save.CoupledSave.FAILED});
        final SaveAllBarrier.Result failed = sealAndAwait(SaveAllBarrier.begin(level, true, failing, 10_000L), 10_000L);
        assertNotNull(failed);
        assertEquals("a failed chunk write means do not trust this cut", 1, failed.failed());

        final FenceHost stuck = new FenceHost();
        stuck.tasks.add(new int[] {net.linear.save.CoupledSave.PENDING});
        final long start = System.nanoTime();
        final SaveAllBarrier.Result timedOut = sealAndAwait(SaveAllBarrier.begin(level, true, stuck, 150L), 10_000L);
        assertNotNull(timedOut);
        assertEquals("the wait budget bounds the fence wait", 1, timedOut.failed());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    public void idsIncreaseAndContinueFromTheExistingMarker() throws Exception {
        final Path level = this.temporaryFolder.newFolder("world").toPath();
        Files.createDirectories(SaveAllBarrier.markerPath(level).getParent());
        Files.writeString(SaveAllBarrier.markerPath(level),
            "{\"id\":41,\"startMs\":1,\"endMs\":2,\"regions\":0,\"failed\":0}\n");
        assertEquals(42, SaveAllBarrier.begin(level, false).id());
        assertEquals(43, SaveAllBarrier.begin(level, true).id());
    }

    @Test
    public void plainSaveAllHasNoBarrierAndNoMarker() throws Exception {
        final Path level = this.temporaryFolder.newFolder("world").toPath();
        final AtomicBoolean barrierRan = new AtomicBoolean();
        SaveAllBarrier.setBarrierForTests(() -> barrierRan.set(true));
        final SaveAllBarrier.Ticket ticket = SaveAllBarrier.begin(level, false);
        final AtomicReference<SaveAllBarrier.Result> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        ticket.seal(r -> {
            result.set(r);
            done.countDown();
        });
        assertFalse(await(result, done).markerWritten());
        assertFalse(barrierRan.get());
        assertFalse(Files.exists(SaveAllBarrier.markerPath(level)));
    }

    @Test
    public void markerWaitsForAFlushStillInsideItsDirectoryFsync() throws Exception {
        final Path level = this.temporaryFolder.newFolder("world").toPath();
        final Path folder = Files.createDirectories(level.resolve("region"));
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean gated = new AtomicBoolean(true);
        DirectoryFsync.setSyncerForTests(directory -> {
            if (directory.toAbsolutePath().equals(folder.toAbsolutePath()) && gated.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        try (LinearRegionFile region = new LinearRegionFile(folder.resolve("r.0.0.linear"), 3)) {
            region.write(ChunkKey.of(0, 0), ByteBuffer.wrap(new byte[] {7, 8, 9}));
            // A flush the save-all did not start (the heartbeat) is between
            // its rename and its directory fsync.
            final Thread inFlight = new Thread(() -> {
                try {
                    region.flush();
                } catch (IOException e) {
                    failure.set(e);
                }
            }, "in-flight");
            inFlight.setDaemon(true);
            inFlight.start();
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            final SaveAllBarrier.Ticket ticket = SaveAllBarrier.begin(level, true);
            final AtomicReference<SaveAllBarrier.Result> result = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            ticket.seal(r -> {
                result.set(r);
                done.countDown();
            });
            assertFalse("save-all completed while a region rename was not durable",
                done.await(300, TimeUnit.MILLISECONDS));
            assertFalse(Files.exists(SaveAllBarrier.markerPath(level)));
            release.countDown();
            final SaveAllBarrier.Result r = await(result, done);
            assertNotNull(r);
            assertTrue(r.markerWritten());
            inFlight.join(10_000);
            assertEquals(null, failure.get());
        }
    }
}
