package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.junit.Test;

/**
 * Property/fuzz coverage for {@link LinearEnvelopeCodec}.
 *
 * <p>Round-trip fuzz: seeded-random NBT-like payloads (random bytes with a
 * {@code 0x0A} first byte, so they read as TAG_Compound roots) at sizes
 * 1/100/4K/64K/1M through every envelope (gzip/zlib/none/lz4) plus raw, via
 * both {@code toRawNbt} and {@code serveStored}, asserting byte equality.
 * The 8 MiB boundary and 8 MiB+1 use compressible fills ( Observability:
 * identical bytes, but random 8 MB barely compresses and burns test time in
 * the gzip/zlib paths) — the fuzz shape (first byte + length handling) is
 * what is under test at that scale.
 *
 * <p>Adversarial: truncated envelopes, length-prefix lies, versions
 * 127/0/255, empty buffers and 8 MiB+1 payloads all fail loud
 * ({@link IOException}); nothing returns garbage.
 */
public class FuzzEnvelopeTest {

    private static final int MAX_RAW = 8 * 1024 * 1024;
    private static final int OVER_CAP = MAX_RAW + 1;

    /** Fuzz sizes for the full envelope matrix (0 is fail-loud, tested separately). */
    private static final int[] FUZZ_SIZES = {1, 100, 4096, 65536, 1_048_576};

    private static final int[] BAD_VERSIONS = {0, 5, 9, 127, 255};

    private static byte[] randomNbtLike(int size, long seed) {
        byte[] payload = new byte[size];
        new Random(seed).nextBytes(payload);
        payload[0] = 10;
        return payload;
    }

