package net.linear;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Write-path helpers for the Linear region file: oversize gate + defensive
 * buffer capture.
 */
public final class LinearWritePath {

    private LinearWritePath() {
    }

    /** True when {@code buf}'s remaining bytes exceed {@link LinearRegionFile#MAX_CHUNK_SIZE}. */
    public static boolean oversized(ByteBuffer buf) {
        Objects.requireNonNull(buf, "buf");
        return buf.remaining() > LinearRegionFile.MAX_CHUNK_SIZE;
    }

    /**
     * Defensive copy of {@code buf}'s remaining bytes into a fresh direct
     * buffer (allocateDirect + put + flip), leaving the source's position and
     * limit untouched.
     *
     * <p>Immediate capture semantics: the copy is detached from the caller, so
     * the caller's buffer may be reused right after return. Callers must pass
     * the returned copy (not the original) to {@code write()} and markDirty
     * after.</p>
     */
    public static ByteBuffer copyOf(ByteBuffer buf) {
        Objects.requireNonNull(buf, "buf");
        ByteBuffer src = buf.duplicate();
        ByteBuffer copy = ByteBuffer.allocateDirect(src.remaining());
        copy.put(src);
        copy.flip();
        return copy;
    }

    /**
     * Normalizes a {@code write} payload to raw NBT: empty buffers pass
     * through as-is (delete-slot sentinel); otherwise delegates to
     * {@link LinearEnvelopeCodec#toRawNbt(ByteBuffer)} and wraps the result.
     * Source position/limit are untouched (codec duplicates internally).
     */
    public static ByteBuffer normalizeWritePayload(ByteBuffer buf) throws IOException {
        Objects.requireNonNull(buf, "buf");
        if (buf.remaining() <= 0) {
            return buf;
        }
        return ByteBuffer.wrap(LinearEnvelopeCodec.toRawNbt(buf));
    }

    /**
     * Serves stored bytes tolerantly (raw passthrough, legacy envelope
     * unwrap). Delegates to {@link LinearEnvelopeCodec#serveStored(byte[])}.
     */
    public static byte[] serveReadBytes(byte[] stored) throws IOException {
        Objects.requireNonNull(stored, "stored");
        return LinearEnvelopeCodec.serveStored(stored);
    }
}
