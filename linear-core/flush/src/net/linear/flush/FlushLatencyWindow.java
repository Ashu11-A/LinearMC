package net.linear.flush;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionTimings;

/**
 * Folder-wide timing + windowed flush-latency histogram.
 *
 * <p>One instance per {@link LinearFlushCoordinator} (field {@code timings});
 * the coordinator keeps thin {@code record*} / {@code snapshot()} facades so
 * call sites and tests are unchanged. All hot updates stay lock-free
 * ({@code LongAdder}/{@code LongAccumulator} only); {@code dirtyDepth} is
 * supplied by the caller (read under the coordinator monitor).</p>
 */
public final class FlushLatencyWindow {

    /** Samples per generation before rotation (burst bound). */
    public static final int FLUSH_WINDOW_SAMPLES = 1024;
    /** Generation age before rotation (quiet-server bound). */
    public static final long FLUSH_WINDOW_NANOS = 5L * 60L * 1_000_000_000L;
    /** Minimum current-generation samples before p50/p99 trust it. */
    public static final int FLUSH_WINDOW_MIN_SAMPLES = 10;
    /** Sentinel returned for the unbounded top bucket (5s + 1us). */
    public static final long FLUSH_TOP_SENTINEL_MICROS = 5_000_001L;
    private static final long[] FLUSH_BUCKET_UPPER = new long[]{
        1_000L, 5_000L, 10_000L, 25_000L, 50_000L,
        100_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, Long.MAX_VALUE};

    private final LongAdder readCount = new LongAdder();
    private final LongAdder readTotalMicros = new LongAdder();
    private final LongAccumulator readMaxMicros = new LongAccumulator(Long::max, 0L);
    private final LongAdder writeCount = new LongAdder();
    private final LongAdder writeTotalMicros = new LongAdder();
    private final LongAccumulator writeMaxMicros = new LongAccumulator(Long::max, 0L);
    private final LongAdder flushCount = new LongAdder();
    private final LongAdder flushTotalMicros = new LongAdder();
    private final LongAccumulator flushMaxMicros = new LongAccumulator(Long::max, 0L);
    private final LongAdder loadCount = new LongAdder();
    private final LongAdder loadTotalMicros = new LongAdder();
    private final LongAccumulator loadMaxMicros = new LongAccumulator(Long::max, 0L);
    private final LongAdder markDirtyCount = new LongAdder();
    private final LongAdder filesFlushedCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final LongAdder oversizeRejectCount = new LongAdder();
    private final LongAdder evictCount = new LongAdder();
    private final LongAdder cacheHitCount = new LongAdder();
    private final LongAdder cacheMissCount = new LongAdder();
    private final LongAdder rawBytesTotal = new LongAdder();
    private final LongAdder compressedBytesTotal = new LongAdder();
    private final LongAccumulator lastFlushSuccessNanos = new LongAccumulator(Long::max, 0L);
    private final LongAdder[] flushBucketsCur = newBucketSet();
    private final LongAdder[] flushBucketsPrev = newBucketSet();
    private final LongAdder flushWindowSamples = new LongAdder();
    private volatile long flushWindowStartNanos = System.nanoTime();
    private final Object flushWindowLock = new Object();

    private static LongAdder[] newBucketSet() {
        LongAdder[] set = new LongAdder[FLUSH_BUCKET_UPPER.length];
        for (int i = 0; i < set.length; i++) {
            set[i] = new LongAdder();
        }
        return set;
    }

    /**
     * Quantile over ascending {@code sortedBuckets} in {@code [0, 1]}.
     * Empty (or null) input yields {@code 0.0}; {@code q} is clamped.
     */
    public static double quantile(long[] sortedBuckets, double q) {
        if (sortedBuckets == null || sortedBuckets.length == 0) {
            return 0.0;
        }
        double clamped = q < 0.0 ? 0.0 : (q > 1.0 ? 1.0 : q);
        int n = sortedBuckets.length;
        int idx = (int) Math.ceil(clamped * n) - 1;
        if (idx < 0) {
            idx = 0;
        } else if (idx >= n) {
            idx = n - 1;
        }
        return (double) sortedBuckets[idx];
    }

    public void recordRead(long micros) {
        this.readCount.increment();
        this.readTotalMicros.add(micros);
        this.readMaxMicros.accumulate(micros);
    }

    public void recordWrite(long micros) {
        this.writeCount.increment();
        this.writeTotalMicros.add(micros);
        this.writeMaxMicros.accumulate(micros);
    }

    public void recordFlush(long micros) {
        recordFlush(micros, true);
    }

    /**
     * Success-only bucketed latency. Lifetime totals/max count every I/O
     * flush; the windowed p50/p99 buckets take successes only (failed flushes
     * retry via re-queue and must not poison p99).
     */
    public void recordFlush(long micros, boolean success) {
        this.flushCount.increment();
        this.flushTotalMicros.add(micros);
        this.flushMaxMicros.accumulate(micros);
        if (!success) {
            return;
        }
        maybeRotateFlushWindow();
        for (int i = 0; i < FLUSH_BUCKET_UPPER.length; i++) {
            if (micros <= FLUSH_BUCKET_UPPER[i]) {
                this.flushBucketsCur[i].increment();
                break;
            }
        }
        this.flushWindowSamples.increment();
    }

    private void maybeRotateFlushWindow() {
        if (this.flushWindowSamples.sum() < FLUSH_WINDOW_SAMPLES
                && System.nanoTime() - this.flushWindowStartNanos < FLUSH_WINDOW_NANOS) {
            return;
        }
        synchronized (this.flushWindowLock) {
            if (this.flushWindowSamples.sum() < FLUSH_WINDOW_SAMPLES
                    && System.nanoTime() - this.flushWindowStartNanos < FLUSH_WINDOW_NANOS) {
                return;
            }
            rotateFlushWindowLocked();
        }
    }

