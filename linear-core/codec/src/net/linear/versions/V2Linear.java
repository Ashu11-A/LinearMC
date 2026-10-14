package net.linear.versions;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.LinearImplementation;

/**
 * V2 codec (wire 3: full header + 13 B entries).
 *
 * <p>DivineMC bucketed layout: {@code 8 magic + 1 ver(3) + 8 timestamp +
 * 1 grid + 4 regionX + 4 regionZ + 128 bitmap + features}, where features =
 * repeats of {@code [u8 nameLen][name][u32 value]} terminated by
 * {@code 0x00}. Bitmap is MSB-first and informational on read (presence =
 * table size != 0). Stateless shared instance, thread-safe.</p>
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
        // Phase 2: PumpkinLinear reuses V2LinearBase with 0.
        return 8;
    }

    @Override
    protected int readHeader(ByteBuffer raw) throws IOException {
        if (raw.remaining() < 8 + 1 + 4 + 4 + 128 + 1) {
            throw new IOException("Truncated V2 header");
        }
        raw.getLong(); // file timestamp (informational)
        int grid = raw.get() & 0xFF;
        checkGrid(grid);
        raw.getInt(); // regionX (ignored; filename is authoritative)
        raw.getInt(); // regionZ
        raw.position(raw.position() + 128); // existence bitmap (parsed but unused, like DivineMC)
        skipFeatures(raw);
        return grid;
    }

    @Override
    protected ByteBuffer writeHeader(ChunkSlotView slots, VersionWriteParams params) {
        int grid = params.gridSize();
        int[] snapSize = slots.uncompressedSize();
        ByteBuffer header = ByteBuffer.allocate(8 + 1 + 8 + 1 + 4 + 4 + 128 + 1);
        header.putLong(SUPERBLOCK);
        header.put((byte) 3);
        header.putLong(System.currentTimeMillis() / 1000L);
        header.put((byte) grid);
        header.putInt(params.regionX());
        header.putInt(params.regionZ());
        for (int i = 0; i < 128; i++) {
            byte b = 0;
            for (int j = 0; j < 8; j++) {
                if (snapSize[i * 8 + j] > 0) {
                    b |= (1 << (7 - j));
                }
            }
            header.put(b);
        }
        header.put((byte) 0); // NBT-features terminator (no features)
        header.flip();
        return header;
    }

    /** Header byte length (fixed: no features are ever written). */
    public static int headerLength() {
        return 8 + 1 + 8 + 1 + 4 + 4 + 128 + 1;
    }
}
