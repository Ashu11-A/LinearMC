package net.linear;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.V2LinearBase;
import net.linear.versions.Version;

/**
 * DivineMC-compatible V2/V3 bucket reader (wire 3/4) inflating into V1 slot tables.
 *
 * <p>NMS-free ({@code net.linear} only + zstd/LZ4). Thin delegate to the
 * DivineMC-style one-file-per-version codecs ({@code V2Linear}/{@code V3Linear}
 * via {@link V2LinearBase#parseAfterVersion}); called from legacy call sites
 * with the buffer positioned just after the version byte. Production
 * {@code LinearRegionFile.load()} parses whole images via
 * {@link Version#parse} instead.</p>
 */
public final class LinearBucketReader {

    private LinearBucketReader() {
    }

    /** Valid grid sizes (DivineMC whitelist; single truth in {@link LinearBucketGrid}). */
    public static boolean isValidGrid(int grid) {
        return LinearBucketGrid.isValid(grid);
    }

    /**
     * Inflates V2/V3 buckets from {@code raw} (positioned just after the version
     * byte) into the V1 slot tables.
     *
     * @param raw positioned after SUPERBLOCK+VERSION; consumed through footer
     * @param wireVersion 3 (V2) or 4 (V3)
     */
    public static void inflate(ByteBuffer raw, int wireVersion, Path file,
            ByteBuffer[] staged, int[] stagedSize, int[] stagedTime) throws IOException {
        final Version version;
        try {
            version = LinearBase.getShared((byte) wireVersion);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unsupported bucketed version " + wireVersion + " in " + file, e);
        }
        if (!(version instanceof V2LinearBase)) {
            throw new IOException("Unsupported bucketed version " + wireVersion + " in " + file);
        }
        final ChunkSlotTable table = new ChunkSlotTable();
        try {
            ((V2LinearBase) version).parseAfterVersion(raw, table);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " in " + file, e);
        }
        System.arraycopy(table.lz4Payloads, 0, staged, 0, ChunkSlotTable.SLOTS);
        System.arraycopy(table.uncompressedSize, 0, stagedSize, 0, ChunkSlotTable.SLOTS);
        System.arraycopy(table.timestamps, 0, stagedTime, 0, ChunkSlotTable.SLOTS);
    }
}
