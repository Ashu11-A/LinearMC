package net.linear.versions;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.LinearImplementation;

/**
 * V3 codec (wire 4: 10 B header + 12 B entries).
 *
 * <p>DivineMC bucketed layout without the V2 envelope: {@code 8 magic +
 * 1 ver(4) + 1 grid}. No timestamp, coords, bitmap or features. Bucket
 * bodies and footer are identical to V2; the level is a write-time choice
 * only and is not stored. Stateless shared instance, thread-safe.</p>
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
        // Phase 2: PumpkinLinear reuses V2LinearBase with 0.
        return 8;
    }

    @Override
    protected int readHeader(ByteBuffer raw) throws IOException {
        if (raw.remaining() < 1) {
            throw new IOException("Truncated V3 header");
        }
        int grid = raw.get() & 0xFF;
        checkGrid(grid);
        return grid;
    }

    @Override
    protected ByteBuffer writeHeader(ChunkSlotView slots, VersionWriteParams params) {
        ByteBuffer header = ByteBuffer.allocate(8 + 1 + 1);
        header.putLong(SUPERBLOCK);
        header.put((byte) 4);
        header.put((byte) params.gridSize());
        header.flip();
        return header;
    }

    /** Header byte length (fixed). */
    public static int headerLength() {
        return 8 + 1 + 1;
    }
}