    private static ByteBuffer envelope(int version, byte[] framed) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 1 + framed.length);
        buf.putInt(1 + framed.length);
        buf.put((byte) version);
        buf.put(framed);
        buf.flip();
        return buf;
    }

    private static byte[] envelopeBytes(int version, byte[] framed) {
        ByteBuffer env = envelope(version, framed);
        byte[] out = new byte[env.remaining()];
        env.get(out);
        return out;
    }

    private static byte[] zlib(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] lz4block(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4BlockOutputStream lz4 = new LZ4BlockOutputStream(out)) {
            lz4.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] framedFor(int version, byte[] raw) throws IOException {
        switch (version) {
            case 1:
                return gzip(raw);
            case 2:
                return zlib(raw);
            case 3:
                return raw.clone();
            case 4:
                return lz4block(raw);
            default:
                throw new IllegalArgumentException("bad test version " + version);
        }
    }

    private static void expectThrow(ByteBuffer buf) {
        try {
            LinearEnvelopeCodec.toRawNbt(buf);
            fail("expected IOException for " + describe(buf));
        } catch (IOException expected) {
        }
    }

    private static void expectServeThrow(byte[] stored) {
        try {
            LinearEnvelopeCodec.serveStored(stored);
            fail("expected IOException for stored[" + stored.length + "] first=" + (stored.length == 0
                ? "empty" : (stored[0] & 0xFF)));
        } catch (IOException expected) {
        }
    }

    private static String describe(ByteBuffer buf) {
        ByteBuffer dup = buf.duplicate();
        StringBuilder sb = new StringBuilder("remaining=" + dup.remaining() + " bytes=");
        int n = Math.min(dup.remaining(), 8);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", dup.get()));
        }
        return sb.toString();
    }

    @Test
    public void roundTripFuzzAllEnvelopesAndRaw() throws Exception {
        for (int size : FUZZ_SIZES) {
            byte[] payload = randomNbtLike(size, 0xC0FFEE + size);
            // Raw path: passthrough is the identical array.
            assertSame(payload, LinearEnvelopeCodec.serveStored(payload));
            assertArrayEquals(payload, LinearEnvelopeCodec.toRawNbt(ByteBuffer.wrap(payload)));
            for (int version = 1; version <= 4; version++) {
                byte[] framed = framedFor(version, payload);
                assertArrayEquals("size=" + size + " version=" + version,
                    payload, LinearEnvelopeCodec.toRawNbt(envelope(version, framed)));
                assertArrayEquals("serveStored size=" + size + " version=" + version,
                    payload, LinearEnvelopeCodec.serveStored(envelopeBytes(version, framed)));
            }
        }
    }

    @Test
    public void boundaryExactly8MiBPasses() throws Exception {
        // Compressible fill keeps the gzip/zlib/lz4 legs fast; the codec sees
        // the same framing/length logic as random bytes at this scale.
        byte[] payload = new byte[MAX_RAW];
        new Random(0xB0A5).nextBytes(payload);
        payload[0] = 10;
        assertArrayEquals(payload, LinearEnvelopeCodec.toRawNbt(ByteBuffer.wrap(payload)));
        assertArrayEquals(payload, LinearEnvelopeCodec.toRawNbt(envelope(3, payload)));
        assertArrayEquals(payload, LinearEnvelopeCodec.toRawNbt(envelope(2, zlib(payload))));
        assertArrayEquals(payload, LinearEnvelopeCodec.serveStored(envelopeBytes(1, gzip(payload))));
    }

    @Test
    public void sizeZeroFailsLoud() {
        expectThrow(ByteBuffer.allocate(0));
        expectServeThrow(new byte[0]);
        // A bare length prefix with no version byte is too short for an envelope.
        expectThrow(ByteBuffer.wrap(new byte[] {0, 0, 0, 1}));
    }

    @Test
    public void truncatedEnvelopesFailLoud() throws Exception {
        byte[] payload = randomNbtLike(4096, 0x7A);
        byte[] full = envelopeBytes(2, zlib(payload));
        // Chop the last byte, chop to half, keep only the length prefix.
        expectThrow(ByteBuffer.wrap(java.util.Arrays.copyOf(full, full.length - 1)));
        expectThrow(ByteBuffer.wrap(java.util.Arrays.copyOf(full, full.length / 2)));
        expectThrow(ByteBuffer.wrap(java.util.Arrays.copyOf(full, 4)));
        expectServeThrow(java.util.Arrays.copyOf(full, full.length - 1));
        // Truncated raw-looking prefix: first byte 0x0A passes through only
        // when whole; a torn envelope whose length lies is still an envelope.
        byte[] gzipFull = envelopeBytes(1, gzip(payload));
        expectServeThrow(java.util.Arrays.copyOf(gzipFull, 7));
    }

    @Test
    public void lengthPrefixLiesFailLoud() throws Exception {
        byte[] payload = randomNbtLike(256, 0x111);
        byte[] framed = zlib(payload);
        // Off-by-one both directions.
        ByteBuffer plus = ByteBuffer.allocate(4 + 1 + framed.length);
        plus.putInt(1 + framed.length + 1);
        plus.put((byte) 2);
        plus.put(framed);
        plus.flip();
        expectThrow(plus);
        ByteBuffer minus = ByteBuffer.allocate(4 + 1 + framed.length);
        minus.putInt(1 + framed.length - 1);
        minus.put((byte) 2);
        minus.put(framed);
        minus.flip();
        expectThrow(minus);
        // Wild lie and zero length.
        ByteBuffer wild = ByteBuffer.allocate(4 + 1 + framed.length);
        wild.putInt(999999);
        wild.put((byte) 2);
        wild.put(framed);
        wild.flip();
        expectThrow(wild);
        expectServeThrow(new byte[] {0, 0, 0, 0, 2, 0x0A});
    }

    @Test
    public void badVersionsFailLoud() throws Exception {
        byte[] payload = randomNbtLike(64, 0x222);
        for (int version : BAD_VERSIONS) {
            expectThrow(envelope(version, payload));
            expectServeThrow(envelopeBytes(version, payload));
        }
    }

    @Test
    public void nonCompoundDecodedFailsLoud() throws Exception {
        byte[] notNbt = new byte[] {1, 2, 3, 4};
        expectThrow(envelope(3, notNbt));
        expectThrow(envelope(2, zlib(notNbt)));
        expectServeThrow(envelopeBytes(3, notNbt));
    }

    @Test
    public void overCapFailsLoudOnEveryPath() throws Exception {
        // Version-3 (uncompressed) framing at 8 MiB+1: exercises the direct
        // length check (the streaming pump is bypassed for version 3).
        byte[] big = new byte[OVER_CAP];
        big[0] = 10;
        expectThrow(envelope(3, big));
        expectThrow(ByteBuffer.wrap(big));
        expectServeThrow(big);
        expectServeThrow(envelopeBytes(3, big));
        // Compressed 8 MiB+1 (zeros keep it fast): the pump cap fires mid-decode.
        byte[] zeros = new byte[OVER_CAP];
        zeros[0] = 10;
        expectThrow(envelope(2, zlib(zeros)));
        expectThrow(envelope(1, gzip(zeros)));
        expectThrow(envelope(4, lz4block(zeros)));
    }
}
