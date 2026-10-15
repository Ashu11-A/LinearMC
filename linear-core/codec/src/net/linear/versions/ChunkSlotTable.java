package net.linear.versions;

import java.nio.ByteBuffer;

/**
 * Mutable 1024-slot tables exchanged between {@code LinearRegionFile} and the
 * {@link Version} codecs.
 *
 * <p>Contents are exactly what {@code LinearRegionFile} slot tables hold
 * today: LZ4-compressed payloads plus uncompressed sizes plus int
 * timestamps. {@code parse} fills a table; {@code flush} reads one as a
 * {@link ChunkSlotView}.</p>
 */
public final class ChunkSlotTable implements ChunkSlotView {

    /** Slots per region (32x32). */
    public static final int SLOTS = 1024;

    /** LZ4-compressed payloads, read-only direct slices; null slot == absent chunk. */
    public final ByteBuffer[] lz4Payloads = new ByteBuffer[SLOTS];

    /**
     * Root allocations behind {@link #lz4Payloads} (same slot index); the only
     * handles deterministic release accepts. Null where the view is heap or
     * otherwise not deterministically releasable (GC remains the fallback).
     */
    public final ByteBuffer[] lz4Roots = new ByteBuffer[SLOTS];

    /** Uncompressed payload lengths (0 == absent). */
    public final int[] uncompressedSize = new int[SLOTS];

    /** Per-chunk epoch seconds, preserved across load/flush (never zeroed). */
    public final int[] timestamps = new int[SLOTS];

    /**
     * Human context for diagnostics (e.g. the file path), set by the caller
     * before {@code parse}; codecs include it in log lines. Never null after
     * {@code LinearRegionFile.load} sets it; defaults to empty for tests.
     */
    public String sourceName = "";

    @Override
    public ByteBuffer[] lz4Payloads() {
        return this.lz4Payloads;
    }

    @Override
    public int[] uncompressedSize() {
        return this.uncompressedSize;
    }

    @Override
    public int[] timestamps() {
        return this.timestamps;
    }
}
