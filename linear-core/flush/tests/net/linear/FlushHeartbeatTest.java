package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import net.linear.flush.FlushHeartbeat;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Background heartbeat: dirty regions persist without save/stop (crash safety). */
public class FlushHeartbeatTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setUp() {
        LinearFlushCoordinator.evictAll();
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
        LinearFlushCoordinator.linear$setFlushThreadsForTests(1);
    }

    @After
    public void tearDown() {
        LinearFlushCoordinator.evictAll();
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    private static int heartbeatThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (FlushHeartbeat.THREAD_NAME.equals(t.getName()) && t.isAlive()) {
                n++;
            }
        }
        return n;
    }

    private static boolean await(java.util.function.BooleanSupplier cond, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }

    @Test
    public void notStartedWithoutTrackedFolder() {
        assertFalse(FlushHeartbeat.isRunning());
    }

    @Test(timeout = 20_000)
    public void startsOnceAndIsIdempotent() throws Exception {
        LinearFlushCoordinator.forFolder(temporaryFolder.newFolder("a").toPath());
        LinearFlushCoordinator.forFolder(temporaryFolder.newFolder("b").toPath());
        assertTrue(FlushHeartbeat.isRunning());
        assertTrue(await(() -> heartbeatThreads() <= 1, 2000));
    }

    @Test(timeout = 20_000)
    public void flushesDirtyFileWithoutExplicitDrain() throws Exception {
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(1L);
        LinearFlushCoordinator.linear$enableHeartbeatForTests();
        Path folder = temporaryFolder.newFolder("region").toPath();
        CountingFile file = new CountingFile();
        LinearFlushCoordinator.forFolder(folder).markDirty(file);
        assertTrue("heartbeat must persist within F+1s", await(() -> file.flushes.get() >= 1, 4000));
        assertEquals(1, file.flushes.get());
    }

    @Test(timeout = 20_000)
    public void ageGateHolds() throws Exception {
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(3600L);
        LinearFlushCoordinator.linear$enableHeartbeatForTests();
        CountingFile file = new CountingFile();
        LinearFlushCoordinator.forFolder(temporaryFolder.newFolder("young").toPath()).markDirty(file);
        Thread.sleep(2500);
        assertEquals(0, file.flushes.get());
    }

    @Test(timeout = 20_000)
    public void stopBarrierStopsHeartbeatForGood() throws Exception {
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(3600L);
        LinearFlushCoordinator.linear$enableHeartbeatForTests();
        Path folder = temporaryFolder.newFolder("stop").toPath();
        LinearFlushCoordinator.forFolder(folder);
        assertTrue(FlushHeartbeat.isRunning());
        LinearFlushCoordinator.linear$beginShutdown();
        assertFalse(FlushHeartbeat.isRunning());
        assertTrue(await(() -> heartbeatThreads() == 0, 3000));
        LinearFlushCoordinator.forFolder(temporaryFolder.newFolder("late").toPath());
        FlushHeartbeat.ensureStarted(() -> { });
        assertFalse("no restart after the stop barrier", FlushHeartbeat.isRunning());
        LinearFlushCoordinator.shutdownFlushPool();
    }

    @Test(timeout = 30_000)
    public void concurrentMarksWithHeartbeatLoseNothing() throws Exception {
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(1L);
        LinearFlushCoordinator.linear$enableHeartbeatForTests();
        final LinearFlushCoordinator coordinator =
            LinearFlushCoordinator.forFolder(temporaryFolder.newFolder("mt").toPath());
        final List<CountingFile> files = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            files.add(new CountingFile());
        }
        final CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final int base = t * 8;
            Thread th = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 8; i++) {
                    coordinator.markDirty(files.get(base + i));
                }
            });
            th.start();
            threads.add(th);
        }
        go.countDown();
        for (Thread th : threads) {
            th.join();
        }
        assertTrue(await(() -> files.stream().allMatch(f -> f.flushes.get() >= 1), 6000));
        for (CountingFile f : files) {
            assertEquals("no double flush of a single mark", 1, f.flushes.get());
        }
        assertEquals(0, coordinator.dirtyCount());
    }

    private static final class CountingFile implements AbstractRegionFile {
        final AtomicInteger flushes = new AtomicInteger();
        volatile boolean marked = true;

        @Override public DataInputStream getChunkDataInputStream(long chunk) { return null; }
        @Override public void write(long chunk, ByteBuffer data) { marked = true; }
        @Override public boolean hasChunk(long chunk) { return false; }
        @Override public void flush() { marked = false; flushes.incrementAndGet(); }
        @Override public void close() { }
        @Override public boolean isMarkedToSave() { return marked; }
        @Override public void clearMarkedToSave() { marked = false; }
    }
}
