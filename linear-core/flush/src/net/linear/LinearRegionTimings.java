package net.linear;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free timing instrumentation for the Linear
 * read/write/flush paths.
 *
 * <p>Lock-free: all hot updates use {@link LongAdder}/{@link LongAccumulator}
 * (no {@code synchronized}, no locks, no threads). Elapsed times are
 * {@code System.nanoTime() / 1000} micros recorded in {@code finally} blocks
 * AFTER lock exit, so ANVIL behaviour is byte-identical and no hot-interface
 * signature changes.</p>
 */
public final class LinearRegionTimings {

    private LinearRegionTimings() {
    }

    /**
     * One timing group: count + total/max/avg micros.
     */
    public record LinearTimings(long count, long totalMicros, long maxMicros, long avgMicros) {

        public static final LinearTimings EMPTY = new LinearTimings(0L, 0L, 0L, 0L);

        public static LinearTimings of(LongAdder count, LongAdder totalMicros, LongAccumulator maxMicros) {
            long c = count.sum();
            long t = totalMicros.sum();
            long m = c == 0L ? 0L : maxMicros.get();
            if (m < 0L) {
                m = 0L;
            }
            if (t < 0L) {
                t = 0L;
            }
            long avg = c == 0L ? 0L : t / c;
            return new LinearTimings(c, t, m, avg);
        }
    }

    /**
     * Per-file view for a single LinearRegionFile, returned by
     * {@code LinearRegionFile#linear$stats()}. Folder-wide aggregation lives
     * in {@link LinearFolderSnapshot} via the per-folder coordinator.
     */
    public record LinearRegionStats(
        LinearTimings read,
        LinearTimings write,
        LinearTimings flush,
        LinearTimings load
    ) {

        public static final LinearRegionStats EMPTY = new LinearRegionStats(
            LinearTimings.EMPTY, LinearTimings.EMPTY, LinearTimings.EMPTY, LinearTimings.EMPTY);
    }

    /**
     * Folder-wide snapshot: four timing groups plus lifecycle counts plus dirty depth
     * plus measurement fields (lock-free).
     *
     * <p>New fields: {@code rawBytes}/{@code compressedBytes} are summed flush
     * image bytes (uncompressed vs zstd); {@code flushP50Micros}/
     * {@code flushP99Micros} are bucketed latency estimates from actual I/O
     * flushes only (clean-no-ops excluded, same as {@code flush.count});
     * {@code millisSinceLastFlush} is wall time since the last successful
     * folder flush ({@code -1} when never flushed). {@code markDirty},
     * {@code cacheHits}/{@code cacheMisses} are exposed so operators can
     * separate "never invoked" (all zero, no snapshot entry) from "empty set"
     * (entry present but counts zero) -- empty-set visibility fix.
     */
    public record LinearFolderSnapshot(
        LinearTimings read,
        LinearTimings write,
        LinearTimings flush,
        LinearTimings load,
        long markDirty,
        long filesFlushed,
        long failures,
        long oversizeRejects,
        long evicts,
        long cacheHits,
        long cacheMisses,
        int dirtyDepth,
        long rawBytes,
        long compressedBytes,
        long flushP50Micros,
        long flushP99Micros,
        long millisSinceLastFlush
    ) {

        public static final LinearFolderSnapshot EMPTY = new LinearFolderSnapshot(
            LinearTimings.EMPTY, LinearTimings.EMPTY, LinearTimings.EMPTY, LinearTimings.EMPTY,
            0L, 0L, 0L, 0L, 0L, 0L, 0L, 0,
            0L, 0L, 0L, 0L, -1L);
    }
}
