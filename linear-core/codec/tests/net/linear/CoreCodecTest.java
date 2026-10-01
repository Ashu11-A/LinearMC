package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Direct-only zstd codec contract (avoids per-chunk heap allocation on hot paths). */
public class CoreCodecTest {

    private static ByteBuffer direct(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.allocateDirect(bytes.length == 0 ? 1 : bytes.length);
        buf.put(bytes);
        buf.flip();
        return buf;
    }

    @Test
    public void roundTrip() throws Exception {
        byte[] payload = ("linear chunk payload ".repeat(64)).getBytes(StandardCharsets.UTF_8);
        ByteBuffer src = direct(payload);
        int srcPos = src.position();
        ByteBuffer compressed = ByteBuffer.allocateDirect((int) ZstdChunkCodec.compressBound(payload.length));
        int n = ZstdChunkCodec.compressDirect(src, compressed, 6);
        assertTrue(n > 0);
        assertEquals(srcPos, src.position());
        compressed.flip();
        ByteBuffer out = ByteBuffer.allocateDirect(payload.length);
        int m = ZstdChunkCodec.decompressDirect(compressed, out);
        assertEquals(payload.length, m);
        out.flip();
        byte[] back = new byte[payload.length];
        out.get(back);
        assertArrayEquals(payload, back);
    }

    @Test
    public void heapBuffersRejected() {
        ByteBuffer heap = ByteBuffer.allocate(8);
        ByteBuffer direct = ByteBuffer.allocateDirect(64);
        try {
            ZstdChunkCodec.requireDirect(heap, "heap");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
        ZstdChunkCodec.requireDirect(direct, "direct");
        try {
            ZstdChunkCodec.requireDirect(null, "null");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void levelGuard() {
        assertEquals(6, ZstdChunkCodec.checkLevel(6));
        assertEquals(1, ZstdChunkCodec.checkLevel(1));
        assertEquals(22, ZstdChunkCodec.checkLevel(22));
        for (int bad : new int[] {0, -1, 23, 100}) {
            try {
                ZstdChunkCodec.checkLevel(bad);
                fail("expected IllegalArgumentException for " + bad);
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void hotAndFlushShorthands() throws Exception {
        byte[] payload = ("hot path ".repeat(32)).getBytes(StandardCharsets.UTF_8);
        for (boolean hot : new boolean[] {true, false}) {
            ByteBuffer src = direct(payload);
            ByteBuffer compressed = ByteBuffer.allocateDirect((int) ZstdChunkCodec.compressBound(payload.length));
            int n = hot ? ZstdChunkCodec.compressHot(src, compressed)
                    : ZstdChunkCodec.compressFlush(src, compressed);
            assertTrue(n > 0);
        }
    }

    @Test
    public void nativeWarmupRoundTrips() {
        assertTrue(ZstdChunkCodec.warmup());
    }
}
