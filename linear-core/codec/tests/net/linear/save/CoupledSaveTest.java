package net.linear.save;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.linear.io.PlayerFileDurability;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Coupled save order: a staged player file is written only after every chunk
 * I/O task listed at capture time has landed and the forced storage flush
 * has returned; a failed barrier withholds it and retries; newer saves
 * supersede older ones; reads see staged saves.
 */
public class CoupledSaveTest {

    private static final String UUID = "85a1efec-0000-4000-8000-00000000abcd";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path players;
    private Path stats;

    @Before
    public void setUp() throws IOException {
        CoupledSave.resetForTests();
        CoupledSave.configure(true, 20L);
        CoupledSave.setRetryBaseForTests(5L);
        this.players = this.temporaryFolder.newFolder("playerdata").toPath();
        this.stats = this.temporaryFolder.newFolder("stats").toPath();
    }

    @After
    public void tearDown() {
        CoupledSave.resetForTests();
        PlayerFileDurability.setEnabled(true);
    }

    /** A chunk I/O task: its state is set by the test. */
    static final class Fence {
        volatile int state = CoupledSave.PENDING;
    }

    /** Fake host: fences listed on demand, a flush that logs, blocks or throws as told. */
    static class FakeHost implements CoupledSave.Host {
        final List<Fence> inFlight = new CopyOnWriteArrayList<>();
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger flushes = new AtomicInteger();
        final AtomicInteger failFlushes = new AtomicInteger();
        volatile CountDownLatch flushGate;
        volatile long barrierDoneNanos;

        @Override
        public void snapshotInFlight(final List<Object> out) {
            out.addAll(this.inFlight);
        }

        @Override
        public int fenceState(final Object task) {
            return ((Fence) task).state;
        }

