package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.PumpkinLinear;
import net.linear.versions.RegionFormatDetector;
import net.linear.versions.RegionFormatDetector.DetectedFormat;
import net.linear.versions.VersionWriteParams;
import org.junit.Test;

/**
 * {@link RegionFormatDetector} tests: each format resolves to itself, garbage
 * errors as corrupt/unknown, and a file satisfying two layouts errors as
 * ambiguous (never guessed).
 */
public class RegionFormatDetectorTest {

    @Test
    public void v1Wire2DetectsAsV1() throws Exception {
        // Level 1 collides with the grid whitelist, so this exercises the
        // full structural path (coords/length), not byte sniffing.
        Path dir = Files.createTempDirectory("detect-v1");
        Path file = dir.resolve("r.0.0.linear");
        byte[] payload = "detect-v1".repeat(16).getBytes(StandardCharsets.UTF_8);
        try (LinearRegionFile region = new LinearRegionFile(file, 1)) {
            region.write(ChunkKey.of(4, 4), ByteBuffer.wrap(payload));
            region.flush();
        }
        byte[] raw = Files.readAllBytes(file);
        assertEquals(2, raw[8] & 0xFF);
        assertEquals(DetectedFormat.V1,
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(raw), 0, 0));
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            assertEquals(DetectedFormat.V1, region.linear$detectedFormat());
            assertArrayEquals(payload, readAll(region, ChunkKey.of(4, 4)));
        }
    }

    @Test
    public void divineGoldenV2DetectsAsV2() throws Exception {
        byte[] raw = java.util.Base64.getDecoder().decode(GOLDEN_V2);
        assertEquals(DetectedFormat.V2,
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(raw), 0, 0));
    }

    @Test
    public void divineGoldenV3DetectsAsV3() throws Exception {
        byte[] raw = java.util.Base64.getDecoder().decode(GOLDEN_V3);
        assertEquals(DetectedFormat.V3,
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(raw), 1, 0));
    }

    @Test
    public void handBuiltPumpkinDetectsAsPumpkin() throws Exception {
        int slot = ChunkKey.slotIndex(ChunkKey.of(3, 5));
        byte[] payload = "detect-pumpkin".repeat(8).getBytes(StandardCharsets.UTF_8);
        Path dir = Files.createTempDirectory("detect-pumpkin");
        Path file = dir.resolve("r.5.7.linear");
        PumpkinLinearTest.writePumpkinShape(file, 2, 5, 7,
            Map.of(slot, payload), Map.of(slot, 13L), 0);
        byte[] raw = Files.readAllBytes(file);
        assertEquals(DetectedFormat.PUMPKIN,
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(raw), 5, 7));
        assertEquals(2, RegionFormatDetector.shared().gridOf(ByteBuffer.wrap(raw),
            DetectedFormat.PUMPKIN));
    }

    @Test
    public void truncatedIsCorrupt() throws Exception {
        Path dir = Files.createTempDirectory("detect-trunc");
        Path file = dir.resolve("r.0.0.linear");
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(1, 1),
                ByteBuffer.wrap("trunc".repeat(16).getBytes(StandardCharsets.UTF_8)));
            region.flush();
        }
        byte[] raw = Files.readAllBytes(file);
        byte[] cut = new byte[raw.length / 2];
        System.arraycopy(raw, 0, cut, 0, cut.length);
        try {
            RegionFormatDetector.shared().detect(ByteBuffer.wrap(cut), 0, 0);
            fail("expected corrupt error for truncated file");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("corrupt/unknown"));
        }
    }

    @Test
    public void unknownWireIsCorrupt() throws Exception {
        ByteBuffer image = ByteBuffer.allocate(48);
        image.putLong(0xC3FF13183CCA9D9AL);
        image.put((byte) 9);
        image.flip();
        try {
            RegionFormatDetector.shared().detect(image, 0, 0);
            fail("expected corrupt error for unknown wire byte");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("corrupt/unknown"));
        }
    }

    @Test
    public void v1LengthPlusPumpkinStructureIsAmbiguous() throws Exception {
        // Empty grid-1 Pumpkin file: fixed layout, so its length L0 is known.
        // Choosing header coords (0, D << 16) with D = L0 - 40 encodes
        // dataCount == D into the V1 header fields, satisfying the V1 length
        // equation on top of the valid Pumpkin structure.
        ChunkSlotTable empty = new ChunkSlotTable();
        empty.sourceName = "ambiguous";
        ByteBuffer probe = PumpkinLinear.shared().flush(
            empty, new VersionWriteParams(6, 1, 0, 0, 0L, 0, 0));
        int dataCount = probe.remaining() - 40;
        assertTrue("fixture fits the ambiguous encoding", dataCount > 0 && dataCount < 65536);
        int regionZ = dataCount << 16;
        ByteBuffer image = PumpkinLinear.shared().flush(
            empty, new VersionWriteParams(6, 1, 0, regionZ, 0L, 0, 0));
        assertEquals(image.remaining() - 40, dataCount);
        try {
            RegionFormatDetector.shared().detect(image, 0, regionZ);
            fail("expected ambiguous error for dual-layout file");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Ambiguous"));
            assertTrue(expected.getMessage().contains("V1"));
            assertTrue(expected.getMessage().contains("PUMPKIN"));
        }
    }

    private static byte[] readAll(LinearRegionFile region, long chunk) throws Exception {
        DataInputStream in = region.getChunkDataInputStream(chunk);
        assertNotNull(in);
        byte[] back = in.readAllBytes();
        in.close();
        return back;
    }

    /** DivineMC V2 writer output: grid 8, chunks [1,2]+[30,31], level 3. */
    private static final String GOLDEN_V2 =
        "w/8TGDzKnZoDAAAAAGq/AvsIAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAA+Aze/aHYM393CAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAA9A5EKmFXyVtgIKLUv/QBYlAEAJAIAAAFoAAAAAGVT8QBnb2xkZW4tZGl2aW5lLXYyLWNodW5rBADgAaJdKMBRTvUVCrABAAAotS/9AFiMAQAkAgAAAWgAAAAAZVPxAWdvbGRlbi1kaXZpbmUtdjItb3RoZXIDAMGvCOAop/oCFMACAQAAw/8TGDzKnZo=";

    /** DivineMC V3 writer output: grid 8, chunk [5,7], level 3. */
    private static final String GOLDEN_V3 =
        "w/8TGDzKnZoECAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD7CWrl7UmbvxAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACi1L/0AWJQBACQCAAABaAAAAABlU/ECZ29sZGVuLWRpdmluZS12Mi1jaHVuawQAcAAArwngKKf6YgJYAQAAw/8TGDzKnZo=";
}
