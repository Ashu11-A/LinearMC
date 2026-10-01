package io.linearmc.horizon;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.LinearEnvelopeCodec;

/**
 * Backward-compatible alias for {@link LinearEnvelopeCodec}.
 *
 * <p>The envelope→raw-NBT logic moved to the NMS-free core
 * ({@code linear-core/codec}) so every format owns it once; this class
 * keeps the old name compiling and delegates every call. New code must use
 * {@link LinearEnvelopeCodec} directly.
 *
 * @deprecated Use {@link LinearEnvelopeCodec} instead.
 */
@Deprecated
public final class RegionPayloadCodec {

    private RegionPayloadCodec() {
    }

    /**
     * Converts a {@code write(ChunkPos, ByteBuffer)} payload to raw NBT.
     *
     * @deprecated Delegates to {@link LinearEnvelopeCodec#toRawNbt(ByteBuffer)}.
     */
    @Deprecated
    public static byte[] toRawNbt(final ByteBuffer buf) throws IOException {
        return LinearEnvelopeCodec.toRawNbt(buf);
    }

    /**
     * Serves stored bytes tolerantly (raw passes through, legacy enveloped
     * slots unwrap on the fly).
     *
     * @deprecated Delegates to {@link LinearEnvelopeCodec#serveStored(byte[])}.
     */
    @Deprecated
    public static byte[] serveStored(final byte[] stored) throws IOException {
        return LinearEnvelopeCodec.serveStored(stored);
    }
}
