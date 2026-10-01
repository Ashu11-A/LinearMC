package io.linearmc.horizon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the 1.21.11 read-path corruption
 * ({@code IOException: Root tag must be a named compound tag} on every chunk
 * written through {@code write(ChunkPos, ByteBuffer)}, then discarded by
 * Moonrise as "chunk data will be lost").
 *
 * <p>Root cause: direct-write callers hand over the vanilla sector envelope
 * ({@code [len][version][compressed]}); storing it opaquely serves framing
 * bytes as the NBT root tag. The codec normalizes to raw NBT on write and
 * unwraps legacy enveloped slots on serve. NMS-free: runs on plain JUnit.
 */
public class RegionPayloadCodecTest {

    // Minimal fake NBT: TAG_Compound (10) + arbitrary body.
    private static final byte[] RAW = {10, 0, 5, 72, 101, 108, 108, 111, 1, 2, 3};

    private static ByteBuffer envelope(final int version, final byte[] framed) {
        final ByteBuffer buf = ByteBuffer.allocate(4 + 1 + framed.length);
        buf.putInt(1 + framed.length);
        buf.put((byte) version);
        buf.put(framed);
        buf.flip();
        return buf;
    }

    private static byte[] zlib(final byte[] raw) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] gzip(final byte[] raw) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] lz4frame(final byte[] raw) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Block format: matches vanilla RegionFileVersion.LZ4
        // (LZ4BlockOutputStream on write, LZ4BlockInputStream on read).
        try (LZ4BlockOutputStream lz4 = new LZ4BlockOutputStream(out)) {
            lz4.write(raw);
        }
        return out.toByteArray();
    }

    @Test
    public void rawNbtPassesThrough() throws IOException {
        assertArrayEquals(RAW, RegionPayloadCodec.toRawNbt(ByteBuffer.wrap(RAW)));
    }

    @Test
    public void zlibEnvelopeNormalizes() throws IOException {
        assertArrayEquals(RAW, RegionPayloadCodec.toRawNbt(envelope(2, zlib(RAW))));
    }

    @Test
    public void gzipEnvelopeNormalizes() throws IOException {
        assertArrayEquals(RAW, RegionPayloadCodec.toRawNbt(envelope(1, gzip(RAW))));
    }

    @Test
    public void noneEnvelopeNormalizes() throws IOException {
        assertArrayEquals(RAW, RegionPayloadCodec.toRawNbt(envelope(3, RAW)));
    }

    @Test
    public void lz4EnvelopeNormalizes() throws IOException {
        assertArrayEquals(RAW, RegionPayloadCodec.toRawNbt(envelope(4, lz4frame(RAW))));
    }

    @Test
    public void unknownVersionFailsLoud() {
        final ByteBuffer bad = envelope(9, new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> RegionPayloadCodec.toRawNbt(bad));
    }

    @Test
    public void customVersionFailsLoud() {
        final ByteBuffer bad = envelope(127, new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> RegionPayloadCodec.toRawNbt(bad));
    }

    @Test
    public void lengthMismatchFailsLoud() {
        final ByteBuffer bad = ByteBuffer.allocate(8);
        bad.putInt(9999);
        bad.put((byte) 2);
        bad.put(new byte[] {1, 2, 3});
        bad.flip();
        assertThrows(IOException.class, () -> RegionPayloadCodec.toRawNbt(bad));
    }

    @Test
    public void decodedNonCompoundFailsLoud() throws IOException {
        // Valid zlib of bytes that are NOT NBT must never be stored silently.
        assertThrows(IOException.class,
            () -> RegionPayloadCodec.toRawNbt(envelope(2, zlib(new byte[] {1, 2, 3}))));
    }

    @Test
    public void emptyFailsLoud() {
        assertThrows(IOException.class,
            () -> RegionPayloadCodec.toRawNbt(ByteBuffer.allocate(0)));
    }

    @Test
    public void serveStoredRawPassesThrough() throws IOException {
        assertSame(RAW, RegionPayloadCodec.serveStored(RAW));
    }

    @Test
    public void serveStoredLegacyEnvelopeUnwraps() throws IOException {
        final ByteBuffer env = envelope(2, zlib(RAW));
        final byte[] stored = new byte[env.remaining()];
        env.get(stored);
        assertArrayEquals(RAW, RegionPayloadCodec.serveStored(stored));
    }

    @Test
    public void serveStoredEmptyFailsLoud() {
        assertThrows(IOException.class, () -> RegionPayloadCodec.serveStored(new byte[0]));
    }
}
