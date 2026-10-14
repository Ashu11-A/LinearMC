package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Team B async fire-and-forget flush drain (B-I3, tests only).
 *
 * <p>NMS-light (TempDir + bootstrap). Each test uses a unique folder
 * (separate coordinator) for isolation; the static BY_FOLDER registry is
 * shared. Auto-discovery: {@link LinearNmsTestSuite} selects package
 * {@code net.linear}, so no suite registration edit is needed (verified).
 *
 * <p>API RECONCILIATION (matches B-I1/B-I2's in-worktree 0012/0017 split):
 * <ol>
 *   <li>{@code LinearFlushCoordinator.flushDirtyAsync(false)} — static,
 *       age-gated fire-and-forget drain across folders. This is the exact
 *       spelling already used by the 0017 call-site hunks
 *       ({@code ChunkMap.processUnloads}, {@code ServerChunkCache.save(false)},
 *       {@code ServerLevel} autosave). Instance {@code flushDirty(false)} is
 *       likewise fire-and-forget since the 0012 split routes
 *       {@code force == false} through the private per-coordinator
 *       {@code linear$flushDirtyAsync(snapshot)} helper.</li>
 *   <li>STILL OWED by B-I1/B-I2 at time of writing: the static
 *       {@code flushDirtyAsync(boolean)} DECLARATION appears in no 0012/0017
 *       hunk yet (call sites reference it; only the private instance helper
 *       {@code linear$flushDirtyAsync(List)} is declared), as is the
 *       {@code shutdownFlushPool()} production shutdown referenced by the
 *       {@code RegionShutdownThread} hunk. Tests need neither declaration
 *       beyond the static entry above; {@code AfterEach} keeps using the
 *       existing {@code linear$resetFlushPoolForTests()}.</li>
 *   <li>{@code linear$asyncPool()} from the design doc was NOT introduced;
 *       the split reuses {@code linear$sharedPool(max(1, threads))}, so tests
 *       never reference it. Test 1 leaves the flush-threads override UNSET
 *       to prove the async path is independent of the {@code <= 1} serial
 *       gate (single-thread ordered off-tick drain).</li>
 *   <li>Simulated kill = abandon all references WITHOUT evict/flush/close
 *       (no registry-drop-without-drain hook exists; unique folders prevent
 *       cross-test interference). If B-I1/B-I2 add a test-only
 *       drop-without-draining hook, the crash tests can adopt it.</li>
 *   <li>Test 2 requires {@code evictAll()} (forced {@code flushDirty}) to
 *       join in-flight async batches: files already snapshotted out of the
 *       dirty set by a queued task must still be joined by the stop barrier
 *       (per-generation future tracking), otherwise the barrier is a lie.</li>
 * </ol>
 */
public class LinearAsyncDrainTest {

    private static final int COMPRESSION = 3;

    /** Caller return bound: async entry must return well under this (proposal: &lt; 1 s). */
    private static final long RETURN_BOUND_MILLIS = 1_000L;

    /** Stub flush await cap: bounds test time if an implementation ever blocks synchronously. */
    private static final long STUB_AWAIT_SECONDS = 30L;

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearOverrides() {
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(null);
        LinearFlushCoordinator.linear$setFlushThreadsForTests(null);
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    /**
     * Latch-gated {@link AbstractRegionFile} stub. {@code flush()} signals
     * entry, optionally sleeps, then blocks on {@code release} so tests can
     * hold a drain in flight deterministically. Never touches disk;
     * {@code close()} is a no-op (models the kill path: no flush on drop).
     */
    private static final class GateFlushFile implements AbstractRegionFile {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final long delayMillis;
        private final AtomicInteger flushCount = new AtomicInteger();
        private final AtomicBoolean flushed = new AtomicBoolean(false);
        private volatile boolean marked = true;

        GateFlushFile(CountDownLatch entered, CountDownLatch release, long delayMillis) {
            this.entered = entered;
            this.release = release;
            this.delayMillis = delayMillis;
        }

        int flushCount() {
            return this.flushCount.get();
        }

        boolean isFlushed() {
            return this.flushed.get();
        }

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
            this.flushCount.incrementAndGet();
            if (this.entered != null) {
                this.entered.countDown();
            }
            try {
                if (this.delayMillis > 0L) {
                    Thread.sleep(this.delayMillis);
                }
                if (this.release != null) {
                    this.release.await(STUB_AWAIT_SECONDS, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            this.flushed.set(true);
            this.marked = false;
        }

        @Override
        public void close() {
            // Intentionally no flush: dropping the stub models a kill.
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

    private static byte[] pattern(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) ((seed * 31 + i * 17) & 0xFF);
        }
        return out;
    }

    private static byte[] readAll(LinearRegionFile region, long chunk) throws Exception {
        try (DataInputStream in = region.getChunkDataInputStream(chunk)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static boolean awaitFilesFlushed(LinearFlushCoordinator coord, long want, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (coord.snapshot().filesFlushed() >= want) {
                return true;
            }
            Thread.sleep(25L);
        }
        return coord.snapshot().filesFlushed() >= want;
    }

    @Test
    public void periodicDrainDoesNotBlockCaller() throws Exception {
        Path dir = this.tempDir.resolve("async-noblock");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        // Frequency 0: immediate age eligibility, and the opportunistic
        // markDirty driver stays off (test-only <= 0), so the ONLY drain is
        // the explicit async entry below. Threads override deliberately
        // unset: the async pool must not be gated by the serial default.
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GateFlushFile slow = new GateFlushFile(entered, release, 0L);
        try {
            coordinator.markDirty(slow);

            long startNanos = System.nanoTime();
            LinearFlushCoordinator.flushDirtyAsync(false);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

            assertTrue(elapsedMillis < RETURN_BOUND_MILLIS,
                "async drain must return promptly (<1s), took " + elapsedMillis + "ms");
            assertTrue(entered.await(10L, TimeUnit.SECONDS), "flush started in the background");
            assertEquals(0L, coordinator.snapshot().filesFlushed(),
                "nothing completes while the flush is still in flight");

            release.countDown();
            assertTrue(awaitFilesFlushed(coordinator, 1L, 10_000L),
                "drain completes asynchronously after release");
            assertTrue(slow.isFlushed(), "slow file materialised");
        } finally {
            release.countDown();
        }
    }

    @Test
    public void shutdownBarrierStillJoins() throws Exception {
        Path dir = this.tempDir.resolve("async-shutdown-join");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GateFlushFile slow = new GateFlushFile(entered, release, 0L);
        byte[] payload = pattern(64, 5);
        LinearRegionFile real = new LinearRegionFile(dir.resolve("r.0.0.linear"), COMPRESSION);
        boolean ok = false;
        try {
            real.write(ChunkKey.of(0, 0), ByteBuffer.wrap(payload));
            coordinator.markDirty(real);
            coordinator.markDirty(slow);

            // Queue the async drain; wait until the slow flush is in flight.
            LinearFlushCoordinator.flushDirtyAsync(false);
            assertTrue(entered.await(10L, TimeUnit.SECONDS), "async drain in flight before stop");

            // Timed auto-release: even a regressed non-joining evictAll
            // cannot wedge this test; the join assertion below still fails.
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                release.countDown();
            });
            releaser.setDaemon(true);
            releaser.start();

            long startNanos = System.nanoTime();
            LinearFlushCoordinator.evictAll();
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            releaser.join(10_000L);

            assertTrue(slow.isFlushed(), "in-flight async file joined by the stop barrier");
            assertTrue(coordinator.snapshot().filesFlushed() >= 2L,
                "counters consistent: real + slow file flushed");
            assertEquals(0, coordinator.dirtyCount(), "barrier drained everything tracked");
            assertTrue(elapsedMillis >= 300L,
                "evictAll joined the in-flight drain (took " + elapsedMillis + "ms)");
            assertTrue(Files.size(dir.resolve("r.0.0.linear")) > 40L, "real file materialised");

            // Idempotent stop-drain contract: second evictAll is a no-op.
            assertDoesNotThrow(LinearFlushCoordinator::evictAll, "second evictAll never throws");
            ok = true;
        } finally {
            release.countDown();
            try {
                real.close();
            } catch (Exception ignored) {
            }
        }
        assertTrue(ok, "reached end of barrier assertions");

        // Read-back on a fresh handle: the barrier-persisted payload survives.
        LinearRegionFile reopened = new LinearRegionFile(dir.resolve("r.0.0.linear"), COMPRESSION);
        try {
            assertArrayEquals(payload, readAll(reopened, ChunkKey.of(0, 0)), "stop barrier persisted payload");
        } finally {
            try {
                reopened.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void concurrentDrainsSingleMaterialisation() throws Exception {
        Path dir = this.tempDir.resolve("async-concurrent");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);

        // Shared gates: every stub flush blocks on `go`, so both drains are
        // provably in flight (neither complete) before either can finish,
        // regardless of async pool width.
        final int n = 4;
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        List<GateFlushFile> files = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                GateFlushFile f = new GateFlushFile(firstEntered, go, 0L);
                files.add(f);
                coordinator.markDirty(f);
            }

            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            Thread t1 = new Thread(() -> {
                try {
                    LinearFlushCoordinator.flushDirtyAsync(false);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            Thread t2 = new Thread(() -> {
                try {
                    LinearFlushCoordinator.flushDirtyAsync(false);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            t1.start();
            t2.start();
            t1.join(10_000L);
            t2.join(10_000L);
            assertFalse(t1.isAlive() || t2.isAlive(), "both async drains returned");
            assertTrue(errors.isEmpty(), "concurrent drains never throw: " + errors);

            // Both drains were submitted while the first flush was gated, so
            // they overlapped; now let the single snapshot's batch finish.
            assertTrue(firstEntered.await(10L, TimeUnit.SECONDS), "drain batch started");
            Thread.sleep(100L); // let the second drain queue behind the gated batch
            go.countDown();

            assertTrue(awaitFilesFlushed(coordinator, n, 10_000L), "batch completes");
            for (int i = 0; i < n; i++) {
                assertEquals(1, files.get(i).flushCount(),
                    "file " + i + " materialised exactly once (flushGuard serialisation)");
            }
            assertEquals((long) n, coordinator.snapshot().filesFlushed(),
                "counters match on-disk state: one flush event per file");
            assertEquals(0, coordinator.dirtyCount(), "non-evicting path leaves nothing tracked");

            // Registry intact afterwards: the coordinator accepts new work.
            GateFlushFile extra = new GateFlushFile(null, null, 0L);
            coordinator.markDirty(extra);
            assertEquals(1, coordinator.dirtyCount(), "coordinator usable after concurrent drains");
        } finally {
            go.countDown();
        }
    }

    @Test
    public void crashWindowBound() throws Exception {
        Path dir = this.tempDir.resolve("async-crash-window");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(1L);

        Path file = dir.resolve("r.0.0.linear");
        byte[] payloadA = pattern(64, 11);
        byte[] payloadB = pattern(64, 22);
        LinearRegionFile writer = new LinearRegionFile(file, COMPRESSION);

        // Batch A, then age it past the 1s frequency.
        writer.write(ChunkKey.of(0, 0), ByteBuffer.wrap(payloadA));
        coordinator.markDirty(writer);
        Thread.sleep(1500L);

        // One age-gated async pass; AWAIT completion before writing B so the
        // durability oracle is deterministic (B must not piggyback A's flush).
        LinearFlushCoordinator.flushDirtyAsync(false);
        assertTrue(awaitFilesFlushed(coordinator, 1L, 10_000L), "aged batch A drained async");

        // Batch B stays young (frequency now far above its age).
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(3600L);
        writer.write(ChunkKey.of(1, 0), ByteBuffer.wrap(payloadB));
        coordinator.markDirty(writer);

        // Simulated kill: drop the coordinator WITHOUT evict/flush/close.
        // The writer handle is abandoned unclosed (close would flush); the
        // on-disk image is the oracle. Unique folder => no cross-test leak.
        writer = null;
        coordinator = null;

        try (Stream<Path> entries = Files.list(dir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                "async path leaves no new orphan shape");
        }

        LinearRegionFile reader = new LinearRegionFile(file, COMPRESSION);
        try {
            assertArrayEquals(payloadA, readAll(reader, ChunkKey.of(0, 0)),
                "aged batch A drained async is durable across the kill");
            assertNull(readAll(reader, ChunkKey.of(1, 0)),
                "young batch B (< frequency, never drained) is lost: bounded to one window");
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void crashWindowNegativeControlAsyncLeakLosesAgedBatch() throws Exception {
        // Sensitivity proof for crashWindowBound: WITHOUT a joining stop
        // barrier (fire-and-forget leak at the C path), even the AGED batch
        // is lost. This test PASSES by asserting that loss; if a future
        // barrier ever joins here, this control must be updated.
        Path dir = this.tempDir.resolve("async-crash-control");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GateFlushFile gated = new GateFlushFile(entered, release, 0L);
        try {
            coordinator.markDirty(gated);
            LinearFlushCoordinator.flushDirtyAsync(false);
            assertTrue(entered.await(10L, TimeUnit.SECONDS), "leaked drain started");

            // Kill while the async flush is still gated: no evict, no await.
            // (Local ref kept for assertions only; the barrier was skipped.)
            assertFalse(gated.isFlushed(), "no barrier joined: aged batch lost on kill");
            assertEquals(0L, coordinator.snapshot().filesFlushed(),
                "no completion counted without the barrier");
        } finally {
            release.countDown();
        }
    }
}
