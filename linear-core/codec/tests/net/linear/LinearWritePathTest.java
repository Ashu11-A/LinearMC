package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.DeflaterOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Write-path gates: oversize boundary, detached copy, empty-deletes-slot. */
public class LinearWritePathTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static ByteBuffer mappedOfSize(Path file, long size) throws Exception {
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ch.truncate(size);
            MappedByteBuffer mapped = ch.map(FileChannel.MapMode.READ_WRITE, 0, size);
            return mapped;
        }
    }

    @Test
    public void oversizedRejectsMaxPlusOne() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("max-plus-one.bin");
        ByteBuffer buf = mappedOfSize(file, (long) LinearRegionFile.MAX_CHUNK_SIZE + 1L);
        assertTrue(LinearWritePath.oversized(buf));
    }

    @Test
    public void boundaryMaxIsNotOversized() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("max.bin");
        ByteBuffer buf = mappedOfSize(file, LinearRegionFile.MAX_CHUNK_SIZE);
        assertFalse(LinearWritePath.oversized(buf));
    }

    @Test
    public void normalIsNotOversized() {
        ByteBuffer buf = ByteBuffer.allocateDirect(16);
        buf.put(new byte[16]);
        buf.flip();
        assertFalse(LinearWritePath.oversized(buf));
    }

    @Test
    public void copyOfLeavesSourceUntouchedAndDetached() {
        ByteBuffer src = ByteBuffer.allocateDirect(8);
        src.put(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        src.flip();
        src.position(2);
        src.limit(6);
        int pos = src.position();
        int lim = src.limit();
        ByteBuffer copy = LinearWritePath.copyOf(src);
        assertEquals(pos, src.position());
        assertEquals(lim, src.limit());
        assertTrue(copy.isDirect());
        assertEquals(4, copy.remaining());
        byte[] got = new byte[4];
        copy.get(got);
        assertArrayEquals(new byte[] {3, 4, 5, 6}, got);
        // Detached: mutating the source range does not affect the copy.
        src.put(2, (byte) 99);
        ByteBuffer again = LinearWritePath.copyOf(src);
        assertEquals(4, again.remaining());
    }

    @Test
    public void copyOfEmptyDeletesSlot() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("r.0.0.linear");
        long chunk = ChunkKey.of(0, 0);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            ByteBuffer payload = ByteBuffer.allocateDirect(4);
            payload.put(new byte[] {1, 2, 3, 4});
            payload.flip();
            region.write(chunk, payload);
            assertTrue(region.hasChunk(chunk));
            ByteBuffer empty = ByteBuffer.allocate(0);
            ByteBuffer copy = LinearWritePath.copyOf(empty);
            region.write(chunk, copy);
            assertFalse(region.hasChunk(chunk));
        }
    }

    private static final byte[] RAW_NBT = {10, 0, 5, 72, 101, 108, 108, 111, 1, 2, 3};

    private static ByteBuffer zlibEnvelope(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(raw);
        }
        byte[] framed = out.toByteArray();
        ByteBuffer buf = ByteBuffer.allocate(4 + 1 + framed.length);
        buf.putInt(1 + framed.length);
        buf.put((byte) 2);
        buf.put(framed);
        buf.flip();
        return buf;
    }

    @Test
    public void normalizeEmptyPassthrough() throws Exception {
        ByteBuffer empty = ByteBuffer.allocate(0);
        assertSame(empty, LinearWritePath.normalizeWritePayload(empty));
        ByteBuffer consumed = ByteBuffer.wrap(new byte[] {1, 2, 3});
        consumed.position(3);
        assertSame(consumed, LinearWritePath.normalizeWritePayload(consumed));
    }

    @Test
    public void normalizeEnvelopedToRaw() throws Exception {
        ByteBuffer enveloped = zlibEnvelope(RAW_NBT);
        int pos = enveloped.position();
        int lim = enveloped.limit();
        ByteBuffer raw = LinearWritePath.normalizeWritePayload(enveloped);
        assertEquals(pos, enveloped.position());
        assertEquals(lim, enveloped.limit());
        byte[] got = new byte[raw.remaining()];
        raw.get(got);
        assertArrayEquals(RAW_NBT, got);
    }

    @Test
    public void normalizeRawPassthrough() throws Exception {
        ByteBuffer raw = ByteBuffer.wrap(RAW_NBT.clone());
        ByteBuffer out = LinearWritePath.normalizeWritePayload(raw);
        byte[] got = new byte[out.remaining()];
        out.get(got);
        assertArrayEquals(RAW_NBT, got);
    }

    @Test
    public void serveReadBytesRawPassthrough() throws Exception {
        assertArrayEquals(RAW_NBT, LinearWritePath.serveReadBytes(RAW_NBT.clone()));
    }

    @Test
    public void serveReadBytesLegacyUnwrap() throws Exception {
        ByteBuffer env = zlibEnvelope(RAW_NBT);
        byte[] stored = new byte[env.remaining()];
        env.get(stored);
        assertArrayEquals(RAW_NBT, LinearWritePath.serveReadBytes(stored));
    }
}
