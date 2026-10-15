package net.linear;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Direct-memory lifetime: every transient direct buffer on the chunk I/O hot
 * paths must be freed deterministically (stream close, end of load, region
 * close) instead of riding GC-triggered Cleaner runs, which cannot keep up
 * with generation-speed rates (~500 chunk I/O/s exhausts the direct cap in
 * minutes). Covers the shared read/write/load paths for V1, V2 and V3
 * (their codecs differ only in heap-side assembly; see V1Encoder and
 * AbstractBucketedVersion).
 */
public class DirectMemoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static long directUsed() {
        final List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        for (final BufferPoolMXBean pool : pools) {
            if ("direct".equals(pool.getName())) {
                return pool.getMemoryUsed();
            }
        }
        fail("no direct buffer pool MXBean");
        return -1L;
    }

    private static byte[] payload(final int size, final int seed) {
        final byte[] out = new byte[size];
        int state = seed;
        for (int i = 0; i < out.length; i++) {
            state = state * 1103515245 + 12345;
            out[i] = (byte) (state >>> 16);
        }
        return out;
    }

    private static byte[] readChunk(final LinearRegionFile region, final long key) throws IOException {
        try (DataInputStream in = region.getChunkDataInputStream(key)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        }
    }

    @Test
    public void readCloseFreesDirectBuffer() throws Exception {
        final File file = this.temporaryFolder.newFile("r.0.0.linear");
        final byte[] expected = payload(64 * 1024, 0xD1EC);
        try (LinearRegionFile region = new LinearRegionFile(file.toPath(), 6)) {
            region.write(ChunkKey.of(0, 0), ByteBuffer.wrap(expected));
            region.flush();
            assertArrayEquals(expected, readChunk(region, ChunkKey.of(0, 0)));

            final long freedBefore = net.linear.io.LinearDirect.freedBytes();
            final int reads = 200;
            for (int i = 0; i < reads; i++) {
                assertArrayEquals(expected, readChunk(region, ChunkKey.of(0, 0)));
            }
            final long freedDelta = net.linear.io.LinearDirect.freedBytes() - freedBefore;
            // 200 reads x 64 KiB, no System.gc: every close must have freed.
            assertTrue("expected >= " + (reads * 64L * 1024L) + " freed, got " + freedDelta,
                freedDelta >= reads * 64L * 1024L);
        }
    }

    @Test
    public void readAfterCloseFailsInsteadOfTouchingFreedMemory() throws Exception {
        final File file = this.temporaryFolder.newFile("r.0.1.linear");
        try (LinearRegionFile region = new LinearRegionFile(file.toPath(), 6)) {
            region.write(ChunkKey.of(1, 1), ByteBuffer.wrap(payload(1024, 7)));
            region.flush();
            final DataInputStream in = region.getChunkDataInputStream(ChunkKey.of(1, 1));
            in.close();
            try {
                in.read();
                fail("expected IOException after close");
            } catch (final IOException expected) {
                // Guard holds: no use-after-free.
            }
        }
    }

    @Test
    public void writeRetainsExactlyOneDirectBufferPerSlot() throws Exception {
        final File file = this.temporaryFolder.newFile("r.0.2.linear");
        final long usedBefore = directUsed();
        try (LinearRegionFile region = new LinearRegionFile(file.toPath(), 6)) {
            // Staging copy is heap now; only the packed image stays direct
            // (~32 KiB worst case here). A direct staging copy as well would
            // double this past the bound.
            region.write(ChunkKey.of(2, 2), ByteBuffer.wrap(payload(32 * 1024, 9)));
            final long delta = directUsed() - usedBefore;
            assertTrue("one packed buffer retained, no direct staging: +" + delta, delta <= 48L * 1024L);
        }
    }

    @Test
    public void regionCloseFreesRetainedSet() throws Exception {
        final File file = this.temporaryFolder.newFile("r.0.3.linear");
        final long freedBefore = net.linear.io.LinearDirect.freedBytes();
        final int slots = 16;
        try (LinearRegionFile region = new LinearRegionFile(file.toPath(), 6)) {
            for (int i = 0; i < slots; i++) {
                region.write(ChunkKey.of(i, 0), ByteBuffer.wrap(payload(16 * 1024, 100 + i)));
            }
            region.flush();
        }
        final long freedDelta = net.linear.io.LinearDirect.freedBytes() - freedBefore;
        assertTrue("close must free the " + slots + "-slot retained set, freed=" + freedDelta,
            freedDelta >= slots * 1024L);
    }

    @Test
    public void dirtyCloseDrainsAndFreesRetained() throws Exception {
        final File file = this.temporaryFolder.newFile("r.0.4.linear");
        final long freedBefore = net.linear.io.LinearDirect.freedBytes();
        final int slots = 8;
        // No explicit flush: close must drain the dirty state AND free the
        // retained set on the shared success path.
        try (LinearRegionFile region = new LinearRegionFile(file.toPath(), 6)) {
            for (int i = 0; i < slots; i++) {
                region.write(ChunkKey.of(i, 1), ByteBuffer.wrap(payload(16 * 1024, 200 + i)));
            }
        }
        final long freedDelta = net.linear.io.LinearDirect.freedBytes() - freedBefore;
        assertTrue("dirty close must free the " + slots + "-slot retained set, freed=" + freedDelta,
            freedDelta >= slots * 1024L);
        // Drained data must have survived the close.
        try (LinearRegionFile reopened = new LinearRegionFile(file.toPath(), 6)) {
            assertArrayEquals(payload(16 * 1024, 200),
                readChunk(reopened, ChunkKey.of(0, 1)));
        }
    }

    @Test
    public void bufferedMasterOpenFreesBody() throws Exception {
        final File file = this.temporaryFolder.newFile("r.0.5.linear");
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(file.toPath(), 6)) {
            region.write(ChunkKey.of(3, 3), ByteBuffer.wrap(payload(16 * 1024, 300)));
            region.flush();
        }
        // Reopen parses the master (whole-body direct buffer) and must free
        // it after extracting the heap envelopes.
        final long freedBefore = net.linear.io.LinearDirect.freedBytes();
        try (BufferedLinearRegionFile reopened = new BufferedLinearRegionFile(file.toPath(), 6)) {
            assertArrayEquals(payload(16 * 1024, 300),
                readChunkBytes(reopened, ChunkKey.of(3, 3)));
        }
        final long freedDelta = net.linear.io.LinearDirect.freedBytes() - freedBefore;
        assertTrue("master body must be freed after parse, freed=" + freedDelta, freedDelta > 0L);
    }

    private static byte[] readChunkBytes(final BufferedLinearRegionFile region, final long key)
            throws IOException {
        try (DataInputStream in = region.getChunkDataInputStream(key)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        }
    }

    @Test
    public void writeRetainsTightPrefixNotWorstCase() throws Exception {
        final File file = this.temporaryFolder.newFile("r.0.6.linear");
        // 64 KiB of zeros compresses to a few hundred bytes; the retained
        // root must be the used prefix, not the ~66 KiB worst-case staging.
        final byte[] zeros = new byte[64 * 1024];
        final long usedBefore = directUsed();
        try (LinearRegionFile region = new LinearRegionFile(file.toPath(), 6)) {
            region.write(ChunkKey.of(6, 6), ByteBuffer.wrap(zeros));
            final long delta = directUsed() - usedBefore;
            assertTrue("retained must be the tight prefix, +" + delta, delta <= 4L * 1024L);
            assertArrayEquals(zeros, readChunk(region, ChunkKey.of(6, 6)));
        }
    }

    @Test
    public void soakWriteReadFlushAcrossImplementations() throws Exception {
        for (final LinearImplementation impl : LinearImplementation.values()) {
            final File file = this.temporaryFolder.newFile("r.1." + impl.version() + ".linear");
            final long base = directUsed();
            final long freedBefore = net.linear.io.LinearDirect.freedBytes();
            long peak = base;
            try (LinearRegionFile region =
                    new LinearRegionFile(file.toPath(), 6, impl, 8)) {
                for (int i = 0; i < 1200; i++) {
                    final long key = ChunkKey.of(i % 16, 0);
                    final byte[] data = payload(8 * 1024, i);
                    region.write(key, ByteBuffer.wrap(data));
                    assertArrayEquals(data, readChunk(region, key));
                    if (i % 50 == 0) {
                        region.flush();
                    }
                    if (i % 200 == 0) {
                        // Production runs young GC every few seconds under
                        // generation load; overwritten-slot garbage is
                        // GC-domain by design (reads hold views past the
                        // lock, so overwrite-time free would be
                        // use-after-free). Model that collector here.
                        System.gc();
                    }
                    final long used = directUsed();
                    if (used > peak) {
                        peak = used;
                    }
                }
                region.flush();
            }
            // Deterministic part (no GC dependence): every read-close and the
            // final region close freed explicitly: 1200 x 8 KiB reads alone.
            final long freedDelta = net.linear.io.LinearDirect.freedBytes() - freedBefore;
            assertTrue(impl + ": expected >= 9 MiB deterministically freed, got " + freedDelta,
                freedDelta >= 9L * 1024L * 1024L);
            // Steady-state part: with production-like GC, the 16-slot working
            // set plus one in-flight buffer is all that ever exists.
            final long peakDelta = peak - base;
            assertTrue(impl + ": direct peak too high, +" + peakDelta + " bytes",
                peakDelta < 4L * 1024L * 1024L);
        }
    }
}
