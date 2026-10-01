package net.linear;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import org.junit.Test;

/** Lock-free timing snapshot math. */
public class CoreTimingsTest {

    @Test
    public void emptySnapshotIsZero() {
        assertEquals(0L, LinearRegionTimings.LinearTimings.EMPTY.count());
        assertEquals(0L, LinearRegionTimings.LinearTimings.EMPTY.avgMicros());
        assertEquals(0L, LinearRegionTimings.LinearRegionStats.EMPTY.read().count());
        assertEquals(0L, LinearRegionTimings.LinearFolderSnapshot.EMPTY.rawBytes());
        assertEquals(0L, LinearRegionTimings.LinearFolderSnapshot.EMPTY.filesFlushed());
        assertEquals(-1L, LinearRegionTimings.LinearFolderSnapshot.EMPTY.millisSinceLastFlush());
    }

    @Test
    public void ofComputesCountTotalMaxAvg() {
        LongAdder count = new LongAdder();
        LongAdder total = new LongAdder();
        LongAccumulator max = new LongAccumulator(Long::max, 0L);
        count.add(4);
        total.add(100);
        max.accumulate(40);
        LinearRegionTimings.LinearTimings t = LinearRegionTimings.LinearTimings.of(count, total, max);
        assertEquals(4L, t.count());
        assertEquals(100L, t.totalMicros());
        assertEquals(40L, t.maxMicros());
        assertEquals(25L, t.avgMicros());
    }

    @Test
    public void avgOfEmptyIsZeroNotNaN() {
        LinearRegionTimings.LinearTimings t = LinearRegionTimings.LinearTimings.of(
                new LongAdder(), new LongAdder(), new LongAccumulator(Long::max, 0L));
        assertEquals(0L, t.avgMicros());
    }
}
