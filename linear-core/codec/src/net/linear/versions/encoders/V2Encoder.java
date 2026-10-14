package net.linear.versions.encoders;

import java.nio.ByteBuffer;
import net.linear.versions.codecs.V2Linear;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.VersionWriteParams;

/**
 * Write side of the V2 codec (wire 3: full header + 13 B entries).
 *
 * <p>The {@code V2Linear} version class delegates its {@code writeHeader}
 * here: current-time file timestamp, grid, region coords, MSB-first
 * existence bitmap, NBT-features terminator. V2 uses the default grid
 * validation and compression-level hooks.</p>
 */
public final class V2Encoder {

    private static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;

    private V2Encoder() {
    }

    /**
     * Renders the V2 header (fixed size: no features are ever written).
     */
    public static ByteBuffer writeHeader(ChunkSlotView slots, VersionWriteParams params) {
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
}
