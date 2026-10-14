package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * V2/V3 bucketed write (DivineMC wire 3/4) via {@link LinearRegionFile}.
 * Written files must read back through the same reader (and carry the
 * right wire bytes, grid, footer magic for DivineMC interop).
 */
public class LinearBucketWriterTest {

    private static byte[] payload(String seed, int reps) {
        return seed.repeat(reps).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void v2WriteReadGrid8() throws Exception {
        Path dir = Files.createTempDirectory("linear-wv2");
        Path file = dir.resolve("r.2.3.linear");
        byte[] a = payload("w2-a", 32);
        byte[] b = payload("w2-b", 32);
        try (LinearRegionFile region = new LinearRegionFile(file, 6, LinearImplementation.V2, 8)) {
            assertEquals(LinearImplementation.V2, region.linear$writeImplementation());
            assertEquals(8, region.linear$gridSize());
            region.write(ChunkKey.of(2 * 32 + 1, 3 * 32 + 2), ByteBuffer.wrap(a));
            region.write(ChunkKey.of(2 * 32 + 30, 3 * 32 + 0), ByteBuffer.wrap(b));
            region.flush();
        }
        assertWire(file, 3, 8);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(2 * 32 + 1, 3 * 32 + 2)));
            assertArrayEquals(b, readAll(region, ChunkKey.of(2 * 32 + 30, 3 * 32 + 0)));
            assertNull(region.getChunkDataInputStream(ChunkKey.of(2 * 32 + 5, 3 * 32 + 5)));
        }
    }

    @Test
    public void allGridsV2V3() throws Exception {
        for (int grid : new int[] {1, 2, 4, 8, 16, 32}) {
            for (LinearImplementation impl : new LinearImplementation[] {LinearImplementation.V2, LinearImplementation.V3}) {
                Path dir = Files.createTempDirectory("linear-wg" + grid + impl.name());
                Path file = dir.resolve("r.0.0.linear");
                byte[] p = payload("g" + grid + impl.name(), 24);
                long chunk = ChunkKey.of(7, 9);
                try (LinearRegionFile region = new LinearRegionFile(file, 3, impl, grid)) {
                    region.write(chunk, ByteBuffer.wrap(p));
                    region.flush();
                }
                assertWire(file, impl.version() & 0xFF, grid);
                try (LinearRegionFile region = new LinearRegionFile(file, 3)) {
                    assertArrayEquals("grid " + grid + " " + impl, p, readAll(region, chunk));
                }
            }
        }
    }

    @Test
    public void v1DefaultUnchanged() throws Exception {
        Path dir = Files.createTempDirectory("linear-wv1");
        Path file = dir.resolve("r.0.0.linear");
        try (LinearRegionFile region = new LinearRegionFile(file, 6, null, 99)) {
            assertEquals(LinearImplementation.V1, region.linear$writeImplementation());
            assertEquals(8, region.linear$gridSize());
            region.write(ChunkKey.of(0, 0), ByteBuffer.wrap(payload("v1", 8)));
            region.flush();
        }
        assertWire(file, 2, -1);
    }

    @Test
    public void emptyBucketedFlushLeavesNoFile() throws Exception {
        Path dir = Files.createTempDirectory("linear-wempty");
        Path file = dir.resolve("r.0.0.linear");
        try (LinearRegionFile region = new LinearRegionFile(file, 6, LinearImplementation.V3, 8)) {
            region.flush();
        }
        assertEquals(false, Files.isRegularFile(file));
    }

    private static void assertWire(Path file, int version, int grid) throws Exception {
        byte[] raw = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        assertEquals(0xC3FF13183CCA9D9AL, buf.getLong());
        assertEquals(version, buf.get() & 0xFF);
        if (grid >= 0) {
            if (version == 3) {
                buf.getLong();
                assertEquals(grid, buf.get() & 0xFF);
            } else {
                assertEquals(grid, buf.get() & 0xFF);
            }
        }
        ByteBuffer foot = ByteBuffer.wrap(raw, raw.length - 8, 8);
        assertEquals(0xC3FF13183CCA9D9AL, foot.getLong());
    }

    private static byte[] readAll(LinearRegionFile region, long chunk) throws Exception {
        DataInputStream in = region.getChunkDataInputStream(chunk);
        assertNotNull(in);
        byte[] back = in.readAllBytes();
        in.close();
        return back;
    }
}
