package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;
import net.linear.buffered.BufferedRegionOutput;

/**
 * Buffered-linear tier ({@code .b_linear} + {@code .b_linear.swp}):
 * round-trip, repair-on-open, master import, compaction, guards.
 */
public class BufferedLinearRegionFileTest {

    private static byte[] payload(String seed, int reps) {
        return seed.repeat(reps).getBytes(StandardCharsets.UTF_8);
    }

    private static Path master(Path dir, String name) {
        return dir.resolve(name + RegionFileFormat.B_LINEAR_EXTENSION);
    }

    private static byte[] readAll(BufferedLinearRegionFile region, long chunk) throws Exception {
        DataInputStream in = region.getChunkDataInputStream(chunk);
        assertNotNull(in);
        byte[] back = in.readAllBytes();
        in.close();
        return back;
    }

    @Test
    public void roundTrip() throws Exception {
        Path dir = Files.createTempDirectory("blinear-rt");
        Path file = master(dir, "r.0.0");
        byte[] a = payload("ba", 64);
        byte[] b = payload("bb", 64);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(0, 0), ByteBuffer.wrap(a));
            region.write(ChunkKey.of(31, 31), ByteBuffer.wrap(b));
            region.flush();
        }
        assertTrue(Files.isRegularFile(file));
        // WAL-at-rest prune: clean close leaves the master alone; the swap
        // is rebuilt from the master on the next open.
        assertFalse(Files.isRegularFile(Path.of(file + ".swp")));
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(0, 0)));
            assertArrayEquals(b, readAll(region, ChunkKey.of(31, 31)));
            assertNull(region.getChunkDataInputStream(ChunkKey.of(1, 1)));
            assertTrue(region.hasChunk(ChunkKey.of(0, 0)));
        }
    }

    @Test
    public void closePrunesSwapAndStaging() throws Exception {
        Path dir = Files.createTempDirectory("blinear-prune");
        Path file = master(dir, "r.5.5");
        byte[] a = payload("prune-me", 48);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(7, 7), ByteBuffer.wrap(a));
            region.flush();
            // Swap exists while open (primary read store).
            assertTrue(Files.isRegularFile(Path.of(file + ".swp")));
        }
        // Clean close prunes the WAL sidecar and staging tmps: at-rest
        // state is the master alone.
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.isRegularFile(Path.of(file + ".swp")));
        assertFalse(Files.isRegularFile(Path.of(file + ".tmp")));
        assertFalse(Files.isRegularFile(Path.of(file + ".swp.tmp")));
    }

    @Test
    public void reopenAfterClosePruneServesMaster() throws Exception {
        Path dir = Files.createTempDirectory("blinear-reopen");
        Path file = master(dir, "r.6.6");
        byte[] a = payload("reopen-me", 40);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(9, 9), ByteBuffer.wrap(a));
            region.flush();
        }
        assertFalse(Files.isRegularFile(Path.of(file + ".swp")));
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(9, 9)));
        }
    }

    @Test
    public void swapOnlyStateMarksDirtyAndHeals() throws Exception {
        // Killed before the first master flush: swap holds the only copy, no
        // master exists. Open must report dirty (forcing materialization) and
        // close must NOT prune the sole copy; after flush+close the master
        // exists and the swap is pruned, with payloads intact.
        Path dir = Files.createTempDirectory("blinear-swaponly");
        Path file = master(dir, "r.8.8");
        byte[] a = payload("swap-only-heal", 40);
        byte[] swapSnapshot;
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(11, 11), ByteBuffer.wrap(a));
            region.flush();
            swapSnapshot = snapshotSwap(file);
        }
        assertTrue(Files.isRegularFile(file));
        Files.delete(file);
        Files.write(Path.of(file + ".swp"), swapSnapshot);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertTrue("swap-only state must report dirty", region.isMarkedToSave());
            assertArrayEquals(a, readAll(region, ChunkKey.of(11, 11)));
            region.flush();
        }
        assertTrue("master must be materialized", Files.isRegularFile(file));
        assertFalse("swap pruned once master exists",
            Files.isRegularFile(Path.of(file + ".swp")));
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(11, 11)));
        }
    }

    @Test
    public void swapOnlyHealsOnCloseWithoutExplicitFlush() throws Exception {
        // Same sole-copy setup, but rely on close()'s own drain instead of an
        // explicit flush: proves the auto-flush path preserves the sole copy.
        Path dir = Files.createTempDirectory("blinear-swaponly-close");
        Path file = master(dir, "r.10.10");
        byte[] a = payload("swap-only-close", 40);
        byte[] swapSnapshot;
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(13, 13), ByteBuffer.wrap(a));
            region.flush();
            swapSnapshot = snapshotSwap(file);
        }
        Files.delete(file);
        Files.write(Path.of(file + ".swp"), swapSnapshot);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertTrue("swap-only state must report dirty", region.isMarkedToSave());
        }
        assertTrue("master must be materialized by close drain", Files.isRegularFile(file));
        assertFalse("swap pruned once master exists",
            Files.isRegularFile(Path.of(file + ".swp")));
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(13, 13)));
        }
    }

    @Test
    public void emptyHuskLeavesNoFiles() throws Exception {
        // Neither master nor live data ever existed: open must not litter a
        // 17 KB swap husk, and clean close must leave zero files behind.
        Path dir = Files.createTempDirectory("blinear-husk");
        Path file = master(dir, "r.9.9");
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertNull(region.getChunkDataInputStream(ChunkKey.of(0, 0)));
        }
        assertFalse(Files.isRegularFile(file));
        assertFalse(Files.isRegularFile(Path.of(file + ".swp")));
        // Rewrite after zero-files: create-then-write still works (no crash
        // on the pruned channel; allocator restarts from the header end).
        byte[] a = payload("husk-rewrite", 32);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(5, 5), ByteBuffer.wrap(a));
        }
        assertTrue(Files.isRegularFile(file));
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(5, 5)));
        }
    }

    @Test
    public void openSweepsStagingTmps() throws Exception {        Path dir = Files.createTempDirectory("blinear-sweep");
        Path file = master(dir, "r.7.7");
        byte[] a = payload("sweep-me", 40);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(3, 3), ByteBuffer.wrap(a));
            region.flush();
        }
        // Simulate killed-flush orphans: forced-but-unmoved master staging
        // and compact staging. Neither may survive the next open.
        Path masterTmp = Path.of(file + ".tmp");
        Path swapTmp = Path.of(file + ".swp.tmp");
        Files.write(masterTmp, new byte[32]);
        Files.write(swapTmp, new byte[32]);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(3, 3)));
        }
        assertFalse(Files.isRegularFile(masterTmp));
        assertFalse(Files.isRegularFile(swapTmp));
    }

    @Test
    public void closeFlushesWithoutExplicitFlush() throws Exception {
        Path dir = Files.createTempDirectory("blinear-close");
        Path file = master(dir, "r.1.2");
        byte[] a = payload("close", 32);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(33, 34), ByteBuffer.wrap(a));
        }
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(33, 34)));
        }
    }

    private static byte[] snapshotSwap(Path file) throws Exception {
        // Crash-window state must be captured while open: clean close prunes
        // the WAL sidecar, so tests reconstruct the pre-prune state by hand.
        Path swap = Path.of(file + ".swp");
        assertTrue(Files.isRegularFile(swap));
        return Files.readAllBytes(swap);
    }

    @Test
    public void tornSwapSectorRepairedFromMaster() throws Exception {
        Path dir = Files.createTempDirectory("blinear-repair");
        Path file = master(dir, "r.0.0");
        byte[] a = payload("repair-me", 48);
        byte[] swapSnapshot;
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(4, 4), ByteBuffer.wrap(a));
            region.flush();
            swapSnapshot = snapshotSwap(file);
        }
        Path swap = Path.of(file + ".swp");
        // Restore the crash-window WAL beside the closed master, then corrupt
        // the first sector payload byte (past the 17429-byte header).
        assertTrue(swapSnapshot.length > BufferedLinearRegionFile.SWAP_HEADER_SIZE + 20);
        swapSnapshot[BufferedLinearRegionFile.SWAP_HEADER_SIZE + 10] ^= 0xFF;
        Files.write(swap, swapSnapshot);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(4, 4)));
        }
    }

    @Test
    public void missingSwapReimportedFromMaster() throws Exception {
        Path dir = Files.createTempDirectory("blinear-import");
        Path file = master(dir, "r.3.3");
        byte[] a = payload("import-me", 40);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(99, 99), ByteBuffer.wrap(a));
            region.flush();
        }
        // Clean close already pruned the WAL; the missing-swap state is the
        // steady state. deleteIfExists keeps the intent if a sidecar lingers.
        Files.deleteIfExists(Path.of(file + ".swp"));
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(99, 99)));
        }
    }

    @Test
    public void swapAloneServesReads() throws Exception {
        // The sector table must be forced to the swap file at flush: with
        // the master deleted, reopened reads may only use swap state.
        Path dir = Files.createTempDirectory("blinear-swapon");
        Path file = master(dir, "r.0.0");
        byte[] a = payload("swap-only", 40);
        byte[] swapSnapshot;
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(2, 2), ByteBuffer.wrap(a));
            region.flush();
            swapSnapshot = snapshotSwap(file);
        }
        // Restore the crash-window WAL, then lose the master: only swap state
        // may serve the reopened read.
        Files.write(Path.of(file + ".swp"), swapSnapshot);
        Files.delete(file);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(2, 2)));
        }
    }

    @Test
    public void truncatedSwapRebuildsFromMaster() throws Exception {
        Path dir = Files.createTempDirectory("blinear-truncswap");
        Path file = master(dir, "r.0.0");
        byte[] a = payload("trunc-swap", 40);
        byte[] swapSnapshot;
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(6, 6), ByteBuffer.wrap(a));
            region.flush();
            swapSnapshot = snapshotSwap(file);
        }
        Path swap = Path.of(file + ".swp");
        byte[] truncated = new byte[100];
        System.arraycopy(swapSnapshot, 0, truncated, 0, 100);
        Files.write(swap, truncated);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(6, 6)));
        }
    }

    @Test
    public void writesSelfReportToCoordinator() throws Exception {
        // Live-found gap: every mutation path must land in the coordinator
        // without caller-side reporting, or save-all never flushes it.
        Path dir = Files.createTempDirectory("blinear-selfreport");
        Path file = master(dir, "r.0.0");
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(0, 0), ByteBuffer.wrap(payload("direct", 16)));
            assertTrue(LinearFlushCoordinator.isFolderDirty(dir));
        }
        // Stream-only path (what Moonrise ChunkBuffer drives): no explicit
        // markDirty anywhere on this path, yet the folder must read dirty.
        Path dir2 = Files.createTempDirectory("blinear-selfreport-stream");
        Path file2 = master(dir2, "r.0.0");
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file2, 6)) {
            try (java.io.OutputStream out =
                    BufferedRegionOutput.forFile(region, ChunkKey.of(1, 1))) {
                out.write(payload("streamed", 16));
            }
            assertTrue(LinearFlushCoordinator.isFolderDirty(dir2));
        }
    }

    @Test
    public void badMasterMagicRejected() throws Exception {
        Path dir = Files.createTempDirectory("blinear-badmaster");
        Path file = master(dir, "r.0.0");
        Files.write(file, new byte[64]);
        try {
            new BufferedLinearRegionFile(file, 6);
            fail("expected IOException");
        } catch (java.io.IOException expected) {
        }
    }

    @Test
    public void oversizeRejected() throws Exception {
        Path dir = Files.createTempDirectory("blinear-huge");
        Path file = master(dir, "r.0.0");
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            try {
                region.write(ChunkKey.of(0, 0), ByteBuffer.allocate(LinearRegionFile.MAX_CHUNK_SIZE + 1));
                fail("expected IOException");
            } catch (java.io.IOException expected) {
            }
        }
    }

    @Test
    public void compactionReclaimsSpare() throws Exception {
        Path dir = Files.createTempDirectory("blinear-compact");
        Path file = master(dir, "r.0.0");
        // Incompressible payloads (fixed seed): LZ4/zstd cannot hide the spare.
        java.util.Random random = new java.util.Random(42);
        byte[][] big = new byte[20][];
        for (int i = 0; i < 20; i++) {
            big[i] = new byte[60 * 1024];
            random.nextBytes(big[i]);
        }
        byte[] small = payload("s", 64);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            for (int i = 0; i < 20; i++) {
                region.write(ChunkKey.of(i, 0), ByteBuffer.wrap(big[i]));
            }
            region.flush();
            long fragmented = Files.size(Path.of(file + ".swp"));
            assertTrue("setup should exceed 1MiB: " + fragmented, fragmented > 1024L * 1024L);
            for (int i = 0; i < 20; i++) {
                region.write(ChunkKey.of(i, 0), ByteBuffer.wrap(small));
            }
            region.flush();
            long compacted = Files.size(Path.of(file + ".swp"));
            assertTrue("swap should shrink after compact: " + fragmented + " -> " + compacted,
                compacted < fragmented / 2);
            for (int i = 0; i < 20; i++) {
                assertArrayEquals(small, readAll(region, ChunkKey.of(i, 0)));
            }
        }
    }

    @Test
    public void closeSkipsCompactWhenPruning() throws Exception {
        // Same fragmentation setup as compactionReclaimsSpare: after the
        // overwrite the swap is compact-eligible. close() must NOT compact
        // (the swap is pruned right after) but must still persist + prune.
        Path dir = Files.createTempDirectory("blinear-nocompact");
        Path file = master(dir, "r.4.4");
        java.util.Random random = new java.util.Random(42);
        byte[][] big = new byte[20][];
        for (int i = 0; i < 20; i++) {
            big[i] = new byte[60 * 1024];
            random.nextBytes(big[i]);
        }
        byte[] small = payload("s", 64);
        BufferedLinearRegionFile first;
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            for (int i = 0; i < 20; i++) {
                region.write(ChunkKey.of(i, 0), ByteBuffer.wrap(big[i]));
            }
            region.flush();
            for (int i = 0; i < 20; i++) {
                region.write(ChunkKey.of(i, 0), ByteBuffer.wrap(small));
            }
            first = region;
            first.close();
            assertEquals("close-drain must skip the compact pass", 0, first.linear$compactCount());
        }
        assertFalse(Files.isRegularFile(Path.of(file + ".swp")));
        // Control: identical fragmentation + plain flush() DOES compact.
        Path dir2 = Files.createTempDirectory("blinear-yescompact");
        Path file2 = master(dir2, "r.4.4");
        random = new java.util.Random(42);
        for (int i = 0; i < 20; i++) {
            big[i] = new byte[60 * 1024];
            random.nextBytes(big[i]);
        }
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file2, 6)) {
            for (int i = 0; i < 20; i++) {
                region.write(ChunkKey.of(i, 0), ByteBuffer.wrap(big[i]));
            }
            region.flush();
            for (int i = 0; i < 20; i++) {
                region.write(ChunkKey.of(i, 0), ByteBuffer.wrap(small));
            }
            region.flush();
            assertEquals("plain flush must still compact", 1, region.linear$compactCount());
            for (int i = 0; i < 20; i++) {
                assertArrayEquals(small, readAll(region, ChunkKey.of(i, 0)));
            }
        }
        // And the close-pruned file reads back intact from the master.
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            for (int i = 0; i < 20; i++) {
                assertArrayEquals(small, readAll(region, ChunkKey.of(i, 0)));
            }
        }
    }

    @Test
    public void concurrentWritesAllSurviveFlush() throws Exception {
        Path dir = Files.createTempDirectory("blinear-conc");
        Path file = master(dir, "r.0.0");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file, 6)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                final int slot = i;
                // Distinct slots: x cycles 0..31, z carries the high bits.
                final long chunk = ChunkKey.of(slot % 32, slot / 32);
                futures.add(pool.submit((Callable<Void>) () -> {
                    region.write(chunk, ByteBuffer.wrap(payload("c" + slot + "-", 32)));
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
            region.flush();
            for (int i = 0; i < 64; i++) {
                assertArrayEquals(payload("c" + i + "-", 32), readAll(region, ChunkKey.of(i % 32, i / 32)));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
