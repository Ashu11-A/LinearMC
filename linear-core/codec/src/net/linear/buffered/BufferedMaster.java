package net.linear.buffered;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHashFactory;
import net.linear.BufferedLinearRegionFile;
import net.linear.LinearRegionFile;

/**
 * Slot-envelope codecs for the buffered-linear master image.
 *
 * <p>Owns the inner ({@code [int32 dataLen][int64 timestampMs][int32 xxhash32]
 * [payload]}) and outer ({@code [int32 decompressedLen][LZ4 bytes]}) envelope
 * codecs verbatim from {@code BufferedLinearRegionFile}; the owner delegates.
 * Single source of truth for master bounds as well.</p>
 */
public final class BufferedMaster {

    /** Master magic (DivineMC-compatible). */
    public static final long MASTER_SUPERBLOCK = -0x200812250269L;

    /** Master layout version. */
    public static final byte MASTER_VERSION = 2;

    /** Magic + version + newest timestamp + level. */
    public static final int MASTER_HEADER_SIZE = 8 + 1 + 8 + 1;

    /** Auto-compact when spare exceeds 1 MiB and 60% of live bytes. */
    public static final long COMPACT_SPARE_BYTES = 1024L * 1024L;

    /** Cap guarding the single master-image allocation against corrupt lengths. */
    public static final long MAX_MASTER_BYTES = 1024L * 1024L * 1024L;

    private static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();
    private static final XXHash32 HASH32 = XXHashFactory.fastestInstance().hash32();

    private BufferedMaster() {
    }

    /** Builds the inner envelope {@code [len, timestampMs, xxhash32, payload]}. */
    public static byte[] encodeInner(byte[] payload, long timestampMs, int hashSeed) {
        ByteBuffer envelope = ByteBuffer.allocate(4 + 8 + 4 + payload.length);
        envelope.putInt(payload.length);
        envelope.putLong(timestampMs);
        envelope.putInt(HASH32.hash(payload, 0, payload.length, hashSeed));
        envelope.put(payload);
        return envelope.array();
    }

    /** Verifies an inner envelope and returns its payload. */
    public static byte[] decodeInner(byte[] envelope, int slot, Path file, int hashSeed) throws IOException {
        if (envelope.length < 16) {
            throw new IOException("Truncated envelope in slot " + slot + " of " + file);
        }
        ByteBuffer buf = ByteBuffer.wrap(envelope);
        int dataLen = buf.getInt();
        buf.getLong();
        int hash = buf.getInt();
        if (dataLen < 0 || dataLen > LinearRegionFile.MAX_CHUNK_SIZE || dataLen != envelope.length - 16) {
            throw new IOException("Invalid envelope length " + dataLen + " in slot " + slot + " of " + file);
        }
        byte[] payload = new byte[dataLen];
        buf.get(payload);
        if (HASH32.hash(payload, 0, payload.length, hashSeed) != hash) {
            throw new IOException("XXHash32 check failed in slot " + slot + " of " + file);
        }
        return payload;
    }

    public static void verifyInner(byte[] envelope, int slot, Path file, int hashSeed) throws IOException {
        decodeInner(envelope, slot, file, hashSeed);
    }

    public static long envelopeTimestamp(byte[] envelope) {
        if (envelope.length < 12) {
            return 0L;
        }
        return ByteBuffer.wrap(envelope, 4, 8).getLong();
    }

    /** Outer layer: {@code [int32 decompressedLen][LZ4 bytes]} of a fresh inner envelope. */
    public static byte[] encodeOuter(byte[] payload, Path file, int hashSeed) throws IOException {
        byte[] envelope = encodeInner(payload, System.currentTimeMillis(), hashSeed);
        int maxCompressed = LZ4.fastCompressor().maxCompressedLength(envelope.length);
        byte[] packed = new byte[maxCompressed + 4];
        ByteBuffer outer = ByteBuffer.wrap(packed);
        outer.putInt(envelope.length);
        int n;
        try {
            n = LZ4.fastCompressor().compress(envelope, 0, envelope.length, packed, 4, maxCompressed);
        } catch (RuntimeException e) {
            throw new IOException("LZ4 compress failed for " + file + ": " + e.getMessage(), e);
        }
        byte[] out = new byte[n + 4];
        System.arraycopy(packed, 0, out, 0, n + 4);
        return out;
    }

    /** Decodes an outer envelope all the way to the raw payload. */
    public static byte[] decodeOuter(byte[] outer, int slot, Path file, int hashSeed) throws IOException {
        return decodeInner(decodeOuterToEnvelope(outer, slot, file), slot, file, hashSeed);
    }

    public static byte[] decodeOuterToEnvelope(byte[] outer, int slot, Path file) throws IOException {
        if (outer.length < 4) {
            throw new IOException("Truncated sector in slot " + slot + " of " + file);
        }
        int decompressedLen = ByteBuffer.wrap(outer, 0, 4).getInt();
        if (decompressedLen < 16 || decompressedLen > LinearRegionFile.MAX_CHUNK_SIZE + 16) {
            throw new IOException("Invalid sector length " + decompressedLen + " in slot " + slot + " of " + file);
        }
        byte[] envelope = new byte[decompressedLen];
        int consumed;
        try {
            consumed = LZ4.fastDecompressor().decompress(outer, 4, envelope, 0, decompressedLen);
        } catch (RuntimeException e) {
            throw new IOException("LZ4 decompress failed in slot " + slot + " of " + file + ": " + e.getMessage(),
                e);
        }
        if (consumed != outer.length - 4) {
            throw new IOException("LZ4 frame mismatch in slot " + slot + " of " + file);
        }
        return envelope;
    }

    public static byte[] lz4Outer(byte[] inner) {
        int maxCompressed = LZ4.fastCompressor().maxCompressedLength(inner.length);
        byte[] packed = new byte[maxCompressed + 4];
        ByteBuffer outer = ByteBuffer.wrap(packed);
        outer.putInt(inner.length);
        int n = LZ4.fastCompressor().compress(inner, 0, inner.length, packed, 4, maxCompressed);
        byte[] out = new byte[n + 4];
        System.arraycopy(packed, 0, out, 0, n + 4);
        return out;
    }
}
