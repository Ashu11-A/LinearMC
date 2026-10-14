package net.linear.probe;

/**
 * Bucket-grid math shared by the V2/V3 reader and writer (DivineMC-compatible).
 *
 * <p>A region is a 32x32 slot square divided into {@code gridSize x gridSize}
 * buckets, each holding {@code cellCount x cellCount} chunks where
 * {@code cellCount = 32 / gridSize}. Bucket order is row-major:
 * {@code bucketIdx = bx * gridSize + bz}. Pure JDK, NMS-free.</p>
 */
public final class LinearBucketGrid {

    private LinearBucketGrid() {
    }

    /** DivineMC grid whitelist. */
    public static boolean isValid(int grid) {
        return grid == 1 || grid == 2 || grid == 4 || grid == 8 || grid == 16 || grid == 32;
    }

    /** Cells per bucket edge ({@code 32 / gridSize}); callers must validate first. */
    public static int cellCount(int gridSize) {
        return 32 / gridSize;
    }

    /** Bucket count ({@code gridSize * gridSize}). */
    public static int bucketCount(int gridSize) {
        return gridSize * gridSize;
    }

    /** Bucket column for a region-local coordinate. */
    public static int bucketX(int localX, int cellCount) {
        return localX / cellCount;
    }

    /** Bucket row for a region-local coordinate. */
    public static int bucketZ(int localZ, int cellCount) {
        return localZ / cellCount;
    }

    /** Row-major bucket index. */
    public static int bucketIndex(int bx, int bz, int gridSize) {
        return bx * gridSize + bz;
    }

    /**
     * Slot index for cell {@code (cx, cz)} inside bucket {@code (bx, bz)}.
     * Matches the vanilla slot layout {@code (x & 31) + (z & 31) * 32}.
     */
    public static int slotIndex(int bx, int bz, int cx, int cz, int cellCount) {
        return (bx * cellCount + cx) + (bz * cellCount + cz) * 32;
    }
}
