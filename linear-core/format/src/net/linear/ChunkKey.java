package net.linear;

/**
 * Opaque packed chunk coordinate for the Linear core contract.
 *
 * <p>Replaces vanilla {@code ChunkPos} at every
 * core boundary so {@code common/linear-core} compiles without NMS.
 * Layout ({@code x} high, {@code z} low) is core-internal; adapters convert
 * at the boundary via {@link #of(int, int)}. Slot math matches vanilla
 * exactly: {@code getRegionLocalX() == x & 31}.
 */
public final class ChunkKey {

    private ChunkKey() {
    }

    /** Packs {@code (x, z)} into one opaque key. */
    public static long of(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Unpacks the {@code x} coordinate. */
    public static int x(long key) {
        return (int) (key >> 32);
    }

    /** Unpacks the {@code z} coordinate. */
    public static int z(long key) {
        return (int) key;
    }

    /** Region slot index: {@code (x & 31) + (z & 31) * 32}. */
    public static int slotIndex(long key) {
        return (x(key) & 31) + (z(key) & 31) * 32;
    }

    /** Log form matching vanilla {@code ChunkPos.toString}: {@code [x, z]}. */
    public static String format(long key) {
        return "[" + x(key) + ", " + z(key) + "]";
    }
}
