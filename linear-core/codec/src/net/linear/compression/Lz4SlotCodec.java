package net.linear.compression;

import java.nio.ByteBuffer;
import net.jpountz.lz4.LZ4Factory;
import net.linear.versions.base.V2LinearBase;
import net.linear.versions.codecs.V1Linear;

/**
 * Shared LZ4 slot payload codec (direct buffers, read-only slices).
 *
 * <p>Consolidates the identical {@code lz4Pack} helpers previously
 * duplicated in {@code V1Linear} and {@code V2LinearBase}, with no
 * behavior change: worst-case direct staging, {@code fastCompressor},
 * sliced read-only view of the used prefix.</p>
 */
public final class Lz4SlotCodec {

    private static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();

    private Lz4SlotCodec() {
    }

    /**
     * Compresses {@code plain} (remaining bytes) into a direct buffer holding
     * exactly the used prefix (position 0, limit {@code packedSize}).
     * Positions of {@code plain} are left untouched (absolute compress).
     *
     * <p>Returns the <em>root</em> allocation: it is the only handle
     * {@code Unsafe.invokeCleaner} accepts (slices, duplicates and
     * read-only views are rejected). Derive shared views with
     * {@code asReadOnlyBuffer()} and free only the root.
     */
    public static ByteBuffer pack(ByteBuffer plain) {
        int size = plain.remaining();
        int maxCompressed = LZ4.fastCompressor().maxCompressedLength(size);
        ByteBuffer packed = ByteBuffer.allocateDirect(maxCompressed);
        int packedSize = LZ4.fastCompressor().compress(plain, plain.position(), size, packed, 0, maxCompressed);
        packed.position(0);
        packed.limit(packedSize);
        // Tight copy: retain only the used prefix. The worst-case staging is
        // freed immediately; compressible payloads (typical chunk NBT) would
        // otherwise pin multiples of their stored size per slot forever.
        final ByteBuffer tight = ByteBuffer.allocateDirect(packedSize);
        net.linear.io.LinearDirect.recordAlloc(packedSize);
        tight.put(packed.duplicate());
        tight.flip();
        net.linear.io.LinearDirect.free(packed);
        return tight;
    }

    /** Alias for {@link #pack(ByteBuffer)} (matches the historic helper name). */
    public static ByteBuffer lz4Pack(ByteBuffer plain) {
        return pack(plain);
    }

    /** Heap-array variant of {@link #pack(ByteBuffer)}. */
    public static ByteBuffer copyPack(byte[] payload) {
        return pack(ByteBuffer.wrap(payload));
    }

    /**
     * Decompresses {@code frame} (remaining bytes, position untouched) into
     * {@code dst} at absolute {@code dstOffset}, expecting {@code size}
     * bytes out. Returns the consumed input bytes (must equal
     * {@code frame.remaining()} for an exact frame).
     */
    public static int unpack(ByteBuffer frame, ByteBuffer dst, int dstOffset, int size) {
        return LZ4.fastDecompressor().decompress(frame, frame.position(), dst, dstOffset, size);
    }
}
