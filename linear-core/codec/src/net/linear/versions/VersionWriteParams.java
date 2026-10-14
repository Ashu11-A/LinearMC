package net.linear.versions;

/**
 * Flush inputs for {@link Version#flush(ChunkSlotView, VersionWriteParams)}.
 *
 * @param compressionLevel zstd level 1..22 (already clamped by the caller)
 * @param gridSize bucket grid for V2/V3 (whitelisted; ignored for V1)
 * @param regionX region coordinate parsed from the file name
 * @param regionZ region coordinate parsed from the file name
 * @param newestTimestamp max slot timestamp (unsigned) for the V1 header
 * @param workers zstd worker count (0 = serial; V1 only, see below)
 * @param ldm long-distance-matching windowLog (0 = off; V1 only, see below)
 */
public record VersionWriteParams(
    int compressionLevel,
    int gridSize,
    int regionX,
    int regionZ,
    long newestTimestamp,
    int workers,
    int ldm) {
}