    private void rotateFlushWindowLocked() {
        for (int i = 0; i < FLUSH_BUCKET_UPPER.length; i++) {
            this.flushBucketsPrev[i].reset();
            this.flushBucketsPrev[i].add(this.flushBucketsCur[i].sumThenReset());
        }
        this.flushWindowSamples.reset();
        this.flushWindowStartNanos = System.nanoTime();
    }

    /** Test-only: forces a window rotation regardless of age/sample bounds. */
    public void forceRotateForTests() {
        synchronized (this.flushWindowLock) {
            rotateFlushWindowLocked();
        }
    }

    public void recordFlushBytes(long rawBytes, long compressedBytes) {
        if (rawBytes > 0L) {
            this.rawBytesTotal.add(rawBytes);
        }
        if (compressedBytes > 0L) {
            this.compressedBytesTotal.add(compressedBytes);
        }
    }

    public void recordLoad(long micros) {
        this.loadCount.increment();
        this.loadTotalMicros.add(micros);
        this.loadMaxMicros.accumulate(micros);
    }

    public void recordEvict() {
        this.evictCount.increment();
    }

    public void recordFailure() {
        this.failureCount.increment();
    }

    public void recordOversizeReject() {
        this.oversizeRejectCount.increment();
    }

    public void recordCacheHit() {
        this.cacheHitCount.increment();
    }

    public void recordCacheMiss() {
        this.cacheMissCount.increment();
    }

    public void recordMarkDirty() {
        this.markDirtyCount.increment();
    }

    public void recordFileFlushed(long atNanos) {
        this.filesFlushedCount.increment();
        this.lastFlushSuccessNanos.accumulate(atNanos);
    }

    /**
     * Folder snapshot. {@code dirtyDepth} is supplied by the caller (read
     * under the coordinator monitor); all counters read lock-free outside it.
     */
    public LinearRegionTimings.LinearFolderSnapshot snapshot(int dirtyDepth) {
        long[] cur = new long[FLUSH_BUCKET_UPPER.length];
        long curTotal = 0L;
        for (int i = 0; i < cur.length; i++) {
            cur[i] = this.flushBucketsCur[i].sum();
            curTotal += cur[i];
        }
        long[] buckets = cur;
        long totalFlush = curTotal;
        if (curTotal < FLUSH_WINDOW_MIN_SAMPLES) {
            long[] prev = new long[FLUSH_BUCKET_UPPER.length];
            long prevTotal = 0L;
            for (int i = 0; i < prev.length; i++) {
                prev[i] = this.flushBucketsPrev[i].sum();
                prevTotal += prev[i];
            }
            if (prevTotal > 0L) {
                buckets = prev;
                totalFlush = prevTotal;
            }
        }
        long p50 = bucketQuantile(buckets, totalFlush, 0.50);
        long p99 = bucketQuantile(buckets, totalFlush, 0.99);
        long lastNanos = this.lastFlushSuccessNanos.get();
        long millisSince = lastNanos <= 0L ? -1L : (System.nanoTime() - lastNanos) / 1_000_000L;
        if (millisSince < -1L) {
            millisSince = -1L;
        }
        return new LinearRegionTimings.LinearFolderSnapshot(
            LinearRegionTimings.LinearTimings.of(this.readCount, this.readTotalMicros, this.readMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.writeCount, this.writeTotalMicros, this.writeMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.flushCount, this.flushTotalMicros, this.flushMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.loadCount, this.loadTotalMicros, this.loadMaxMicros),
            this.markDirtyCount.sum(),
            this.filesFlushedCount.sum(),
            this.failureCount.sum(),
            this.oversizeRejectCount.sum(),
            this.evictCount.sum(),
            this.cacheHitCount.sum(),
            this.cacheMissCount.sum(),
            dirtyDepth,
            this.rawBytesTotal.sum(),
            this.compressedBytesTotal.sum(),
            p50,
            p99,
            millisSince);
    }

    private static long bucketQuantile(long[] buckets, long total, double q) {
        if (total <= 0L) {
            return 0L;
        }
        long need = (long) Math.ceil(total * q);
        if (need < 1L) {
            need = 1L;
        }
        long acc = 0L;
        for (int i = 0; i < buckets.length; i++) {
            acc += buckets[i];
            if (acc >= need) {
                long upper = FLUSH_BUCKET_UPPER[i];
                return upper == Long.MAX_VALUE ? FLUSH_TOP_SENTINEL_MICROS : upper;
            }
        }
        return 0L;
    }

    /** Explicit test-only reset (lifecycle/dirty set untouched). */
    public void reset() {
        this.readCount.reset();
        this.readTotalMicros.reset();
        this.readMaxMicros.reset();
        this.writeCount.reset();
        this.writeTotalMicros.reset();
        this.writeMaxMicros.reset();
        this.flushCount.reset();
        this.flushTotalMicros.reset();
        this.flushMaxMicros.reset();
        this.loadCount.reset();
        this.loadTotalMicros.reset();
        this.loadMaxMicros.reset();
        this.markDirtyCount.reset();
        this.filesFlushedCount.reset();
        this.failureCount.reset();
        this.oversizeRejectCount.reset();
        this.evictCount.reset();
        this.cacheHitCount.reset();
        this.cacheMissCount.reset();
        this.rawBytesTotal.reset();
        this.compressedBytesTotal.reset();
        for (LongAdder b : this.flushBucketsCur) {
            b.reset();
        }
        for (LongAdder b : this.flushBucketsPrev) {
            b.reset();
        }
        this.flushWindowSamples.reset();
        this.flushWindowStartNanos = System.nanoTime();
        this.lastFlushSuccessNanos.reset();
    }
}
