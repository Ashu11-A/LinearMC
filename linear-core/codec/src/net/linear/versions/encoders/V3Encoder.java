package net.linear.versions.encoders;

import java.nio.ByteBuffer;
import net.linear.versions.codecs.V3Linear;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.VersionWriteParams;

/**
 * Write side of the V3 codec (wire 4: 10 B header + 12 B entries).
 *
 * <p>The {@code V3Linear} version class delegates its {@code writeHeader}
 * here: magic + wire byte + grid. The level is a write-time choice only
 * and is not stored.</p>
 */
public final class V3Encoder {

    private static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;

    private V3Encoder() {
    }

    /**
     * Renders the V3 header (fixed 10 B).
     */
    public static ByteBuffer writeHeader(ChunkSlotView slots, VersionWriteParams params) {
        ByteBuffer header = ByteBuffer.allocate(8 + 1 + 1);
        header.putLong(SUPERBLOCK);
        header.put((byte) 4);
        header.put((byte) params.gridSize());
        header.flip();
        return header;
    }
}
