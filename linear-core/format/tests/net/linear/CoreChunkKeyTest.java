package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Packed chunk keys (vanilla-exact slot math). */
public class CoreChunkKeyTest {

    @Test
    public void roundTrip() {
        long k = ChunkKey.of(123, -456);
        assertEquals(123, ChunkKey.x(k));
        assertEquals(-456, ChunkKey.z(k));
        long origin = ChunkKey.of(0, 0);
        assertEquals(0, ChunkKey.x(origin));
        assertEquals(0, ChunkKey.z(origin));
    }

    @Test
    public void slotIndexMatchesVanillaFormula() {
        // slotIndex must equal (x & 31) + (z & 31) * 32 for all sign combinations.
        int[] samples = {0, 1, 31, 32, -1, -31, -32, 1000, -1000, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int x : samples) {
            for (int z : samples) {
                int want = (x & 31) + (z & 31) * 32;
                assertEquals("x=" + x + " z=" + z, want, ChunkKey.slotIndex(ChunkKey.of(x, z)));
                assertTrue(ChunkKey.slotIndex(ChunkKey.of(x, z)) >= 0);
                assertTrue(ChunkKey.slotIndex(ChunkKey.of(x, z)) < 1024);
            }
        }
    }

    @Test
    public void formatMatchesChunkPosToString() {
        assertEquals("[3, -7]", ChunkKey.format(ChunkKey.of(3, -7)));
    }
}
