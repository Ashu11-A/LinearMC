package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LinearRegionFile} write → flush →
 * reopen → read round-trip, including a simulated restart re-read.
 *
 * <p>Exercises EXACTLY the region-file contract surface (verified against
 * {@code net/linear/LinearRegionFile.java}
 * and the integrated fork file, which differs only by
 * {@code implements AbstractRegionFile} + a {@code clear(ChunkPos)} override +
 * {@code @Override}s — same ctor and seven methods):
 * {@code LinearRegionFile(Path, int)}, {@code write(ChunkPos, ByteBuffer)},
 * {@code getChunkDataInputStream(ChunkPos)}, {@code hasChunk(ChunkPos)},
 * {@code flush()}, {@code close()}, {@code isMarkedToSave()} /
 * {@code clearMarkedToSave()}. Deletion uses the contract form
 * (empty-buffer {@code write}), never {@code clear()}, so this file compiles
 * against both pure and integrated trees.
 *
 * <p>NMS bootstrap: {@link ChunkPos} class init needs
 * {@code Bootstrap.bootStrap()} (registry chain),
 * hence the {@code @BeforeAll} below.
 * Payloads are opaque bytes; no NBT/registries/datapacks are involved.
 *
 * <p>Target path when integrated:
 * {@code folia-server/src/test/java/net/linear/LinearRegionFileRoundTripTest.java}.
 */
public class LinearRegionFileRoundTripTest {

