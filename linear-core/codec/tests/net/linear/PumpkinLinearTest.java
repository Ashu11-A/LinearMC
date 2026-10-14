package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.jpountz.lz4.LZ4Factory;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.PumpkinLinear;
import net.linear.versions.RegionFormatDetector;
import net.linear.versions.V2Linear;
import net.linear.versions.VersionWriteParams;
import org.junit.Test;

/**
 * Pumpkin (wire 2, bucketed) codec tests against hand-built fixtures following
 * section 1.4 of {@code docs/linear-format-requirements.md}.
 *
 * <p>Fixtures are built here byte-by-byte in the upstream Pumpkin shape (26 B
 * superblock, LSB-first bitmap, features terminator, 13 B entries with level 1,
 * every bucket compressed, footer), so the reader is proven against the real
 * layout rather than its own writer's habits.
 */
public class PumpkinLinearTest {

    static final long MAGIC = 0xC3FF13183CCA9D9AL;

    /** Real Pumpkin server writer output: grid 2, region 0,0, 5 chunks, 570 B. */
    private static final String GOLDEN_PUMPKIN_REAL =
        "w/8TGDzKnZoCAAAAAGVT9MMCAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAABaAfgMjDBm/vQ4AAAAgQFfR6+6NhxQvwAAAH4Bh6BCdnwvqa0AAAAKARAP5Xh160VcKLUv/QA4jQIAzAEAAAAAGwAAAABlU/EAUFVNUEtJTi1TMC0AAAAAAAqoEOP/+xCMKkk6EFIxEq/E/0icRCJ+AQaUhn4GkvUZtJmeISJ0Rm3pyVLX3TVqoNARKLUv/QA4xQMArAIAAAAAAABbAAAAAGVT8X5QVU1QS0lOLVMxMjYtfn5+fn5RtDgwLbS0tLS0FqgQXVr2NxCMRBK3ARAiARG9eCmP8kg5BU0HKwKVAp3lEuhW2W1tTs0AxuzNZlj5UtleVMceMBhEg/r1DH3aGcWmJ0WvhUMPAHA7KLUv/QA4rQMA3AIAAAAAAAAiAAAAAGVT84VQVU1QS0lOLVM2NDUthYWFhYWFIPTDOTYzLcPDw8PDEqgQW2XazxCMIiIyPRAqIhoTr8T/SJxEewNN9UpKAJVsPKVx07sePU9WV/BsSzb6wHmDtOkZYndGVevJ99cxzusAALcDKLUv/QA4A2AAAMP/Exg8yp2a";

