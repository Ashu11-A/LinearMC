package net.linear.versions.codecs;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.LinearImplementation;
import net.linear.bucket.LinearBase;
import net.linear.versions.base.AbstractBucketedVersion;
import net.linear.versions.base.V2LinearBase;
import net.linear.versions.decoders.V2Decoder;
import net.linear.versions.encoders.V2Encoder;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.VersionWriteParams;

/**
 * V2 codec (wire 3: full header + 13 B entries).
 *
 * <p>DivineMC bucketed layout: {@code 8 magic + 1 ver(3) + 8 timestamp +
 * 1 grid + 4 regionX + 4 regionZ + 128 bitmap + features}, where features =
 * repeats of {@code [u8 nameLen][name][u32 value]} terminated by
 * {@code 0x00}. Bitmap is MSB-first and informational on read (presence =
 * table size != 0). Header rendering/parsing delegates to
 * {@code V2Decoder}/{@code V2Encoder}; the bucketed parse/flush flow lives
 * in {@code AbstractBucketedVersion}. Stateless shared instance,
 * thread-safe.</p>
 */
public final class V2Linear extends V2LinearBase {

    private static final V2Linear SHARED = new V2Linear();

    private V2Linear() {
    }

    /** Shared stateless instance (see {@code LinearBase.getShared}). */
    public static V2Linear shared() {
        return SHARED;
    }

    @Override
    public LinearImplementation implementation() {
        return LinearImplementation.V2;
    }

    @Override
    protected int wireVersion() {
        return 3;
    }

    @Override
    protected int tableEntrySize() {
        return 13;
    }

    @Override
    protected boolean hasLevelByte() {
        return true;
    }

    @Override
    protected int slotLengthBias() {
        // DivineMC stores len+8 on the wire.
        return 8;
    }

    @Override
    protected int readHeader(ByteBuffer raw) throws IOException {
        return V2Decoder.readHeader(raw);
    }

    @Override
    protected ByteBuffer writeHeader(ChunkSlotView slots, VersionWriteParams params) {
        return V2Encoder.writeHeader(slots, params);
    }

    /** Header byte length (fixed: no features are ever written). */
    public static int headerLength() {
        return 8 + 1 + 8 + 1 + 4 + 4 + 128 + 1;
    }
}
