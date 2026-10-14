package net.linear.versions;

import java.nio.ByteBuffer;

/**
 * Read view over the 1024 region slot tables.
 *
 * <p>Contents are exactly what {@code LinearRegionFile} slot tables hold:
 * LZ4-compressed payloads plus uncompressed sizes plus int timestamps.
 * The view is read-only by convention; mutation goes through
 * {@link ChunkSlotTable}.</p>
 */
public interface ChunkSlotView {

    /** LZ4-compressed payloads, read-only direct slices; null slot == absent chunk. */
    ByteBuffer[] lz4Payloads();

    /** Uncompressed payload lengths (0 == absent). */
    int[] uncompressedSize();

    /** Per-chunk epoch seconds, preserved across load/flush (never zeroed). */
    int[] timestamps();
}
