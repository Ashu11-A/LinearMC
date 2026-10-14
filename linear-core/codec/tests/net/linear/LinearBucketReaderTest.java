package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * V2/V3 bucketed read (DivineMC wire 3/4) into V1 slot tables.
 * Fixtures are written here with the same layout DivineMC uses
 * (per-bucket zstd + xxhash64), then read via {@link LinearRegionFile}.
 */
public class LinearBucketReaderTest {

    @Test
    public void xxhashKnownVectors() {
        // Verified against OpenHFT LongHashFunction.xx() (seed 0), 7 vectors.
        assertEquals(0xEF46DB3751D8E999L, LinearXxHash64.hashBytes(new byte[0], 0, 0));
        assertHash("a", 0xD24EC4F1A98C6E5BL);
        assertHash("abc", 0x44BC2CF5AD770999L);
        assertHash("linear-bucket-hash", 0xF51E566DB577EB51L);
        assertHash("0123456789abcdef0123456789abcdef", 0x642A94958E71E6C5L);
        byte[] big = new byte[1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31 + 7);
        }
        assertEquals(0x149AA44972CDAE00L, LinearXxHash64.hashBytes(big, 0, big.length));
    }

    private static void assertHash(String text, long expected) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        assertEquals(expected, LinearXxHash64.hashBytes(data, 0, data.length));
        assertEquals(expected, LinearXxHash64.hashBuffer(ByteBuffer.wrap(data)));
    }

    @Test
    public void gridValidation() {
        for (int g : new int[] {1, 2, 4, 8, 16, 32}) {
            assertEquals(true, LinearBucketReader.isValidGrid(g));
        }
        for (int g : new int[] {0, 3, 5, 6, 7, 9, 31, 33, -1}) {
            assertEquals(false, LinearBucketReader.isValidGrid(g));
        }
    }

    @Test
    public void implementationMapping() {
        assertEquals(LinearImplementation.V1, LinearImplementation.forWireVersion(1));
        assertEquals(LinearImplementation.V1, LinearImplementation.forWireVersion(2));
        assertEquals(LinearImplementation.V2, LinearImplementation.forWireVersion(3));
        assertEquals(LinearImplementation.V3, LinearImplementation.forWireVersion(4));
        assertEquals(null, LinearImplementation.forWireVersion(0));
        assertEquals(null, LinearImplementation.forWireVersion(5));
        assertEquals(LinearImplementation.V2, LinearImplementation.fromString("v2"));
        assertEquals(LinearImplementation.V3, LinearImplementation.fromString(" V3 "));
        assertEquals(null, LinearImplementation.fromString("V9"));
    }

    @Test
    public void v2RoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("linear-v2");
        Path file = dir.resolve("r.0.0.linear");
        byte[] payload = "v2-chunk-payload".repeat(16).getBytes(StandardCharsets.UTF_8);
        writeV2(file, 8, ChunkKey.of(1, 2), payload, 1_700_000_000L);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            DataInputStream in = region.getChunkDataInputStream(ChunkKey.of(1, 2));
            assertNotNull(in);
            byte[] back = in.readAllBytes();
            in.close();
            assertArrayEquals(payload, back);
            assertNull(region.getChunkDataInputStream(ChunkKey.of(0, 0)));
        }
    }

    @Test
    public void v3RoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("linear-v3");
        Path file = dir.resolve("r.0.0.linear");
        byte[] payload = "v3-chunk-payload".repeat(16).getBytes(StandardCharsets.UTF_8);
        writeV3(file, 8, ChunkKey.of(30, 31), payload, 1_700_000_001L);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            DataInputStream in = region.getChunkDataInputStream(ChunkKey.of(30, 31));
            assertNotNull(in);
            byte[] back = in.readAllBytes();
            in.close();
            assertArrayEquals(payload, back);
        }
    }

    @Test
    public void gridVariantsRoundTrip() throws Exception {
        for (int grid : new int[] {1, 2, 4, 16, 32}) {
            Path dir = Files.createTempDirectory("linear-grid" + grid);
            Path file = dir.resolve("r.0.0.linear");
            byte[] payload = ("grid" + grid).repeat(32).getBytes(StandardCharsets.UTF_8);
            if (grid % 2 == 0 && grid < 32) {
                writeV2(file, grid, ChunkKey.of(5, 7), payload, 1_700_000_010L);
            } else {
                writeV3(file, grid, ChunkKey.of(5, 7), payload, 1_700_000_011L);
            }
            try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
                DataInputStream in = region.getChunkDataInputStream(ChunkKey.of(5, 7));
                assertNotNull("grid " + grid, in);
                assertArrayEquals("grid " + grid, payload, in.readAllBytes());
                in.close();
            }
        }
    }

    @Test
    public void multiChunkAcrossBuckets() throws Exception {
        Path dir = Files.createTempDirectory("linear-multi");
        Path file = dir.resolve("r.0.0.linear");
        byte[] a = "chunk-a".repeat(32).getBytes(StandardCharsets.UTF_8);
        byte[] b = "chunk-b".repeat(32).getBytes(StandardCharsets.UTF_8);
        writeV2Multi(file, 8, new long[] {ChunkKey.of(0, 0), ChunkKey.of(31, 31)},
            new byte[][] {a, b}, 1_700_000_020L);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            assertArrayEquals(a, readAll(region, ChunkKey.of(0, 0)));
            assertArrayEquals(b, readAll(region, ChunkKey.of(31, 31)));
            assertNull(region.getChunkDataInputStream(ChunkKey.of(16, 16)));
        }
    }

    @Test
    public void timestampsPreservedThroughRewrite() throws Exception {
        Path dir = Files.createTempDirectory("linear-ts");
        Path file = dir.resolve("r.0.0.linear");
        byte[] payload = "ts-payload".repeat(16).getBytes(StandardCharsets.UTF_8);
        writeV2(file, 8, ChunkKey.of(9, 9), payload, 1_700_000_030L);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            // Clean load stays V2 on disk; a write forces the V1 rewrite.
            region.write(ChunkKey.of(10, 10), ByteBuffer.wrap("n".repeat(16).getBytes(StandardCharsets.UTF_8)));
            region.flush();
        }
        // Rewritten as V1: payload intact and table timestamp preserved.
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            assertArrayEquals(payload, readAll(region, ChunkKey.of(9, 9)));
        }
        byte[] raw = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        buf.getLong();
        assertEquals(2, buf.get() & 0xFF);
    }

    private static byte[] readAll(LinearRegionFile region, long chunk) throws Exception {
        DataInputStream in = region.getChunkDataInputStream(chunk);
        assertNotNull(in);
        byte[] back = in.readAllBytes();
        in.close();
        return back;
    }

    /**
     * Golden interop: files written by DivineMC's own writer
     * ({@code V2Linear.flush}/{@code V3Linear.flush}, level 3, grid 8, zstd +
     * xxhash64) must read back with payloads in the right slots. This breaks
     * any bucket-order/header self-consistency that self-fixtures would hide.
     */
    @Test
    public void goldenDivineV2() throws Exception {
        Path dir = Files.createTempDirectory("linear-golden-v2");
        Path file = dir.resolve("r.0.0.linear");
        Files.write(file, java.util.Base64.getDecoder().decode(GOLDEN_V2));
        byte[] p1 = "golden-divine-v2-chunk".repeat(16).getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "golden-divine-v2-other".repeat(16).getBytes(StandardCharsets.UTF_8);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            assertArrayEquals(p1, readAll(region, ChunkKey.of(1, 2)));
            assertArrayEquals(p2, readAll(region, ChunkKey.of(30, 31)));
            assertNull(region.getChunkDataInputStream(ChunkKey.of(0, 0)));
        }
    }

    @Test
    public void goldenDivineV3() throws Exception {
        Path dir = Files.createTempDirectory("linear-golden-v3");
        Path file = dir.resolve("r.1.0.linear");
        Files.write(file, java.util.Base64.getDecoder().decode(GOLDEN_V3));
        byte[] p1 = "golden-divine-v2-chunk".repeat(16).getBytes(StandardCharsets.UTF_8);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            assertArrayEquals(p1, readAll(region, ChunkKey.of(5, 7)));
            assertNull(region.getChunkDataInputStream(ChunkKey.of(0, 0)));
        }
    }

    /**
     * R5: the DivineMC golden's existence bitmap is MSB-first
     * ({@code bit j of byte i = (b >> (7-j)) & 1}). Slots (1,2)->index 65
     * (byte 8 = 0x40) and (30,31)->index 1022 (byte 127 = 0x02); every other
     * byte is zero since only those two chunks are present.
     */
    @Test
    public void goldenDivineV2BitmapIsMsbFirst() throws Exception {
        byte[] raw = java.util.Base64.getDecoder().decode(GOLDEN_V2);
        byte[] bitmap = java.util.Arrays.copyOfRange(raw, 26, 26 + 128);
        byte[] expected = new byte[128];
        expected[8] = 0x40;
        expected[127] = 0x02;
        assertArrayEquals(expected, bitmap);
    }

    /** DivineMC V2 writer output: grid 8, chunks [1,2]+[30,31], level 3. */
    private static final String GOLDEN_V2 =
        "w/8TGDzKnZoDAAAAAGq/AvsIAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAA+Aze/aHYM393CAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAA9A5EKmFXyVtgIKLUv/QBYlAEAJAIAAAFoAAAAAGVT8QBnb2xkZW4tZGl2aW5lLXYyLWNodW5rBADgAaJdKMBRTvUVCrABAAAotS/9AFiMAQAkAgAAAWgAAAAAZVPxAWdvbGRlbi1kaXZpbmUtdjItb3RoZXIDAMGvCOAop/oCFMACAQAAw/8TGDzKnZo=";

    /** DivineMC V3 writer output: grid 8, chunk [5,7], level 3. */
    private static final String GOLDEN_V3 =
        "w/8TGDzKnZoECAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD7CWrl7UmbvxAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACi1L/0AWJQBACQCAAABaAAAAABlU/ECZ29sZGVuLWRpdmluZS12Mi1jaHVuawQAcAAArwngKKf6YgJYAQAAw/8TGDzKnZo=";

    @Test
    public void v2FlushRewritesAsV1() throws Exception {
        Path dir = Files.createTempDirectory("linear-v2rw");
        Path file = dir.resolve("r.0.0.linear");
        byte[] payload = "rewrite-me".repeat(8).getBytes(StandardCharsets.UTF_8);
        writeV2(file, 8, ChunkKey.of(3, 3), payload, 1_700_000_002L);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(4, 4), ByteBuffer.wrap("extra".getBytes(StandardCharsets.UTF_8)));
            region.flush();
        }
        byte[] raw = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        buf.getLong();
        assertEquals(2, buf.get() & 0xFF);
    }

    @Test
    public void hashMismatchRejected() throws Exception {
        Path dir = Files.createTempDirectory("linear-badhash");
        Path file = dir.resolve("r.0.0.linear");
        writeV2(file, 8, ChunkKey.of(1, 1), "x".repeat(64).getBytes(StandardCharsets.UTF_8), 42L);
        byte[] raw = Files.readAllBytes(file);
        // Corrupt one byte inside bucket data (before the 8-byte footer,
        // which stays intact so only the xxhash check can catch this).
        raw[raw.length - 16] ^= 0xFF;
        Files.write(file, raw);
        try {
            new LinearRegionFile(file, 6);
            fail("expected IOException for bad bucket hash");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("hash"));
        }
    }

    @Test
    public void invalidGridRejected() throws Exception {
        Path dir = Files.createTempDirectory("linear-badgrid");
        Path file = dir.resolve("r.0.0.linear");
        writeV3(file, 8, ChunkKey.of(0, 1), "y".repeat(32).getBytes(StandardCharsets.UTF_8), 43L);
        byte[] raw = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        buf.getLong();
        buf.get();
        buf.put(9, (byte) 7); // gridSize 7 is invalid
        Files.write(file, raw);
        try {
            new LinearRegionFile(file, 6);
            fail("expected IOException for bad grid");
        } catch (IOException expected) {
        }
    }

    // Test-only minimal V2/V3 writers mirroring DivineMC layout.
    private static void writeV2(Path file, int grid, long chunk, byte[] payload, long ts) throws IOException {
        int cell = 32 / grid;
        int bucketCount = grid * grid;
        int cx = ChunkKey.x(chunk);
        int cz = ChunkKey.z(chunk);
        int bx = (cx & 31) / cell;
        int bz = (cz & 31) / cell;
        int target = bx * grid + bz;
        byte[][] buckets = new byte[bucketCount][];
        for (int b = 0; b < bucketCount; b++) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean hasData = false;
            try (ZstdOutputStream zstd = new ZstdOutputStream(baos, 3)) {
                DataOutputStream out = new DataOutputStream(zstd);
                for (int ix = 0; ix < cell; ix++) {
                    for (int iz = 0; iz < cell; iz++) {
                        int slot = (bx * cell + ix) + (bz * cell + iz) * 32;
                        boolean isTarget = (b == target)
                            && (slot == ChunkKey.slotIndex(chunk));
                        if (isTarget) {
                            hasData = true;
                            out.writeInt(payload.length + 8);
                            out.writeLong(ts);
                            out.write(payload);
                        } else {
                            out.writeInt(0);
                            out.writeLong(0L);
                        }
                    }
                }
                out.flush();
            }
            if (b == target || hasData) {
                buckets[b] = baos.toByteArray();
            }
            if (b != target) {
                buckets[b] = null;
            }
        }
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeLong(0xC3FF13183CCA9D9AL);
            out.writeByte(3);
            out.writeLong(ts);
            out.writeByte(grid);
            out.writeInt(0);
            out.writeInt(0);
            for (int i = 0; i < 128; i++) {
                out.writeByte(0);
            }
            out.writeByte(0); // NBT features terminator
            for (int b = 0; b < bucketCount; b++) {
                out.writeInt(buckets[b] != null ? buckets[b].length : 0);
                out.writeByte(3);
                out.writeLong(buckets[b] != null
                    ? LinearXxHash64.hashBytes(buckets[b], 0, buckets[b].length) : 0L);
            }
            for (int b = 0; b < bucketCount; b++) {
                if (buckets[b] != null) {
                    out.write(buckets[b]);
                }
            }
            out.writeLong(0xC3FF13183CCA9D9AL);
        }
    }

    private static void writeV2Multi(Path file, int grid, long[] chunks, byte[][] payloads, long ts)
            throws IOException {
        int cell = 32 / grid;
        int bucketCount = grid * grid;
        java.util.Map<Integer, byte[]> bySlot = new java.util.HashMap<>();
        for (int k = 0; k < chunks.length; k++) {
            bySlot.put(ChunkKey.slotIndex(chunks[k]), payloads[k]);
        }
        byte[][] buckets = new byte[bucketCount][];
        for (int b = 0; b < bucketCount; b++) {
            int bx = b / grid;
            int bz = b % grid;
            boolean hasData = false;
            for (int ix = 0; ix < cell; ix++) {
                for (int iz = 0; iz < cell; iz++) {
                    int slot = (bx * cell + ix) + (bz * cell + iz) * 32;
                    if (bySlot.containsKey(slot)) {
                        hasData = true;
                    }
                }
            }
            if (!hasData) {
                continue;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZstdOutputStream zstd = new ZstdOutputStream(baos, 3)) {
                DataOutputStream out = new DataOutputStream(zstd);
                for (int ix = 0; ix < cell; ix++) {
                    for (int iz = 0; iz < cell; iz++) {
                        int slot = (bx * cell + ix) + (bz * cell + iz) * 32;
                        byte[] p = bySlot.get(slot);
                        if (p != null) {
                            out.writeInt(p.length + 8);
                            out.writeLong(ts);
                            out.write(p);
                        } else {
                            out.writeInt(0);
                            out.writeLong(0L);
                        }
                    }
                }
                out.flush();
            }
            buckets[b] = baos.toByteArray();
        }
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeLong(0xC3FF13183CCA9D9AL);
            out.writeByte(3);
            out.writeLong(ts);
            out.writeByte(grid);
            out.writeInt(0);
            out.writeInt(0);
            for (int i = 0; i < 128; i++) {
                out.writeByte(0);
            }
            out.writeByte(0);
            for (int b = 0; b < bucketCount; b++) {
                out.writeInt(buckets[b] != null ? buckets[b].length : 0);
                out.writeByte(3);
                out.writeLong(buckets[b] != null
                    ? LinearXxHash64.hashBytes(buckets[b], 0, buckets[b].length) : 0L);
            }
            for (int b = 0; b < bucketCount; b++) {
                if (buckets[b] != null) {
                    out.write(buckets[b]);
                }
            }
            out.writeLong(0xC3FF13183CCA9D9AL);
        }
    }

    private static void writeV3(Path file, int grid, long chunk, byte[] payload, long ts) throws IOException {
        int cell = 32 / grid;
        int bucketCount = grid * grid;
        int cx = ChunkKey.x(chunk);
        int cz = ChunkKey.z(chunk);
        int bx = (cx & 31) / cell;
        int bz = (cz & 31) / cell;
        int target = bx * grid + bz;
        byte[][] buckets = new byte[bucketCount][];
        for (int b = 0; b < bucketCount; b++) {
            if (b != target) {
                continue;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZstdOutputStream zstd = new ZstdOutputStream(baos, 3)) {
                DataOutputStream out = new DataOutputStream(zstd);
                for (int ix = 0; ix < cell; ix++) {
                    for (int iz = 0; iz < cell; iz++) {
                        int slot = (bx * cell + ix) + (bz * cell + iz) * 32;
                        if (slot == ChunkKey.slotIndex(chunk)) {
                            out.writeInt(payload.length + 8);
                            out.writeLong(ts);
                            out.write(payload);
                        } else {
                            out.writeInt(0);
                            out.writeLong(0L);
                        }
                    }
                }
                out.flush();
            }
            buckets[b] = baos.toByteArray();
        }
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeLong(0xC3FF13183CCA9D9AL);
            out.writeByte(4);
            out.writeByte(grid);
            for (int b = 0; b < bucketCount; b++) {
                out.writeInt(buckets[b] != null ? buckets[b].length : 0);
                out.writeLong(buckets[b] != null
                    ? LinearXxHash64.hashBytes(buckets[b], 0, buckets[b].length) : 0L);
            }
            for (int b = 0; b < bucketCount; b++) {
                if (buckets[b] != null) {
                    out.write(buckets[b]);
                }
            }
            out.writeLong(0xC3FF13183CCA9D9AL);
        }
    }
}
