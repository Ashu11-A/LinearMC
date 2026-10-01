package net.linear;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.jpountz.lz4.LZ4BlockInputStream;

/**
 * Normalizes region chunk payloads to raw NBT (multi-version axis #2).
 *
 * <p>Vanilla/Moonrise {@code RegionFile.write(ChunkPos, ByteBuffer)} receives
 * the sector envelope {@code [int32 BE length][u8 version][compressed…]}
 * (verified byte-level on Folia 1.21.11: {@code 00 00 01 60 02 78 9c…} =
 * length 352, version 2/zlib), while {@code getChunkDataInputStream} must
 * serve RAW NBT (vanilla unwraps via {@code RegionFileVersion} before
 * {@code NbtIo} parses). Storing the envelope opaquely serves the version
 * byte (or a length prefix) as the NBT tag type → {@code IOException: Root
 * tag must be a named compound tag} on every affected read, with Moonrise
 * then discarding the chunk ("chunk data will be lost").
 *
 * <p>Version ids follow {@code RegionFileVersion} (stable since 1.15):
 * 1 = gzip, 2 = zlib/deflate, 3 = none, 4 = LZ4-frame; 127 = custom
 * (undecodable → fail-loud). A payload starting with {@code 10}
 * ({@code TAG_Compound}) is already raw NBT (the streaming write path) and
 * passes through untouched, so this codec is a no-op wherever callers already
 * hand over raw bytes — safe on every mappings line by construction.
 *
 * <p>Fail-loud everywhere: unknown versions, length mismatches, and decoded
 * output that is not a compound all throw {@link IOException}. A wrong guess
 * here is silent world corruption; an exception is a log line.
 *
 * <p>Canonical home of the logic formerly known as
 * {@code io.linearmc.horizon.RegionPayloadCodec} (now a deprecated thin
 * delegation shim). Final with a private constructor; use the static methods.
 */
public final class LinearEnvelopeCodec {

    /** NBT TAG_Compound — the only legal root tag. */
    public static final int TAG_COMPOUND = 10;

    /** RegionFileVersion ids we can decode. */
    private static final int VERSION_GZIP = 1;
    private static final int VERSION_ZLIB = 2;
    private static final int VERSION_NONE = 3;
    private static final int VERSION_LZ4 = 4;

    /** Hard cap: a raw chunk payload above this is not a chunk (8 MiB). */
    private static final int MAX_RAW = 8 * 1024 * 1024;

    private LinearEnvelopeCodec() {
    }

    /**
     * Converts a {@code write(ChunkPos, ByteBuffer)} payload to raw NBT.
     * Accepts the enveloped form ({@code [len][version][data…]}) or already-raw
     * NBT (first byte {@code 10}). Never returns enveloped bytes.
     */
    public static byte[] toRawNbt(final ByteBuffer buf) throws IOException {
        final ByteBuffer in = buf.duplicate();
        final int remaining = in.remaining();
        if (remaining <= 0) {
            throw new IOException("LinearMC: refusing empty chunk payload");
        }
        if ((in.get(in.position()) & 0xFF) == TAG_COMPOUND) {
            final byte[] raw = copyRemaining(in);
            requireRawSize(raw);
            return raw;
        }
        if (remaining < 6) {
            throw new IOException(
                "LinearMC: chunk payload too short for an envelope (" + remaining + " bytes)");
        }
        final int length = in.getInt();
        if (length != remaining - 4) {
            throw new IOException("LinearMC: envelope length prefix " + length
                + " does not match payload size " + (remaining - 4));
        }
        final int version = in.get() & 0xFF;
        final byte[] framed = new byte[in.remaining()];
        in.get(framed);
        final byte[] raw = decode(version, framed);
        requireCompound(raw, version);
        return raw;
    }

    /**
     * Serves stored bytes tolerantly: already-raw payloads pass through;
     * legacy enveloped slots (written before normalization) are unwrapped on
     * the fly so old data becomes readable without a migration step.
     */
    public static byte[] serveStored(final byte[] stored) throws IOException {
        if (stored.length == 0) {
            throw new IOException("LinearMC: refusing empty stored payload");
        }
        if ((stored[0] & 0xFF) == TAG_COMPOUND) {
            requireRawSize(stored);
            return stored;
        }
        return toRawNbt(ByteBuffer.wrap(stored));
    }

    private static byte[] decode(final int version, final byte[] framed) throws IOException {
        try {
            switch (version) {
                case VERSION_GZIP:
                    return pump(new GZIPInputStream(new ByteArrayInputStream(framed)));
                case VERSION_ZLIB:
                    return pump(new InflaterInputStream(new ByteArrayInputStream(framed)));
                case VERSION_NONE:
                    // Uncompressed framing bypasses the streaming pump, so the
                    // 8 MiB cap must be enforced here, not just in pump().
                    if (framed.length > MAX_RAW) {
                        throw new IOException("LinearMC: version-3 payload exceeds 8 MiB ("
                            + framed.length + " bytes)");
                    }
                    return Arrays.copyOf(framed, framed.length);
                case VERSION_LZ4:
                    return pump(new LZ4BlockInputStream(new ByteArrayInputStream(framed)));
                default:
                    throw new IOException(
                        "LinearMC: unsupported region payload version " + version
                            + " (custom region compression needs explicit support)");
            }
        } catch (final IOException bad) {
            throw new IOException(
                "LinearMC: failed to decode version-" + version + " payload", bad);
        }
    }

    private static void requireCompound(final byte[] raw, final int version) throws IOException {
        requireRawSize(raw);
        if (raw.length == 0 || (raw[0] & 0xFF) != TAG_COMPOUND) {
            throw new IOException("LinearMC: version-" + version
                + " payload does not decode to NBT (first byte "
                + (raw.length == 0 ? "missing" : (raw[0] & 0xFF)) + ")");
        }
    }

    /**
     * Uniform 8 MiB cap for every path that yields raw NBT: the streaming
     * pump enforces it mid-decode, but uncompressed framing and already-raw
     * passthrough never reach the pump, so they are checked here instead.
     * A raw chunk payload above this is not a chunk — fail loud, never store.
     */
    private static void requireRawSize(final byte[] raw) throws IOException {
        if (raw.length > MAX_RAW) {
            throw new IOException("LinearMC: raw payload exceeds 8 MiB (" + raw.length + " bytes)");
        }
    }

    private static byte[] copyRemaining(final ByteBuffer in) {
        final byte[] out = new byte[in.remaining()];
        in.get(out);
        return out;
    }

    private static byte[] pump(final InputStream in) throws IOException {
        try (InputStream autoClose = in;
             ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16)) {
            final byte[] scratch = new byte[32 * 1024];
            int total = 0;
            int n;
            while ((n = autoClose.read(scratch)) != -1) {
                total += n;
                if (total > MAX_RAW) {
                    throw new IOException("LinearMC: decoded payload exceeds 8 MiB");
                }
                out.write(scratch, 0, n);
            }
            return out.toByteArray();
        }
    }
}
