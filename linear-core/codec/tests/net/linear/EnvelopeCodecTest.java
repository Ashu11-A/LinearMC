package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.junit.Test;

/** Envelope matrix for {@link LinearEnvelopeCodec} (JUnit4 port of the horizon suite). */
public class EnvelopeCodecTest {

    private static final byte[] RAW = {10, 0, 5, 72, 101, 108, 108, 111, 1, 2, 3};

    private static ByteBuffer envelope(int version, byte[] framed) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 1 + framed.length);
        buf.putInt(1 + framed.length);
        buf.put((byte) version);
        buf.put(framed);
        buf.flip();
        return buf;
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

    @Test
    public void rawPassthrough() throws Exception {
        assertArrayEquals(RAW, LinearEnvelopeCodec.toRawNbt(ByteBuffer.wrap(RAW)));
    }

    @Test
    public void gzipEnvelopeNormalizes() throws Exception {
        assertArrayEquals(RAW, LinearEnvelopeCodec.toRawNbt(envelope(1, gzip(RAW))));
    }

    @Test
    public void zlibEnvelopeNormalizes() throws Exception {
        assertArrayEquals(RAW, LinearEnvelopeCodec.toRawNbt(envelope(2, zlib(RAW))));
    }

    @Test
    public void noneEnvelopeNormalizes() throws Exception {
        assertArrayEquals(RAW, LinearEnvelopeCodec.toRawNbt(envelope(3, RAW)));
    }

    @Test
    public void lz4BlockEnvelopeNormalizes() throws Exception {
        assertArrayEquals(RAW, LinearEnvelopeCodec.toRawNbt(envelope(4, lz4block(RAW))));
    }

    @Test
    public void unknownVersionFails() {
        try {
            LinearEnvelopeCodec.toRawNbt(envelope(9, new byte[] {1, 2, 3}));
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void custom127Fails() {
        try {
            LinearEnvelopeCodec.toRawNbt(envelope(127, new byte[] {1, 2, 3}));
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void lengthMismatchFails() {
        ByteBuffer bad = ByteBuffer.allocate(8);
        bad.putInt(9999);
        bad.put((byte) 2);
        bad.put(new byte[] {1, 2, 3});
        bad.flip();
        try {
            LinearEnvelopeCodec.toRawNbt(bad);
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void nonCompoundDecodeFails() throws Exception {
        try {
            LinearEnvelopeCodec.toRawNbt(envelope(2, zlib(new byte[] {1, 2, 3})));
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void emptyFails() {
        try {
            LinearEnvelopeCodec.toRawNbt(ByteBuffer.allocate(0));
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void serveStoredRawPassthrough() throws Exception {
        assertSame(RAW, LinearEnvelopeCodec.serveStored(RAW));
    }

    @Test
    public void serveStoredLegacyEnvelopedUnwraps() throws Exception {
        ByteBuffer env = envelope(2, zlib(RAW));
        byte[] stored = new byte[env.remaining()];
        env.get(stored);
        assertArrayEquals(RAW, LinearEnvelopeCodec.serveStored(stored));
    }

    @Test
    public void serveStoredEmptyFails() {
        try {
            LinearEnvelopeCodec.serveStored(new byte[0]);
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }
}