    @Test
    public void handBuiltFixtureParses() throws Exception {
        Path dir = Files.createTempDirectory("pumpkin-hand");
        Path file = dir.resolve("r.5.7.linear");
        byte[] payload = "pumpkin-handbuilt-chunk".repeat(16).getBytes(StandardCharsets.UTF_8);
        int slot = ChunkKey.slotIndex(ChunkKey.of(3, 5));
        writePumpkinShape(file, 2, 5, 7, Map.of(slot, payload), Map.of(slot, 1_700_000_100L), 0);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, region.linear$detectedFormat());
            assertEquals(2, region.linear$detectedGrid());
            assertArrayEquals(payload, readAll(region, ChunkKey.of(3, 5)));
            assertEquals(null, region.getChunkDataInputStream(ChunkKey.of(0, 0)));
        }
    }

    @Test
    public void selfRoundTrip() throws Exception {
        int slot = ChunkKey.slotIndex(ChunkKey.of(10, 20));
        byte[] payload = "pumpkin-roundtrip".repeat(32).getBytes(StandardCharsets.UTF_8);
        ChunkSlotTable table = new ChunkSlotTable();
        table.sourceName = "selfRoundTrip";
        table.uncompressedSize[slot] = payload.length;
        table.timestamps[slot] = 1_700_000_101;
        table.lz4Payloads[slot] = ByteBuffer.wrap(lz4Pack(payload)).asReadOnlyBuffer();
        // Level 22 in params must still land as level byte 1 with fixed zstd level 1.
        ByteBuffer image = PumpkinLinear.shared().flush(
            table, new VersionWriteParams(22, 2, 5, 7, 1_700_000_101L, 0, 0));
        assertEquals(2, image.get(8) & 0xFF);
        assertEquals(1_700_000_101L, image.getLong(9));
        for (int b = 0; b < 4; b++) {
            assertEquals("table level byte " + b, 1, image.get(155 + b * 13 + 4));
        }
        ChunkSlotTable back = new ChunkSlotTable();
        back.sourceName = "selfRoundTrip";
        PumpkinLinear.shared().parse(image, back);
        assertEquals(payload.length, back.uncompressedSize[slot]);
        assertEquals(1_700_000_101, back.timestamps[slot]);
        assertArrayEquals(payload, lz4Unpack(back.lz4Payloads[slot], back.uncompressedSize[slot]));
    }

    @Test
    public void invalidGridDefaultsToTwo() throws Exception {
        ChunkSlotTable table = new ChunkSlotTable();
        table.sourceName = "invalidGridDefaultsToTwo";
        ByteBuffer image = PumpkinLinear.shared().flush(
            table, new VersionWriteParams(6, 7, 5, 7, 0L, 0, 0));
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN,
            RegionFormatDetector.shared().detect(image, 5, 7));
        assertEquals(2, RegionFormatDetector.shared().gridOf(image,
            RegionFormatDetector.DetectedFormat.PUMPKIN));
    }

    @Test
    public void bitmapIsLsbFirst() throws Exception {
        // Only slot 1 present: LSB-first stores 0x02, MSB-first would store 0x40.
        int slot = 1;
        byte[] payload = "bitmap-probe".repeat(8).getBytes(StandardCharsets.UTF_8);
        ChunkSlotTable table = new ChunkSlotTable();
        table.sourceName = "bitmapIsLsbFirst";
        table.uncompressedSize[slot] = payload.length;
        table.timestamps[slot] = 99;
        table.lz4Payloads[slot] = ByteBuffer.wrap(lz4Pack(payload)).asReadOnlyBuffer();
        ByteBuffer image = PumpkinLinear.shared().flush(
            table, new VersionWriteParams(6, 2, 0, 0, 99L, 0, 0));
        assertEquals(0x02, image.get(26) & 0xFF);
    }

    @Test
    public void bitmapIsInformationalOnRead() throws Exception {
        int slot = ChunkKey.slotIndex(ChunkKey.of(3, 5));
        byte[] payload = "bitmap-ignored".repeat(8).getBytes(StandardCharsets.UTF_8);
        Path dir = Files.createTempDirectory("pumpkin-nobitmap");
        Path file = dir.resolve("r.5.7.linear");
        writePumpkinShape(file, 2, 5, 7, Map.of(slot, payload), Map.of(slot, 5L), 0);
        byte[] raw = Files.readAllBytes(file);
        Arrays.fill(raw, 26, 26 + 128, (byte) 0); // wipe the bitmap entirely
        ChunkSlotTable out = new ChunkSlotTable();
        out.sourceName = "bitmapIsInformationalOnRead";
        PumpkinLinear.shared().parse(ByteBuffer.wrap(raw), out);
        assertEquals(payload.length, out.uncompressedSize[slot]);
    }

    @Test
    public void slotLengthIsRawLen() throws Exception {
        int slot = ChunkKey.slotIndex(ChunkKey.of(3, 5));
        byte[] payload = "raw-len-semantics".repeat(16).getBytes(StandardCharsets.UTF_8);
        Map<Integer, byte[]> payloads = Map.of(slot, payload);
        Map<Integer, Long> times = Map.of(slot, 7L);

        // Control: raw-len Pumpkin shape parses as Pumpkin.
        ChunkSlotTable ok = new ChunkSlotTable();
        ok.sourceName = "slotLengthIsRawLen";
        PumpkinLinear.shared().parse(pumpkinImage(2, 5, 7, payloads, times, 0), ok);
        assertEquals(payload.length, ok.uncompressedSize[slot]);

        // DivineMC len+8 in a Pumpkin envelope must fail, never misread.
        try {
            ChunkSlotTable bad = new ChunkSlotTable();
            bad.sourceName = "slotLengthIsRawLen";
            PumpkinLinear.shared().parse(pumpkinImage(2, 5, 7, payloads, times, 8), bad);
            fail("expected IOException for len+8 slot in Pumpkin envelope");
        } catch (IOException expected) {
        }

        // Control: len+8 V2 shape parses as V2.
        ChunkSlotTable v2ok = new ChunkSlotTable();
        v2ok.sourceName = "slotLengthIsRawLen";
        V2Linear.shared().parse(v2Image(2, payloads, times, 8), v2ok);
        assertEquals(payload.length, v2ok.uncompressedSize[slot]);

        // Raw len in a V2 envelope must fail, never misread.
        try {
            ChunkSlotTable v2bad = new ChunkSlotTable();
            v2bad.sourceName = "slotLengthIsRawLen";
            V2Linear.shared().parse(v2Image(2, payloads, times, 0), v2bad);
            fail("expected IOException for raw-len slot in V2 envelope");
        } catch (IOException expected) {
        }
    }

    @Test
    public void coordsMismatchRejected() throws Exception {
        int slot = ChunkKey.slotIndex(ChunkKey.of(3, 5));
        byte[] payload = "coords-probe".repeat(8).getBytes(StandardCharsets.UTF_8);
        Path dir = Files.createTempDirectory("pumpkin-coords");
        Path file = dir.resolve("r.0.0.linear");
        writePumpkinShape(file, 2, 5, 7, Map.of(slot, payload), Map.of(slot, 9L), 0);
        byte[] raw = Files.readAllBytes(file);
        try {
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(raw), 0, 0);
            fail("expected IOException for coords mismatch");
        } catch (IOException expected) {
        }
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN,
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(raw), 5, 7));
        try {
            new LinearRegionFile(file, 6);
            fail("expected IOException loading Pumpkin file under the wrong name");
        } catch (IOException expected) {
        }
    }

    @Test
    public void u64TimestampClamps() throws Exception {
        int slot = ChunkKey.slotIndex(ChunkKey.of(3, 5));
        byte[] payload = "clamp-probe".repeat(8).getBytes(StandardCharsets.UTF_8);
        ByteBuffer image = pumpkinImage(2, 5, 7,
            Map.of(slot, payload), Map.of(slot, 5_000_000_000L), 0);
        ChunkSlotTable out = new ChunkSlotTable();
        out.sourceName = "u64TimestampClamps";
        PumpkinLinear.shared().parse(image, out);
        assertEquals(Integer.MAX_VALUE, out.timestamps[slot]);
        assertArrayEquals(payload, lz4Unpack(out.lz4Payloads[slot], out.uncompressedSize[slot]));

        ByteBuffer small = pumpkinImage(2, 5, 7, Map.of(slot, payload), Map.of(slot, 42L), 0);
        ChunkSlotTable smallOut = new ChunkSlotTable();
        smallOut.sourceName = "u64TimestampClamps";
        PumpkinLinear.shared().parse(small, smallOut);
        assertEquals(42, smallOut.timestamps[slot]);
    }

    @Test
    public void pumpkinPreservedOnFlush() throws Exception {
        Path dir = Files.createTempDirectory("pumpkin-preserve");
        Path file = dir.resolve("r.5.7.linear");
        int slotA = ChunkKey.slotIndex(ChunkKey.of(3, 5));
        byte[] payloadA = "preserve-a".repeat(16).getBytes(StandardCharsets.UTF_8);
        writePumpkinShape(file, 2, 5, 7, Map.of(slotA, payloadA), Map.of(slotA, 11L), 0);
        byte[] payloadB = "preserve-b".repeat(16).getBytes(StandardCharsets.UTF_8);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, region.linear$detectedFormat());
            region.write(ChunkKey.of(30, 31), ByteBuffer.wrap(payloadB));
            region.flush();
        }
        byte[] raw = Files.readAllBytes(file);
        assertEquals(2, raw[8] & 0xFF);
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN,
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(raw), 5, 7));
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, region.linear$detectedFormat());
            assertEquals(2, region.linear$detectedGrid());
            assertArrayEquals(payloadA, readAll(region, ChunkKey.of(3, 5)));
            assertArrayEquals(payloadB, readAll(region, ChunkKey.of(30, 31)));
        }
    }

    /**
     * Golden interop: file written by Pumpkin's own writer
     * (crates/pumpkin-world, grid 2, region 0,0, 570 B, 5 chunks).
     * Detector must say PUMPKIN, every manifest slot must parse with the
     * exact len/sha256/ts, and a flush must preserve PUMPKIN + payloads.
     */
    @Test
    public void realServerPumpkinFixture() throws Exception {
        Path dir = Files.createTempDirectory("pumpkin-real");
        Path file = dir.resolve("r.0.0.linear");
        Files.write(file, java.util.Base64.getDecoder().decode(GOLDEN_PUMPKIN_REAL));
        byte[] raw = Files.readAllBytes(file);
        assertEquals(570, raw.length);
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN,
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(raw), 0, 0));
        assertEquals(2, RegionFormatDetector.shared().gridOf(ByteBuffer.wrap(raw),
            RegionFormatDetector.DetectedFormat.PUMPKIN));
        ChunkSlotTable table = new ChunkSlotTable();
        table.sourceName = "realServerPumpkinFixture";
        PumpkinLinear.shared().parse(ByteBuffer.wrap(raw), table);
        assertRealSlot(table, 0, 27,
            "2adedc3d8d50fbc878f2dad2403be1394f9ab786d39337320b6cd3bc4a5894d1", 1_700_000_000);
        assertRealSlot(table, 126, 91,
            "9d2be207a7054e0c98e96f14bd956ac4f9e9f7157993de8c548621e80991e81c", 1_700_000_126);
        assertRealSlot(table, 180, 81,
            "383a3c704e261bb4a437a6889e5a716de27c47406acc21f2580d2b3aab1de757", 1_700_000_180);
        assertRealSlot(table, 645, 34,
            "78a1d11081a273b040be1fd35a0d50fda724e1e5592feccd98e4983f3203d919", 1_700_000_645);
        assertRealSlot(table, 963, 32,
            "edc04b668e097aa4803c4f2373b79419db5674438cfd5f78debd3bc39de32672", 1_700_000_963);
        Map<Integer, byte[]> expected = new HashMap<>();
        for (int slot : new int[] {0, 126, 180, 645, 963}) {
            expected.put(slot, lz4Unpack(table.lz4Payloads[slot], table.uncompressedSize[slot]));
        }
        byte[] probe = "pumpkin-real-preserve".repeat(16).getBytes(StandardCharsets.UTF_8);
        long probeChunk = ChunkKey.of(31, 31);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, region.linear$detectedFormat());
            assertEquals(2, region.linear$detectedGrid());
            for (Map.Entry<Integer, byte[]> e : expected.entrySet()) {
                long chunk = ChunkKey.of(e.getKey() % 32, e.getKey() / 32);
                assertEquals(e.getKey().intValue(), ChunkKey.slotIndex(chunk));
                assertArrayEquals("payload slot " + e.getKey(), e.getValue(), readAll(region, chunk));
            }
            region.write(probeChunk, ByteBuffer.wrap(probe));
            region.flush();
        }
        byte[] flushed = Files.readAllBytes(file);
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN,
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(flushed), 0, 0));
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, region.linear$detectedFormat());
            assertEquals(2, region.linear$detectedGrid());
            for (Map.Entry<Integer, byte[]> e : expected.entrySet()) {
                long chunk = ChunkKey.of(e.getKey() % 32, e.getKey() / 32);
                assertArrayEquals("preserved slot " + e.getKey(), e.getValue(), readAll(region, chunk));
            }
            assertArrayEquals(probe, readAll(region, probeChunk));
        }
    }

    private static void assertRealSlot(ChunkSlotTable table, int slot, int len, String sha256, int ts)
            throws Exception {
        assertEquals("len slot " + slot, len, table.uncompressedSize[slot]);
        assertEquals("ts slot " + slot, ts, table.timestamps[slot]);
        assertNotNull("payload slot " + slot, table.lz4Payloads[slot]);
        byte[] plain = lz4Unpack(table.lz4Payloads[slot], table.uncompressedSize[slot]);
        assertEquals("unpacked len slot " + slot, len, plain.length);
        assertEquals("sha256 slot " + slot, sha256, sha256Hex(plain));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    public void offDiagonalBucketGrid2() throws Exception {
        // Chunk (20,5) grid 2: x-bucket 1, z-bucket 0.
        // DivineMC bucket 1*2+0=2, Pumpkin bucket 0*2+1=1 (transposed).
        int slot = ChunkKey.slotIndex(ChunkKey.of(20, 5));
        int transposed = ChunkKey.slotIndex(ChunkKey.of(5, 20));
        byte[] payload = "off-diagonal-20-5".repeat(16).getBytes(StandardCharsets.UTF_8);
        Path dir = Files.createTempDirectory("pumpkin-offdiag");
        Path file = dir.resolve("r.0.0.linear");
        writePumpkinShape(file, 2, 0, 0, Map.of(slot, payload), Map.of(slot, 77L), 0);
        byte[] raw = Files.readAllBytes(file);
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN,
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(raw), 0, 0));
        // Independent bucket proof: only Pumpkin bucket 1 contains the payload bytes.
        assertTrue("pumpkin bucket 1 must hold (20,5)", bucketBodyContains(raw, 2, 1, payload));
        assertTrue("divine bucket 2 must not hold (20,5) in pumpkin file",
            !bucketBodyContains(raw, 2, 2, payload));
        // Reader proof: correct slot has it, transposed slot is absent.
        ChunkSlotTable out = new ChunkSlotTable();
        out.sourceName = "offDiagonalBucketGrid2";
        PumpkinLinear.shared().parse(ByteBuffer.wrap(raw), out);
        assertEquals(payload.length, out.uncompressedSize[slot]);
        assertArrayEquals(payload, lz4Unpack(out.lz4Payloads[slot], out.uncompressedSize[slot]));
        assertEquals(0, out.uncompressedSize[transposed]);
    }

    @Test
    public void asymmetricPairGrid8() throws Exception {
        // Grid 8, cell 4: (3,30) -> bx 0,bz 7; (30,3) -> bx 7,bz 0.
        // Pumpkin buckets 56 and 7 (swapped vs DivineMC 7 and 56).
        int slotA = ChunkKey.slotIndex(ChunkKey.of(3, 30));
        int slotB = ChunkKey.slotIndex(ChunkKey.of(30, 3));
        byte[] payloadA = "asymmetric-3-30".repeat(16).getBytes(StandardCharsets.UTF_8);
        byte[] payloadB = "asymmetric-30-3".repeat(16).getBytes(StandardCharsets.UTF_8);
        Path dir = Files.createTempDirectory("pumpkin-asym");
        Path file = dir.resolve("r.1.2.linear");
        writePumpkinShape(file, 8, 1, 2,
            Map.of(slotA, payloadA, slotB, payloadB),
            Map.of(slotA, 101L, slotB, 102L), 0);
        byte[] raw = Files.readAllBytes(file);
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN,
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(raw), 1, 2));
        // Cross-mapping: Pumpkin bucket 56 holds (3,30), bucket 7 holds (30,3).
        assertTrue("bucket 56 must hold (3,30)", bucketBodyContains(raw, 8, 56, payloadA));
        assertTrue("bucket 7 must hold (30,3)", bucketBodyContains(raw, 8, 7, payloadB));
        assertTrue("bucket 56 must not hold (30,3)", !bucketBodyContains(raw, 8, 56, payloadB));
        assertTrue("bucket 7 must not hold (3,30)", !bucketBodyContains(raw, 8, 7, payloadA));
        ChunkSlotTable out = new ChunkSlotTable();
        out.sourceName = "asymmetricPairGrid8";
        PumpkinLinear.shared().parse(ByteBuffer.wrap(raw), out);
        assertArrayEquals(payloadA, lz4Unpack(out.lz4Payloads[slotA], out.uncompressedSize[slotA]));
        assertArrayEquals(payloadB, lz4Unpack(out.lz4Payloads[slotB], out.uncompressedSize[slotB]));
    }

    private static boolean bucketBodyContains(byte[] raw, int grid, int bucketIdx, byte[] needle)
            throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(raw);
        buf.getLong(); // magic
        buf.get(); // version
        buf.getLong(); // newest
        buf.get(); // grid
        buf.getInt(); // regionX
        buf.getInt(); // regionZ
        buf.position(buf.position() + 128); // bitmap
        if (buf.get() != 0) {
            throw new IOException("unexpected features in test fixture");
        }
        int buckets = grid * grid;
        int[] sizes = new int[buckets];
        for (int i = 0; i < buckets; i++) {
            sizes[i] = buf.getInt();
            buf.get(); // level
            buf.getLong(); // hash
        }
        int off = buf.position();
        for (int i = 0; i < buckets; i++) {
            int len = sizes[i];
            if (i == bucketIdx) {
                byte[] comp = Arrays.copyOfRange(raw, off, off + len);
                byte[] decomp;
                try (com.github.luben.zstd.ZstdInputStream zin =
                        new com.github.luben.zstd.ZstdInputStream(
                            new java.io.ByteArrayInputStream(comp))) {
                    decomp = zin.readAllBytes();
                }
                return indexOf(decomp, needle) >= 0;
            }
            off += len;
        }
        throw new IOException("bucket out of range");
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAll(LinearRegionFile region, long chunk) throws Exception {
        DataInputStream in = region.getChunkDataInputStream(chunk);
        assertNotNull(in);
        byte[] back = in.readAllBytes();
        in.close();
        return back;
    }

    private static ByteBuffer pumpkinImage(int grid, int regionX, int regionZ,
            Map<Integer, byte[]> payloads, Map<Integer, Long> timestamps, int slotBias)
            throws IOException {
        Path dir = Files.createTempDirectory("pumpkin-image");
        Path file = dir.resolve("r." + regionX + "." + regionZ + ".linear");
        writePumpkinShape(file, grid, regionX, regionZ, payloads, timestamps, slotBias);
        return ByteBuffer.wrap(Files.readAllBytes(file));
    }

    /**
     * Hand-built Pumpkin file in the upstream shape: every bucket compressed
     * (even empty ones), level byte always 1, LSB-first bitmap.
     * {@code slotBias} shifts the stored slot length (0 = spec, 8 = DivineMC).
     */
    static void writePumpkinShape(Path file, int grid, int regionX, int regionZ,
            Map<Integer, byte[]> payloads, Map<Integer, Long> timestamps, int slotBias)
            throws IOException {
        int cell = 32 / grid;
        int buckets = grid * grid;
        long newest = 0L;
        for (long ts : timestamps.values()) {
            if (Long.compareUnsigned(ts, newest) > 0) {
                newest = ts;
            }
        }
        byte[][] frames = new byte[buckets][];
        for (int b = 0; b < buckets; b++) {
            // Pumpkin upstream: bucket = brow*grid+bcol with brow<->z, bcol<->x,
            // interiors z-outer/x-inner (local = zOff*stride + xOff).
            int bx = b % grid;
            int bz = b / grid;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZstdOutputStream zstd = new ZstdOutputStream(baos, 1)) {
                zstd.setChecksum(true);
                DataOutputStream out = new DataOutputStream(zstd);
                for (int cz = 0; cz < cell; cz++) {
                    for (int cx = 0; cx < cell; cx++) {
                        int slot = (bx * cell + cx) + (bz * cell + cz) * 32;
                        byte[] p = payloads.get(slot);
                        long ts = timestamps.getOrDefault(slot, 0L);
                        if (p != null) {
                            out.writeInt(p.length + slotBias);
                            out.writeLong(ts);
                            out.write(p);
                        } else {
                            out.writeInt(0);
                            out.writeLong(ts);
                        }
                    }
                }
                out.flush();
            }
            frames[b] = baos.toByteArray();
        }
        byte[] bitmap = new byte[128];
        for (int slot : payloads.keySet()) {
            bitmap[slot / 8] |= (byte) (1 << (slot % 8));
        }
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeLong(MAGIC);
            out.writeByte(2);
            out.writeLong(newest);
            out.writeByte(grid);
            out.writeInt(regionX);
            out.writeInt(regionZ);
            out.write(bitmap);
            out.writeByte(0); // features terminator
            for (byte[] f : frames) {
                out.writeInt(f.length);
                out.writeByte(1);
                out.writeLong(LinearXxHash64.hashBytes(f, 0, f.length));
            }
            for (byte[] f : frames) {
                out.write(f);
            }
            out.writeLong(MAGIC);
        }
    }

    /** Minimal DivineMC V2-shaped file (only non-empty buckets stored, level 3). */
    static ByteBuffer v2Image(int grid, Map<Integer, byte[]> payloads,
            Map<Integer, Long> timestamps, int slotBias) throws IOException {
        int cell = 32 / grid;
        int buckets = grid * grid;
        Map<Integer, byte[]> byBucket = new HashMap<>();
        for (int b = 0; b < buckets; b++) {
            int bx = b / grid;
            int bz = b % grid;
            boolean has = false;
            for (int cx = 0; cx < cell && !has; cx++) {
                for (int cz = 0; cz < cell && !has; cz++) {
                    if (payloads.containsKey((bx * cell + cx) + (bz * cell + cz) * 32)) {
                        has = true;
                    }
                }
            }
            if (!has) {
                continue;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZstdOutputStream zstd = new ZstdOutputStream(baos, 3)) {
                zstd.setChecksum(true);
                DataOutputStream out = new DataOutputStream(zstd);
                for (int cx = 0; cx < cell; cx++) {
                    for (int cz = 0; cz < cell; cz++) {
                        int slot = (bx * cell + cx) + (bz * cell + cz) * 32;
                        byte[] p = payloads.get(slot);
                        long ts = timestamps.getOrDefault(slot, 0L);
                        if (p != null) {
                            out.writeInt(p.length + slotBias);
                            out.writeLong(ts);
                            out.write(p);
                        } else {
                            out.writeInt(0);
                            out.writeLong(ts);
                        }
                    }
                }
                out.flush();
            }
            byBucket.put(b, baos.toByteArray());
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(file)) {
            out.writeLong(MAGIC);
            out.writeByte(3);
            out.writeLong(1_700_000_200L);
            out.writeByte(grid);
            out.writeInt(0);
            out.writeInt(0);
            out.write(new byte[128]);
            out.writeByte(0);
            for (int b = 0; b < buckets; b++) {
                byte[] f = byBucket.get(b);
                out.writeInt(f != null ? f.length : 0);
                out.writeByte(3);
                out.writeLong(f != null ? LinearXxHash64.hashBytes(f, 0, f.length) : 0L);
            }
            for (int b = 0; b < buckets; b++) {
                if (byBucket.get(b) != null) {
                    out.write(byBucket.get(b));
                }
            }
            out.writeLong(MAGIC);
        }
        return ByteBuffer.wrap(file.toByteArray());
    }

    private static byte[] lz4Pack(byte[] plain) {
        net.jpountz.lz4.LZ4Compressor compressor = LZ4Factory.fastestInstance().fastCompressor();
        int max = compressor.maxCompressedLength(plain.length);
        byte[] dst = new byte[max];
        int n = compressor.compress(plain, 0, plain.length, dst, 0, max);
        return Arrays.copyOf(dst, n);
    }

    private static byte[] lz4Unpack(ByteBuffer frame, int size) {
        byte[] src = new byte[frame.remaining()];
        frame.duplicate().get(src);
        byte[] dst = new byte[size];
        int n = LZ4Factory.fastestInstance().fastDecompressor().decompress(src, 0, dst, 0, size);
        assertTrue("lz4 consumed the whole frame", n == src.length);
        return dst;
    }
}
