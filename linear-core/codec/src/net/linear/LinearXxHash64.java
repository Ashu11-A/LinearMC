package net.linear;

import java.nio.ByteBuffer;

/**
 * Minimal pure-Java XXH64 (seed 0) matching {@code LongHashFunction.xx()}.
 *
 * <p>NMS-free, dependency-free: avoids adding OpenHFT to {@code linear-core}
 * for a single hash. Byte-compatible with the DivineMC per-bucket hashes
 * (verified against fixture vectors in tests). Little-endian, seed 0.</p>
 */
public final class LinearXxHash64 {

    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private LinearXxHash64() {
    }

    /** Hashes {@code len} bytes from {@code src} at {@code off} (seed 0). */
    public static long hashBytes(byte[] src, int off, int len) {
        if (src == null) {
            throw new NullPointerException("src");
        }
        if (off < 0 || len < 0 || len > src.length - off) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len);
        }
        long h;
        int p = off;
        int end = off + len;
        if (len >= 32) {
            long v1 = P1 + P2;
            long v2 = P2;
            long v3 = 0L;
            long v4 = 0L - P1;
            int limit = end - 32;
            while (p <= limit) {
                v1 = round(v1, getLongLE(src, p));
                p += 8;
                v2 = round(v2, getLongLE(src, p));
                p += 8;
                v3 = round(v3, getLongLE(src, p));
                p += 8;
                v4 = round(v4, getLongLE(src, p));
                p += 8;
            }
            h = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7)
                + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h = mergeRound(h, v1);
            h = mergeRound(h, v2);
            h = mergeRound(h, v3);
            h = mergeRound(h, v4);
        } else {
            h = P5;
        }
        h += len;
        while (p + 8 <= end) {
            h ^= round(0L, getLongLE(src, p));
            h = Long.rotateLeft(h, 27) * P1 + P4;
            p += 8;
        }
        if (p + 4 <= end) {
            h ^= (getIntLE(src, p) & 0xFFFFFFFFL) * P1;
            h = Long.rotateLeft(h, 23) * P2 + P3;
            p += 4;
        }
        while (p < end) {
            h ^= (src[p] & 0xFFL) * P5;
            h = Long.rotateLeft(h, 11) * P1;
            p++;
        }
        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h;
    }

    /**
     * Hashes remaining bytes of {@code buf} without mutating position/limit
     * and without a heap copy of the payload: 32/8/4/1-byte little-endian
     * blocks are read straight from the buffer in encounter order, so output
     * is identical to {@link #hashBytes} (XXH64 seed 0, proven by the KATs).
     */
    public static long hashBuffer(ByteBuffer buf) {
        ByteBuffer dup = buf.duplicate();
        int len = dup.remaining();
        // Direct block processing from the buffer: same lane boundaries as
        // hashBytes, little-endian, seed 0. No heap copy of the payload.
        long h;
        int remaining = len;
        long v1 = 0L;
        long v2 = 0L;
        long v3 = 0L;
        long v4 = 0L;
        boolean striped = len >= 32;
        if (striped) {
            v1 = P1 + P2;
            v2 = P2;
            v3 = 0L;
            v4 = 0L - P1;
            int stripes = len / 32;
            for (int s = 0; s < stripes; s++) {
                v1 = round(v1, getLongLE(dup));
                v2 = round(v2, getLongLE(dup));
                v3 = round(v3, getLongLE(dup));
                v4 = round(v4, getLongLE(dup));
            }
            h = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7)
                + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h = mergeRound(h, v1);
            h = mergeRound(h, v2);
            h = mergeRound(h, v3);
            h = mergeRound(h, v4);
            remaining = len % 32;
        } else {
            h = P5;
        }
        h += len;
        while (remaining >= 8) {
            h ^= round(0L, getLongLE(dup));
            h = Long.rotateLeft(h, 27) * P1 + P4;
            remaining -= 8;
        }
        if (remaining >= 4) {
            h ^= (getIntLE(dup) & 0xFFFFFFFFL) * P1;
            h = Long.rotateLeft(h, 23) * P2 + P3;
            remaining -= 4;
        }
        while (remaining > 0) {
            h ^= (dup.get() & 0xFFL) * P5;
            h = Long.rotateLeft(h, 11) * P1;
            remaining--;
        }
        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h;
    }

    private static long getLongLE(ByteBuffer buf) {
        // Order-independent little-endian lane (file bytes in encounter order).
        long b0 = buf.get() & 0xFFL;
        long b1 = buf.get() & 0xFFL;
        long b2 = buf.get() & 0xFFL;
        long b3 = buf.get() & 0xFFL;
        long b4 = buf.get() & 0xFFL;
        long b5 = buf.get() & 0xFFL;
        long b6 = buf.get() & 0xFFL;
        long b7 = buf.get() & 0xFFL;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
            | (b4 << 32) | (b5 << 40) | (b6 << 48) | (b7 << 56);
    }

    private static int getIntLE(ByteBuffer buf) {
        int b0 = buf.get() & 0xFF;
        int b1 = buf.get() & 0xFF;
        int b2 = buf.get() & 0xFF;
        int b3 = buf.get() & 0xFF;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        acc = Long.rotateLeft(acc, 31);
        acc *= P1;
        return acc;
    }

    private static long mergeRound(long acc, long val) {
        val = round(0L, val);
        acc ^= val;
        acc = acc * P1 + P4;
        return acc;
    }

    private static long getLongLE(byte[] src, int p) {
        return (src[p] & 0xFFL)
            | ((src[p + 1] & 0xFFL) << 8)
            | ((src[p + 2] & 0xFFL) << 16)
            | ((src[p + 3] & 0xFFL) << 24)
            | ((src[p + 4] & 0xFFL) << 32)
            | ((src[p + 5] & 0xFFL) << 40)
            | ((src[p + 6] & 0xFFL) << 48)
            | ((src[p + 7] & 0xFFL) << 56);
    }

    private static int getIntLE(byte[] src, int p) {
        return (src[p] & 0xFF)
            | ((src[p + 1] & 0xFF) << 8)
            | ((src[p + 2] & 0xFF) << 16)
            | ((src[p + 3] & 0xFF) << 24);
    }
}
