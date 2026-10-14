package net.linear.versions.codecs;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.LinearImplementation;
import net.linear.bucket.LinearBase;
import net.linear.versions.base.AbstractBucketedVersion;
import net.linear.versions.base.V2LinearBase;
import net.linear.versions.decoders.V3Decoder;
import net.linear.versions.encoders.V3Encoder;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.VersionWriteParams;

/**
 * V3 codec (wire 4: 10 B header + 12 B entries).
 *
 * <p>DivineMC bucketed layout without the V2 envelope: {@code 8 magic +
 * 1 ver(4) + 1 grid}. No timestamp, coords, bitmap or features. Bucket
 * bodies and footer are identical to V2; the level is a write-time choice
 * only and is not stored. Header rendering/parsing delegates to
 * {@code V3Decoder}/{@code V3Encoder}; the bucketed parse/flush flow lives
 * in {@code AbstractBucketedVersion}. Stateless shared instance,
 * thread-safe.</p>
 */
public final class V3Linear extends V2LinearBase {

    private static final V3Linear SHARED = new V3Linear();

    private V3Linear() {
    }

    /** Shared stateless instance (see {@code LinearBase.getShared}). */
    public static V3Linear shared() {
        return SHARED;
    }

    @Override
    public LinearImplementation implementation() {
        return LinearImplementation.V3;
    }

    @Override
    protected int wireVersion() {
        return 4;
    }

    @Override
    protected int tableEntrySize() {
        return 12;
    }

    @Override
    protected boolean hasLevelByte() {
        return false;
    }

    @Override
    protected int slotLengthBias() {
        // DivineMC stores len+8 on the wire.
        return 8;
    }

    @Override
    protected int readHeader(ByteBuffer raw) throws IOException {
        return V3Decoder.readHeader(raw);
    }

    @Override
    protected ByteBuffer writeHeader(ChunkSlotView slots, VersionWriteParams params) {
        return V3Encoder.writeHeader(slots, params);
    }

    /** Header byte length (fixed). */
    public static int headerLength() {
        return 8 + 1 + 1;
    }
}