    private static final int COMPRESSION = 3;

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void bootstrapNms() {
        // ChunkPos.<clinit> requires the registry bootstrap.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static byte[] pattern(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) ((seed * 31 + i * 17) & 0xFF);
        }
        return out;
    }

    private static byte[] readAll(LinearRegionFile region, long chunk) throws IOException {
        try (DataInputStream in = region.getChunkDataInputStream(chunk)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Test
    public void writeFlushReopenRead_roundTrip() throws IOException {
        Path file = this.tempDir.resolve("r.0.0.linear");
        long a = ChunkKey.of(0, 0);
        long b = ChunkKey.of(1, 0);
        long c = ChunkKey.of(0, 1);
        long d = ChunkKey.of(31, 31);
        long absent = ChunkKey.of(5, 5);

        byte[] pa = pattern(64, 7);
        byte[] paOrig = pa.clone();
        byte[] pb = pattern(4096, 13);
        byte[] pc = pattern(16, 21);
        byte[] pd = pattern(1024, 42);

        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        assertFalse(region.isMarkedToSave(), "fresh region must be clean");

        // Heap write; then mangle the caller's array to pin copy-on-write semantics.
        region.write(a, ByteBuffer.wrap(pa));
        Arrays.fill(pa, (byte) 0);
        // Direct-buffer write (zero-copy path in the impl).
        ByteBuffer direct = ByteBuffer.allocateDirect(pb.length);
        direct.put(pb);
        direct.flip();
        region.write(b, direct);
        region.write(c, ByteBuffer.wrap(pc));
        region.write(d, ByteBuffer.wrap(pd));
        assertTrue(region.isMarkedToSave(), "writes must mark dirty");

        // Pre-flush reads serve from RAM staging.
        assertArrayEquals(paOrig, readAll(region, a), "caller-buffer reuse must not corrupt slot a");
        assertArrayEquals(pb, readAll(region, b));
        assertArrayEquals(pc, readAll(region, c));
        assertArrayEquals(pd, readAll(region, d));
        assertNull(readAll(region, absent), "never-written slot must read null");
        assertTrue(region.hasChunk(a));
        assertFalse(region.hasChunk(absent));

        region.flush();
        assertFalse(region.isMarkedToSave(), "flush must clear dirty");
        assertTrue(Files.exists(file), "flush must materialise the file");
        assertTrue(Files.size(file) > 0, "flushed file must be non-empty");

        // Post-flush reads on the same instance.
        assertArrayEquals(paOrig, readAll(region, a));
        assertArrayEquals(pd, readAll(region, d));
        region.close();

        // Simulated restart: fresh instance over the same path re-reads everything.
        LinearRegionFile reopened = new LinearRegionFile(file, COMPRESSION);
        try {
            assertFalse(reopened.isMarkedToSave(), "reopened region must load clean");
            assertArrayEquals(paOrig, readAll(reopened, a), "restart re-read of slot a");
            assertArrayEquals(pb, readAll(reopened, b), "restart re-read of slot b");
            assertArrayEquals(pc, readAll(reopened, c), "restart re-read of slot c");
            assertArrayEquals(pd, readAll(reopened, d), "restart re-read of slot d");
            assertNull(readAll(reopened, absent), "absent slot stays absent across restart");
            assertTrue(reopened.hasChunk(d));
            assertFalse(reopened.hasChunk(absent));
        } finally {
            reopened.close();
        }
    }

    @Test
    public void restartPersistsDeleteAndFlushIsStable() throws IOException {
        Path file = this.tempDir.resolve("r.2.3.linear");
        long p = ChunkKey.of(65, 97); // region-local (1,1): any ChunkPos works
        long q = ChunkKey.of(70, 104);
        byte[] pp = pattern(256, 3);
        byte[] pq = pattern(512, 9);

        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        region.write(p, ByteBuffer.wrap(pp));
        region.write(q, ByteBuffer.wrap(pq));
        region.flush();

        // Double-flush byte-stability: second flush is a no-op, bytes identical.
        byte[] once = Files.readAllBytes(file);
        region.flush();
        assertArrayEquals(once, Files.readAllBytes(file), "flush with no new writes must be byte-stable");

        // Contract delete: empty write; slot reads absent but sibling survives.
        region.write(q, ByteBuffer.allocate(0));
        assertFalse(region.hasChunk(q), "empty write must clear the slot");
        assertTrue(region.hasChunk(p));
        assertTrue(region.isMarkedToSave(), "delete must mark dirty");
        region.flush();
        assertNull(readAll(region, q), "deleted slot must read null after flush");
        assertArrayEquals(pp, readAll(region, p), "sibling slot must survive the delete");
        region.close();

        // Restart confirms the deletion persisted (and timestamps of p survived).
        LinearRegionFile reopened = new LinearRegionFile(file, COMPRESSION);
        try {
            assertArrayEquals(pp, readAll(reopened, p), "survivor re-read after restart");
            assertNull(readAll(reopened, q), "deletion must persist across restart");
            assertFalse(reopened.hasChunk(q));
            byte[] before = Files.readAllBytes(file);
            reopened.flush(); // clean reopen: no-op
            assertArrayEquals(before, Files.readAllBytes(file), "clean-reopen flush must be a no-op");
        } finally {
            reopened.close();
        }
    }

    @Test
    public void closedFileFailsFastAndCleanCloseCreatesNothing() throws IOException {
        // Untouched missing file: flush/close create nothing (region lifecycle).
        Path missing = this.tempDir.resolve("r.9.9.linear");
        LinearRegionFile clean = new LinearRegionFile(missing, COMPRESSION);
        assertFalse(Files.exists(missing));
        clean.flush();
        assertFalse(Files.exists(missing), "flush on clean missing file must create nothing");
        clean.close();
        assertFalse(Files.exists(missing), "close on clean missing file must create nothing");

        // Fail-fast after close: never a silent "missing".
        Path file = this.tempDir.resolve("r.4.4.linear");
        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        long pos = ChunkKey.of(128, 128);
        region.write(pos, ByteBuffer.wrap(pattern(32, 5)));
        region.close(); // persists, then closes
        assertTrue(Files.exists(file), "close must flush dirty state");

        assertThrows(IOException.class, () -> region.write(pos, ByteBuffer.wrap(new byte[]{1})),
            "write after close must throw");
        assertThrows(IOException.class, () -> region.getChunkDataInputStream(pos),
            "read after close must throw");
        assertThrows(IOException.class, region::flush, "flush after close must throw");
        assertThrows(IllegalStateException.class, () -> region.hasChunk(pos),
            "hasChunk after close must throw IllegalStateException");
    }
}