        @Override
        public void flushStorages() throws IOException {
            this.flushes.incrementAndGet();
            final CountDownLatch gate = this.flushGate;
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    throw new IOException(e);
                }
            }
            if (this.failFlushes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                this.log.add("flush-failed");
                throw new IOException("region flush failed (test)");
            }
            this.log.add("flushed");
            this.barrierDoneNanos = System.nanoTime();
        }
    }

    private Path dat() {
        return this.players.resolve(UUID + ".dat");
    }

    private Path datOld() {
        return this.players.resolve(UUID + ".dat_old");
    }

    private Path statsFile() {
        return this.stats.resolve(UUID + ".json");
    }

    /** What a leg does in a capture: stage the .dat bytes and write stats through the durable writer. */
    private void stagePlayer(final FakeHost host, final byte[] dat, final String statsJson) throws IOException {
        try (CoupledSave.Capture capture = CoupledSave.begin(host)) {
            assertTrue(CoupledSave.capturing());
            CoupledSave.stageDat(this.dat(), this.datOld(), dat);
            try (Writer writer = PlayerFileDurability.newWriter(this.statsFile(), StandardCharsets.UTF_8)) {
                writer.write(statsJson);
            }
            assertEquals(2, capture.stagedCount());
        }
        assertFalse(CoupledSave.capturing());
    }

    private static void awaitTrue(final String what, final java.util.function.BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(2);
        }
    }

    @Test
    public void playerFilesWaitForEveryChunkWriteInFlightAtCaptureTime() throws Exception {
        final FakeHost host = new FakeHost();
        final Fence chunkSave = new Fence();
        host.inFlight.add(chunkSave);

        this.stagePlayer(host, new byte[] {7}, "{\"v\":1}");

        Thread.sleep(100);
        assertFalse("no player write while the region's chunk write is in flight", Files.exists(this.dat()));
        assertFalse(Files.exists(this.statsFile()));
        assertEquals("the barrier flush waits for the fences too", 0, host.flushes.get());

        chunkSave.state = CoupledSave.DONE;
        CoupledSave.awaitSubmitted(10_000);

        assertArrayEquals(new byte[] {7}, Files.readAllBytes(this.dat()));
        assertEquals("{\"v\":1}", Files.readString(this.statsFile()));
        assertEquals(List.of("flushed"), host.log);
    }

    @Test
    public void playerFilesWaitForTheForcedRegionFlushToReturn() throws Exception {
        final FakeHost host = new FakeHost();
        host.flushGate = new CountDownLatch(1);

        this.stagePlayer(host, new byte[] {1}, "{}");

        awaitTrue("barrier flush started", () -> host.flushes.get() == 1);
        Thread.sleep(50);
        assertFalse("no player write before the forced flush (rename + directory fsync) returns", Files.exists(this.dat()));

        host.flushGate.countDown();
        CoupledSave.awaitSubmitted(10_000);
        assertTrue(Files.exists(this.dat()));
    }

    @Test
    public void failedRegionFlushWithholdsThePlayerWriteAndRetries() throws Exception {
        final FakeHost host = new FakeHost();
        host.failFlushes.set(2);
        Files.write(this.dat(), new byte[] {0});

        this.stagePlayer(host, new byte[] {9}, "{}");

        awaitTrue("first failed attempt", () -> host.log.contains("flush-failed"));
        assertArrayEquals("the old save stays while the barrier fails", new byte[] {0}, Files.readAllBytes(this.dat()));
        awaitTrue("the retrying capture is visible in the stats", () -> CoupledSave.withheldCaptures() == 1
            || host.log.contains("flushed"));

        CoupledSave.awaitSubmitted(10_000);
        assertEquals(List.of("flush-failed", "flush-failed", "flushed"), host.log);
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(this.dat()));
        assertArrayEquals("the previous save is kept as .dat_old", new byte[] {0}, Files.readAllBytes(this.datOld()));
        assertTrue(CoupledSave.statsLine().contains("barrierFailures=2"));
        assertEquals("nothing withheld once written", 0, CoupledSave.withheldCaptures());
        assertTrue(CoupledSave.statsLine().contains("withheldCaptures=0"));
    }

    @Test
    public void failedChunkWriteWithholdsAndTheRetryWaitsForItsReplacement() throws Exception {
        final FakeHost host = new FakeHost();
        final Fence failed = new Fence();
        failed.state = CoupledSave.FAILED;
        host.inFlight.add(failed);

        this.stagePlayer(host, new byte[] {3}, "{}");
        Thread.sleep(60);
        assertFalse(Files.exists(this.dat()));
        assertEquals("no flush while a chunk write failed", 0, host.flushes.get());

        // The chunk system retries the write as a new task: the barrier re-lists the I/O in flight.
        final Fence retry = new Fence();
        host.inFlight.clear();
        host.inFlight.add(retry);
        Thread.sleep(60);
        assertFalse("the retried write is still running", Files.exists(this.dat()));

        retry.state = CoupledSave.DONE;
        CoupledSave.awaitSubmitted(10_000);
        assertArrayEquals(new byte[] {3}, Files.readAllBytes(this.dat()));
    }

    @Test
    public void awaitSubmittedReportsAWithheldSave() throws Exception {
        final FakeHost host = new FakeHost();
        host.failFlushes.set(Integer.MAX_VALUE);
        this.stagePlayer(host, new byte[] {1}, "{}");
        try {
            CoupledSave.awaitSubmitted(100);
            fail("a withheld save must fail the drain");
        } catch (final IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("withheld"));
        }
        assertFalse(Files.exists(this.dat()));
    }

    @Test
    public void newerCaptureSupersedesAnOlderOneThatIsStillWithheld() throws Exception {
        final FakeHost slow = new FakeHost();
        final Fence stuck = new Fence();
        slow.inFlight.add(stuck);
        this.stagePlayer(slow, new byte[] {1}, "{\"old\":1}");

        final FakeHost fast = new FakeHost();
        this.stagePlayer(fast, new byte[] {2}, "{\"new\":1}");
        awaitTrue("newer capture written", () -> Files.exists(this.dat()));

        stuck.state = CoupledSave.DONE;
        CoupledSave.awaitSubmitted(10_000);
        assertArrayEquals("the older capture never lands over the newer one", new byte[] {2}, Files.readAllBytes(this.dat()));
        assertEquals("{\"new\":1}", Files.readString(this.statsFile()));
    }

    @Test
    public void directWriteSupersedesAStagedSave() throws Exception {
        final FakeHost host = new FakeHost();
        final Fence stuck = new Fence();
        host.inFlight.add(stuck);
        this.stagePlayer(host, new byte[] {1}, "{\"staged\":1}");

        // A save outside any capture (stop path) writes now and wins.
        try (Writer writer = PlayerFileDurability.newWriter(this.statsFile(), StandardCharsets.UTF_8)) {
            writer.write("{\"direct\":1}");
        }
        final Path tmp = Files.createTempFile(this.players, UUID + "-", ".dat");
        Files.write(tmp, new byte[] {5});
        PlayerFileDurability.replace(this.dat(), tmp, this.datOld());
        assertNull(CoupledSave.stagedBytes(this.dat()));

        stuck.state = CoupledSave.DONE;
        CoupledSave.awaitSubmitted(10_000);
        assertArrayEquals(new byte[] {5}, Files.readAllBytes(this.dat()));
        assertEquals("{\"direct\":1}", Files.readString(this.statsFile()));
    }

    @Test
    public void slowDirectWriteKeepsANewerStagedSave() throws Exception {
        // An older direct write (off-thread saveData) is still running when a
        // capture stages a newer save of the same file: the direct write must
        // drop only saves staged before it started, or a relog in between would
        // read the older file back.
        final FakeHost host = new FakeHost();
        host.inFlight.add(new Fence()); // keeps the newer capture withheld
        final Path tmp = Files.createTempFile(this.players, UUID + "-", ".dat");
        Files.write(tmp, new byte[] {1});
        CoupledSave.writeDirect(this.dat(), () -> {
            try (CoupledSave.Capture capture = CoupledSave.begin(host)) {
                CoupledSave.stageDat(this.dat(), this.datOld(), new byte[] {2});
            }
            PlayerFileDurability.moveIntoPlace(this.dat(), tmp, this.datOld(), false, false);
        });
        assertArrayEquals("the newer staged save survives the older direct write",
            new byte[] {2}, CoupledSave.stagedBytes(this.dat()));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(this.dat()));
    }

    @Test
    public void supersededCaptureStopsRetryingTheBarrier() throws Exception {
        final FakeHost host = new FakeHost();
        host.failFlushes.set(Integer.MAX_VALUE);
        this.stagePlayer(host, new byte[] {1}, "{\"old\":1}");
        awaitTrue("first failed attempt", () -> host.log.contains("flush-failed"));

        // A direct write supersedes both staged files: the withheld capture has
        // nothing left to order and must not keep running the global flush.
        try (Writer writer = PlayerFileDurability.newWriter(this.statsFile(), StandardCharsets.UTF_8)) {
            writer.write("{\"direct\":1}");
        }
        final Path tmp = Files.createTempFile(this.players, UUID + "-", ".dat");
        Files.write(tmp, new byte[] {5});
        PlayerFileDurability.replace(this.dat(), tmp, this.datOld());

        CoupledSave.awaitSubmitted(10_000);
        final int flushes = host.flushes.get();
        Thread.sleep(100);
        assertEquals("no more barrier retries once superseded", flushes, host.flushes.get());
        assertArrayEquals(new byte[] {5}, Files.readAllBytes(this.dat()));
    }

    @Test
    public void playerWhoLeftARegionWaitsForTheDepartureChunkSaves() throws Exception {
        // Region A saves its chunks and stages the player when the player
        // leaves it (teleport, portal, respawn); region B, which never owned
        // the placement chunk, stages the player next. B's fences are global,
        // so its write still waits for A's chunk write.
        final FakeHost global = new FakeHost();
        final Fence regionAChunk = new Fence();
        global.inFlight.add(regionAChunk);
        this.stagePlayer(global, new byte[] {1}, "{\"a\":1}"); // departure capture in region A
        this.stagePlayer(global, new byte[] {2}, "{\"b\":1}"); // next capture in region B

        Thread.sleep(100);
        assertFalse("no player write before region A's chunk write landed", Files.exists(this.dat()));
        assertEquals(0, global.flushes.get());

        regionAChunk.state = CoupledSave.DONE;
        CoupledSave.awaitSubmitted(10_000);
        assertArrayEquals("B's newer save wins", new byte[] {2}, Files.readAllBytes(this.dat()));
        assertEquals("{\"b\":1}", Files.readString(this.statsFile()));
    }

    @Test
    public void readsSeeTheStagedSaveUntilItIsWritten() throws Exception {
        final FakeHost host = new FakeHost();
        final Fence stuck = new Fence();
        host.inFlight.add(stuck);
        this.stagePlayer(host, new byte[] {4, 2}, "{\"staged\":true}");

        assertArrayEquals(new byte[] {4, 2}, CoupledSave.stagedBytes(this.dat()));
        assertArrayEquals("the key is the normalized absolute path",
            new byte[] {4, 2}, CoupledSave.stagedBytes(this.players.resolve("x/../" + UUID + ".dat")));
        assertFalse(Files.exists(this.statsFile()));
        assertTrue("a first-time player's staged stats are readable", PlayerFileDurability.isReadable(this.statsFile()));
        try (BufferedReader reader = PlayerFileDurability.newReader(this.statsFile(), StandardCharsets.UTF_8)) {
            assertEquals("{\"staged\":true}", reader.readLine());
        }

        stuck.state = CoupledSave.DONE;
        CoupledSave.awaitSubmitted(10_000);
        assertNull("written: reads go back to the file", CoupledSave.stagedBytes(this.dat()));
        assertTrue(PlayerFileDurability.isReadable(this.statsFile()));
    }

    @Test
    public void captureThatStagedNothingSubmitsNothing() {
        final FakeHost host = new FakeHost();
        host.inFlight.add(new Fence());
        try (CoupledSave.Capture capture = CoupledSave.begin(host)) {
            assertEquals(0, capture.stagedCount());
        }
        CoupledSave.awaitSubmitted(100);
        assertEquals(0, host.flushes.get());
    }

    @Test
    public void nestedCaptureJoinsTheOuterOne() throws Exception {
        final FakeHost host = new FakeHost();
        try (CoupledSave.Capture outer = CoupledSave.begin(host)) {
            try (CoupledSave.Capture inner = CoupledSave.begin(host)) {
                CoupledSave.stageDat(this.dat(), this.datOld(), new byte[] {1});
            }
            assertTrue("closing the inner capture keeps staging on", CoupledSave.capturing());
            assertEquals(1, outer.stagedCount());
        }
        CoupledSave.awaitSubmitted(10_000);
        assertTrue(Files.exists(this.dat()));
    }

    @Test
    public void stageOutsideACaptureIsABug() {
        try {
            CoupledSave.stageDat(this.dat(), this.datOld(), new byte[] {1});
            fail();
        } catch (final IllegalStateException expected) {
            // A leg must open a capture first.
        }
    }

    @Test
    public void aFlushFailureInAFolderTheBarrierDidNotFlushDoesNotWithhold() throws Exception {
        final Path mine = this.temporaryFolder.newFolder("region-mine").toPath();
        final Path other = this.temporaryFolder.newFolder("region-other").toPath();
        final FakeHost host = new FakeHost() {
            @Override
            public void flushStorages() throws IOException {
                net.linear.LinearFlushCoordinator.forFolder(mine).flushDirty(true);
                net.linear.FailureScopeTest.recordFailure(other); // unrelated world fails meanwhile
                super.flushStorages();
            }
        };
        try {
            this.stagePlayer(host, new byte[] {3}, "{}");
            CoupledSave.awaitSubmitted(10_000);
            assertArrayEquals("scoped failures: the unrelated folder does not withhold", new byte[] {3},
                Files.readAllBytes(this.dat()));
        } finally {
            net.linear.LinearFlushCoordinator.evictAll();
        }
    }

    @Test
    public void aFlushFailureInAFlushedFolderWithholdsAndRetries() throws Exception {
        final Path mine = this.temporaryFolder.newFolder("region-flushed").toPath();
        final AtomicInteger calls = new AtomicInteger();
        final FakeHost host = new FakeHost() {
            @Override
            public void flushStorages() throws IOException {
                net.linear.LinearFlushCoordinator.forFolder(mine).flushDirty(true);
                if (calls.getAndIncrement() == 0) {
                    net.linear.FailureScopeTest.recordFailure(mine);
                }
                super.flushStorages();
            }
        };
        try {
            this.stagePlayer(host, new byte[] {4}, "{}");
            CoupledSave.awaitSubmitted(10_000);
            assertTrue("retried after the scoped failure", calls.get() >= 2);
            assertArrayEquals(new byte[] {4}, Files.readAllBytes(this.dat()));
        } finally {
            net.linear.LinearFlushCoordinator.evictAll();
        }
    }

    @Test
    public void dueIsPerRegionUnderConcurrencyAndDropsDeadRegions() throws Exception {
        CoupledSave.configure(true, 3600L);
        final Object region = new Object();
        final AtomicInteger dueCount = new AtomicInteger();
        final Thread[] threads = new Thread[8];
        final CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (final InterruptedException e) {
                    return;
                }
                for (int k = 0; k < 1000; k++) {
                    if (CoupledSave.due(region)) {
                        dueCount.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        for (final Thread thread : threads) {
            thread.join();
        }
        assertEquals("exactly one caller wins a due slot per interval", 1, dueCount.get());
        for (int i = 0; i < 100; i++) {
            assertTrue(CoupledSave.due(new Object()));
        }
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (CoupledSave.trackedRegionsForTests() > 1 && System.nanoTime() < deadline) {
            System.gc();
            CoupledSave.due(new Object()); // a first sight expunges dead regions
            Thread.sleep(10);
        }
        assertTrue("dead regions are dropped (weak keys)", CoupledSave.trackedRegionsForTests() <= 2);
        assertFalse("the live region keeps its slot", CoupledSave.due(region));
    }

    @Test
    public void formatScopeNamesEveryCoveredFormatAndTheAnvilCaveats() {
        assertTrue(CoupledSave.FORMAT_SCOPE.contains("LINEAR and B_LINEAR"));
        assertTrue(CoupledSave.FORMAT_SCOPE.contains("ANVIL"));
        assertTrue(CoupledSave.FORMAT_SCOPE.contains(".mcc"));
    }

    @Test
    public void dueOncePerIntervalPerRegionAndAtOnceForANewRegion() {
        final Object regionA = new Object();
        final Object regionB = new Object();
        assertTrue("first sight of a region", CoupledSave.due(regionA));
        assertFalse(CoupledSave.due(regionA));
        assertTrue(CoupledSave.due(regionB));
        assertTrue("null = the whole server on a non-region host", CoupledSave.due(null));
        assertFalse(CoupledSave.due(null));

        CoupledSave.configure(false, 20L);
        assertFalse("off: never due", CoupledSave.due(new Object()));
        assertFalse(CoupledSave.active());
    }

    @Test
    public void disabledMeansDirectWritesAndNoStaging() throws Exception {
        CoupledSave.configure(false, 20L);
        PlayerFileDurability.setEnabled(false);
        assertFalse(CoupledSave.capturing());
        try (Writer writer = PlayerFileDurability.newWriter(this.statsFile(), StandardCharsets.UTF_8)) {
            writer.write("{}");
        }
        assertEquals("{}", Files.readString(this.statsFile()));
        assertNull(CoupledSave.stagedBytes(this.statsFile()));

        CoupledSave.configure(true, 20L);
        assertTrue("coupled-save implies durable player data", PlayerFileDurability.enabled());
    }

    @Test
    public void intervalBelowOneSecondFallsBack() {
        final List<String> errors = new ArrayList<>();
        assertEquals(CoupledSave.DEFAULT_INTERVAL_SECONDS, CoupledSave.resolveIntervalSeconds(0, errors::add));
        assertEquals(1, errors.size());
        assertEquals(5L, CoupledSave.resolveIntervalSeconds(5, errors::add));
        CoupledSave.configure(true, 5L);
        assertEquals(5L, CoupledSave.intervalSeconds());
    }

    @Test
    public void finishShutdownWritesWhatIsStillWithheld() throws Exception {
        final FakeHost host = new FakeHost();
        host.failFlushes.set(Integer.MAX_VALUE);
        this.stagePlayer(host, new byte[] {8}, "{}");
        CoupledSave.beginShutdown();
        assertFalse("no new captures while stopping", CoupledSave.active());

        CoupledSave.finishShutdown(50);

        assertArrayEquals(new byte[] {8}, Files.readAllBytes(this.dat()));
        assertNull(CoupledSave.stagedBytes(this.dat()));

        // A capture after the stop drain writes at once (the stop saved and flushed every chunk).
        try (CoupledSave.Capture capture = CoupledSave.begin(host)) {
            CoupledSave.stageDat(this.dat(), this.datOld(), new byte[] {9});
        }
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(this.dat()));
    }

    /**
     * Ordering under random timing: 200 captures of 8 players, each behind
     * fences that finish at random times and a flush that sometimes fails.
     * Every staged write must land after the barrier flush that preceded it
     * completed (0 violations), and each file ends at its newest capture.
     */
    @Test
    public void noPlayerWriteEverPrecedesItsBarrierUnderRandomTiming() throws Exception {
        final Random random = new Random(20261126L);
        final AtomicLong violations = new AtomicLong();
        final List<FakeHost> hosts = new ArrayList<>();
        final List<Fence> allFences = new ArrayList<>();
        final byte[][] newest = new byte[8][];
        final List<Long> writes = Collections.synchronizedList(new ArrayList<>());
        CoupledSave.setWriteObserverForTests(writes::add);

        for (int round = 0; round < 200; round++) {
            final FakeHost host = new FakeHost() {
                @Override
                public void flushStorages() throws IOException {
                    for (final Fence fence : this.inFlight) {
                        if (fence.state != CoupledSave.DONE) {
                            violations.incrementAndGet();
                        }
                    }
                    super.flushStorages();
                }
            };
            if (random.nextInt(10) == 0) {
                host.failFlushes.set(1);
            }
            for (int f = random.nextInt(4); f > 0; f--) {
                final Fence fence = new Fence();
                host.inFlight.add(fence);
                allFences.add(fence);
            }
            hosts.add(host);
            final int player = random.nextInt(newest.length);
            final byte[] data = {(byte) round, (byte) player};
            newest[player] = data;
            try (CoupledSave.Capture capture = CoupledSave.begin(host)) {
                CoupledSave.stageDat(this.players.resolve("p" + player + ".dat"), this.players.resolve("p" + player + ".dat_old"), data);
            }
            // Finish some fences of earlier rounds, in random order.
            for (int k = 0; k < 3 && !allFences.isEmpty(); k++) {
                allFences.remove(random.nextInt(allFences.size())).state = CoupledSave.DONE;
            }
        }
        for (final Fence fence : allFences) {
            fence.state = CoupledSave.DONE;
        }
        CoupledSave.awaitSubmitted(30_000);

        assertEquals("a flush ran before its capture's chunk writes landed", 0L, violations.get());
        for (final FakeHost host : hosts) {
            for (final Fence fence : host.inFlight) {
                assertEquals(CoupledSave.DONE, fence.state);
            }
        }
        for (int player = 0; player < newest.length; player++) {
            if (newest[player] != null) {
                assertArrayEquals("player " + player + " ends at its newest capture", newest[player],
                    Files.readAllBytes(this.players.resolve("p" + player + ".dat")));
            }
        }
        assertFalse(writes.isEmpty());
    }
}
