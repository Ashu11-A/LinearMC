package io.linearmc.horizon;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import net.linear.AbstractRegionFile;
import net.linear.ChunkKey;
import net.linear.LinearRegionFile;
import net.linear.flush.FlushReports;
import net.linear.io.LinearWritePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Oversize chunk gate (fork parity: the {@code RegionFileStorage} write path
 * of Folia {@code minecraft-0010} / Canvas): a payload above
 * {@link LinearRegionFile#MAX_CHUNK_SIZE} is not stored, the chunk is deleted
 * from the region (anvil parity: vanilla also drops oversized chunks without
 * an external file), the {@code oversize} counter of {@code /linearstats}
 * moves and an error names the chunk.
 */
final class OversizeGate {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    private OversizeGate() {
    }

    /** Writes {@code normalized} or rejects it; returns whether it was stored. */
    static boolean writeOrReject(final AbstractRegionFile delegate, final Path realPath, final long chunk,
        final ByteBuffer normalized) throws IOException {
        if (!LinearWritePath.oversized(normalized)) {
            delegate.write(chunk, normalized);
            return true;
        }
        FlushReports.reportOversizeQuietly(realPath.getParent());
        LOGGER.error("Chunk at ({},{}) in regionfile '{}' exceeds max size of {}MiB, it has been deleted from disk.",
            ChunkKey.x(chunk), ChunkKey.z(chunk), realPath,
            LinearRegionFile.MAX_CHUNK_SIZE / (1024 * 1024));
        delegate.clear(chunk);
        return false;
    }
}
