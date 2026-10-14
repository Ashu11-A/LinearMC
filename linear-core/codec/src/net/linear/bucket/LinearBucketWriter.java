package net.linear.bucket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.codecs.V2Linear;
import net.linear.versions.base.V2LinearBase;
import net.linear.versions.codecs.V3Linear;
import net.linear.versions.Version;
import net.linear.versions.VersionWriteParams;
import net.linear.io.LinearDirectStreams;
import net.linear.LinearRegionFile;

/**
 * DivineMC-compatible V2 (wire 3) / V3 (wire 4) bucketed writer.
 *
 * <p>Thin delegate to the DivineMC-style one-file-per-version codecs
 * ({@code V2Linear}/{@code V3Linear} via {@link Version#flush}): the codec
 * renders the whole file image and this method persists it to {@code tmp}
 * (created/truncated, forced) for the caller to move into place, preserving
 * the original metrics. Called from legacy call sites (unlocked,
 * single-threaded per file); never touches locks, threads, or config — the
 * caller owns the snapshot, the tmp file, and the atomic move.</p>
 *
 * <p>Deliberate divergences from DivineMC: zstd checksum on (readers verify
 * transparently), tmp-file hygiene via caller, no silent timestamp reset.</p>
 */
public final class LinearBucketWriter {

    private static final int FOOTER_SIZE = 8;

    private LinearBucketWriter() {
    }

    /** Flush metrics for the caller (mirrors the V1 byte counters). */
    public record BucketResult(long totalRaw, long packedBytes) {
    }

    /**
     * Builds all buckets from the snapshot tables and streams the region to
     * {@code tmp} (created/truncated, forced). The caller moves {@code tmp}
     * into place.
     *
     * @param tmp target tmp path (parent must exist)
     * @param wire 3 (V2) or 4 (V3)
     * @param gridSize whitelisted grid (validated, fail-loud)
     * @param compressionLevel zstd level 1..22 (clamped)
     * @param snapSize uncompressed payload lengths (0 = absent)
     * @param snapTime per-slot epoch seconds
     * @param snapPacked LZ4 frames (direct read-only slices, null when absent)
     */
    public static BucketResult writeToTmp(Path tmp, int wire, int gridSize, int compressionLevel,
            int[] snapSize, int[] snapTime, ByteBuffer[] snapPacked) throws IOException {
        final Version version;
        try {
            version = LinearBase.getShared((byte) wire);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unsupported bucketed wire version " + wire + " for " + tmp, e);
        }
        if (!(version instanceof V2LinearBase)) {
            throw new IOException("Unsupported bucketed wire version " + wire + " for " + tmp);
        }
        final ChunkSlotTable table = new ChunkSlotTable();
        System.arraycopy(snapPacked, 0, table.lz4Payloads, 0, ChunkSlotTable.SLOTS);
        System.arraycopy(snapSize, 0, table.uncompressedSize, 0, ChunkSlotTable.SLOTS);
        System.arraycopy(snapTime, 0, table.timestamps, 0, ChunkSlotTable.SLOTS);
        long newest = 0L;
        long totalRaw = 0L;
        for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
            if (snapSize[i] > 0) {
                totalRaw += snapSize[i];
                if ((snapTime[i] & 0xFFFFFFFFL) > newest) {
                    newest = snapTime[i] & 0xFFFFFFFFL;
                }
            }
        }
        final int[] coords = parseRegionCoordinates(tmp.getFileName().toString());
        final VersionWriteParams params = new VersionWriteParams(
            compressionLevel, gridSize, coords[0], coords[1], newest,
            LinearRegionFile.linear$resolveCompressionWorkers(),
            LinearRegionFile.linear$resolveLongDistanceMatching());
        final ByteBuffer image;
        try {
            image = version.flush(table, params);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " for " + tmp, e);
        }
        final int headerLength = wire == 3 ? V2Linear.headerLength() : V3Linear.headerLength();
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            LinearDirectStreams.writeFully(channel, image);
            channel.force(true);
            long packedBytes = channel.position() - headerLength - FOOTER_SIZE;
            return new BucketResult(totalRaw, packedBytes);
        }
    }

    /** Region coords from {@code r.X.Z.*}; unparseable names yield 0,0 (never fail). */
    public static int[] parseRegionCoordinates(String fileName) {
        try {
            String[] parts = fileName.split("\\.");
            if (parts.length >= 4) {
                return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
            }
        } catch (RuntimeException ignored) {
        }
        return new int[] {0, 0};
    }
}
