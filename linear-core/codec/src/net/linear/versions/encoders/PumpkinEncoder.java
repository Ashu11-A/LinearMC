package net.linear.versions.encoders;

import java.nio.ByteBuffer;
import net.linear.probe.LinearBucketGrid;
import net.linear.versions.codecs.PumpkinLinear;
import net.linear.versions.decoders.PumpkinDecoder;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.VersionWriteParams;

/**
 * Write side of the Pumpkin codec (wire 2, bucketed {@code .linear} layout).
 *
 * <p>The {@code PumpkinLinear} version class delegates its write hooks
 * here: {@code writeHeader} (newest timestamp, defaulted grid, region
 * coords, LSB-first bitmap, features terminator), {@code resolveGridSize}
 * (default 2 when unset or off-whitelist), {@code resolveCompressionLevel}
 * (fixed 1), {@code tableLevelByte} (always 1), {@code slotLengthBias} (0),
 * transposed interiors and dense empty buckets. Bucket numbering reuses
 * {@link PumpkinDecoder#bucketIndex} via the version class.</p>
 */
public final class PumpkinEncoder {

    private static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;

    /** Pumpkin default grid (upstream {@code DEFAULT_GRID_SIZE}). */
    public static final int DEFAULT_GRID_SIZE = 2;

    private PumpkinEncoder() {
    }

    /**
     * Validated bucket grid for {@code flush}: defaults to 2 when unset or
     * off-whitelist.
     */
    public static int resolveGridSize(VersionWriteParams params) {
        int grid = params.gridSize();
        return LinearBucketGrid.isValid(grid) ? grid : DEFAULT_GRID_SIZE;
    }

    /**
     * zstd level for the per-bucket frames: upstream-faithful fixed level.
     */
    public static int resolveCompressionLevel(VersionWriteParams params) {
        // Upstream-faithful fixed level; byte-identity with ruzstd is not required (NFR5).
        return 1;
    }

    /**
     * Level byte stored in each 13 B table entry: always 1 on the wire,
     * ignored on read (both here and upstream).
     */
    public static int tableLevelByte(int level) {
        // Always 1 on the wire, ignored on read (both here and upstream).
        return 1;
    }

    /** Stored-length bias: the raw NBT length is stored, not DivineMC's {@code len+8}. */
    public static int slotLengthBias() {
        return 0;
    }

    /** Bucket interiors iterate z-outer/x-inner, matching upstream. */
    public static boolean transposeSlotOrder() {
        return true;
    }

    /** Empty buckets are stored dense (compressed all-absent body), matching upstream. */
    public static boolean writeEmptyBucketsDense() {
        return true;
    }

    /**
     * Renders the Pumpkin header (fixed size: no features are ever written).
     */
    public static ByteBuffer writeHeader(ChunkSlotView slots, VersionWriteParams params) {
        int grid = resolveGridSize(params);
        int[] snapSize = slots.uncompressedSize();
        int[] snapTime = slots.timestamps();
        long newest = 0L;
        for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
            if (snapSize[i] > 0) {
                long ts = snapTime[i] & 0xFFFFFFFFL;
                if (ts > newest) {
                    newest = ts;
                }
            }
        }
        ByteBuffer header = ByteBuffer.allocate(8 + 1 + 8 + 1 + 4 + 4 + 128 + 1);
        header.putLong(SUPERBLOCK);
        header.put((byte) 2);
        header.putLong(newest);
        header.put((byte) grid);
        header.putInt(params.regionX());
        header.putInt(params.regionZ());
        for (int i = 0; i < 128; i++) {
            byte b = 0;
            for (int j = 0; j < 8; j++) {
                if (snapSize[i * 8 + j] > 0) {
                    b |= (1 << j);
                }
            }
            header.put(b);
        }
        header.put((byte) 0); // features terminator (no features)
        header.flip();
        return header;
    }
}
