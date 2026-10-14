package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.jpountz.lz4.LZ4Factory;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.codecs.PumpkinLinear;
import net.linear.versions.RegionFormatDetector;
import net.linear.versions.VersionWriteParams;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Phase-3 Pumpkin conversions (new files only; existing tests untouched).
 *
 * <ul>
 *   <li>Pumpkin source -&gt; V1 (payload-identical).</li>
 *   <li>V1 -&gt; Pumpkin (detector PUMPKIN + payload-identical + dense buckets + level 1).</li>
 *   <li>V1 -&gt; V3 direct.</li>
 *   <li>Pumpkin -&gt; B_LINEAR.</li>
 *   <li>MCA-&gt;Pumpkin vs V1 shadow escalation (both kept; same target path,
 *       cross-extension shadow — not two same-name .linear files: same-path
 *       .linear converts are one inode via pre-swap-validated atomic swap).</li>
 *   <li>Validation failure (corrupt Pumpkin source -&gt; FAILED, source kept).</li>
 * </ul>
 */
public class ConverterPumpkinTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void restoreDefaultSeams() {
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            String name = file.getFileName().toString();
            if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                return new BufferedLinearRegionFile(file, level);
            }
            if (format == null || format == RegionFileFormat.LINEAR) {
                return new LinearRegionFile(file, level);
            }
            throw new IOException("No RegionOpener registered for non-linear open: " + file);
        });
        LinearRegionConverter.linear$setChunkSinkOpener((file, folder, level) -> {
            throw new IOException("no ChunkSinkOpener registered for " + file);
        });
        LinearRegionConverter.linear$setReverseSkipPredicate(p -> false);
        LinearRegionConverter.linear$setForwardSkipPredicate(p -> false);
    }

    private static Map<Long, byte[]> sampleChunks(int regionX, int regionZ) {
        Map<Long, byte[]> chunks = new HashMap<>();
        Random random = new Random(0x11A4);
        for (int i = 0; i < 6; i++) {
            byte[] payload = new byte[32 + i * 53];
            random.nextBytes(payload);
            chunks.put(ChunkKey.of(regionX * 32 + i, regionZ * 32 + (i % 4)), payload);
        }
        return chunks;
    }

    private static byte[] lz4Pack(byte[] plain) {
        net.jpountz.lz4.LZ4Compressor comp = LZ4Factory.fastestInstance().fastCompressor();
        int max = comp.maxCompressedLength(plain.length);
        byte[] packed = new byte[max];
        int n = comp.compress(plain, 0, plain.length, packed, 0, max);
        byte[] out = new byte[n];
        System.arraycopy(packed, 0, out, 0, n);
        return out;
    }

    private static void writeV1File(Path file, Map<Long, byte[]> chunks) throws Exception {
        try (LinearRegionFile dst =
                new LinearRegionFile(file, 6, LinearImplementation.V1, 8)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                dst.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            dst.flush();
        }
    }

    private static void writeV2File(Path file, Map<Long, byte[]> chunks) throws Exception {
        try (LinearRegionFile dst =
                new LinearRegionFile(file, 6, LinearImplementation.V2, 8)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                dst.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            dst.flush();
        }
    }

    private static void writeBLinearFile(Path file, Map<Long, byte[]> chunks) throws Exception {
        try (BufferedLinearRegionFile dst = new BufferedLinearRegionFile(file, 6)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                dst.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            dst.flush();
        }
    }

    private static void writePumpkinFile(Path file, Map<Long, byte[]> chunks,
            int regionX, int regionZ, int grid) throws Exception {
        ChunkSlotTable table = new ChunkSlotTable();
        table.sourceName = "ConverterPumpkinTest-setup";
        for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
            int slot = ChunkKey.slotIndex(e.getKey());
            byte[] payload = e.getValue();
            table.uncompressedSize[slot] = payload.length;
            table.timestamps[slot] = 1_700_000_000 + (slot % 100);
            table.lz4Payloads[slot] =
                ByteBuffer.wrap(lz4Pack(payload)).asReadOnlyBuffer();
        }
        VersionWriteParams params =
            new VersionWriteParams(6, grid, regionX, regionZ, 1_700_000_100L, 0, 0);
        ByteBuffer image = PumpkinLinear.shared().flush(table, params);
        byte[] bytes = new byte[image.remaining()];
        image.get(bytes);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static Map<Long, byte[]> readLinearPayloads(Path file, int regionX, int regionZ)
            throws Exception {
        Map<Long, byte[]> out = new HashMap<>();
        try (LinearRegionFile src = new LinearRegionFile(file, 6)) {
            for (int lx = 0; lx < 32; lx++) {
                for (int lz = 0; lz < 32; lz++) {
                    long pos = ChunkKey.of(regionX * 32 + lx, regionZ * 32 + lz);
                    if (!src.hasChunk(pos)) {
                        continue;
                    }
                    try (DataInputStream in = src.getChunkDataInputStream(pos)) {
                        if (in == null) {
                            continue;
                        }
                        byte[] payload = in.readAllBytes();
                        if (payload.length == 0) {
                            continue;
                        }
                        out.put(pos, payload);
                    }
                }
            }
        }
        return out;
    }

    private static RegionFormatDetector.DetectedFormat detect(Path file, int x, int z)
            throws Exception {
        byte[] raw = Files.readAllBytes(file);
        return RegionFormatDetector.shared().detect(ByteBuffer.wrap(raw), x, z);
    }

    private static void assertPayloadsEqual(Map<Long, byte[]> expected, Map<Long, byte[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
            assertArrayEquals("chunk " + ChunkKey.format(e.getKey()), e.getValue(), actual.get(e.getKey()));
        }
    }

    private static void wireForwardMca(Map<Long, byte[]> mcaChunks) {
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new FakeMcaFile(mcaChunks);
            }
            return new LinearRegionFile(file, level);
        });
    }

    @Test
    public void pumpkinSourceToV1IsPayloadIdentical() throws Exception {
        Path region = temporaryFolder.newFolder("p2v1").toPath();
        Map<Long, byte[]> expected = sampleChunks(5, 7);
        Path pumpkin = region.resolve("r.5.7.linear");
        writePumpkinFile(pumpkin, expected, 5, 7, 2);
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, detect(pumpkin, 5, 7));

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 0, null, false,
                LinearRegionConverter.ConvertFormat.PUMPKIN, LinearRegionConverter.ConvertFormat.V1);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        // Same-file swap: the single .linear path now holds V1 bytes.
        assertTrue(Files.isRegularFile(pumpkin));
        assertEquals(RegionFormatDetector.DetectedFormat.V1, detect(pumpkin, 5, 7));
        assertPayloadsEqual(expected, readLinearPayloads(pumpkin, 5, 7));
    }

    @Test
    public void v1ToPumpkinDetectorDenseLevelOneAndPayloadIdentical() throws Exception {
        Path region = temporaryFolder.newFolder("v2p").toPath();
        Map<Long, byte[]> expected = sampleChunks(5, 7);
        Path file = region.resolve("r.5.7.linear");
        writeV1File(file, expected);
        assertEquals(RegionFormatDetector.DetectedFormat.V1, detect(file, 5, 7));

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 0, null, false,
                LinearRegionConverter.ConvertFormat.V1, LinearRegionConverter.ConvertFormat.PUMPKIN);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, detect(file, 5, 7));
        assertPayloadsEqual(expected, readLinearPayloads(file, 5, 7));

        // Dense buckets + level bytes 1 (grid 2 => 4 buckets, header 155 B, 13 B entries).
        byte[] raw = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        // Superblock already verified by the detector; parse the table directly.
        int headerLen = 8 + 1 + 8 + 1 + 4 + 4 + 128 + 1;
        assertEquals(155, headerLen);
        for (int b = 0; b < 4; b++) {
            int off = headerLen + b * 13;
            long size = ((raw[off] & 0xFFL) << 24) | ((raw[off + 1] & 0xFFL) << 16)
                | ((raw[off + 2] & 0xFFL) << 8) | (raw[off + 3] & 0xFFL);
            int levelByte = raw[off + 4] & 0xFF;
            assertEquals("table level byte " + b, 1, levelByte);
            assertTrue("pumpkin bucket " + b + " dense (size " + size + ")", size != 0L);
        }
        // Exercise the buffer to avoid unused warnings on big-endian reads.
        assertTrue(buf.capacity() > headerLen);
    }

    @Test
    public void v1ToV3Direct() throws Exception {
        Path region = temporaryFolder.newFolder("v1v3").toPath();
        Map<Long, byte[]> expected = sampleChunks(1, 2);
        Path file = region.resolve("r.1.2.linear");
        writeV1File(file, expected);
        assertEquals(RegionFormatDetector.DetectedFormat.V1, detect(file, 1, 2));

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 8, null, false,
                LinearRegionConverter.ConvertFormat.V1, LinearRegionConverter.ConvertFormat.V3);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        assertEquals(RegionFormatDetector.DetectedFormat.V3, detect(file, 1, 2));
        assertPayloadsEqual(expected, readLinearPayloads(file, 1, 2));
    }

    @Test
    public void pumpkinToBLinear() throws Exception {
        Path region = temporaryFolder.newFolder("p2b").toPath();
        Map<Long, byte[]> expected = sampleChunks(3, 4);
        Path pumpkin = region.resolve("r.3.4.linear");
        writePumpkinFile(pumpkin, expected, 3, 4, 2);

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 0, null, false,
                LinearRegionConverter.ConvertFormat.PUMPKIN,
                LinearRegionConverter.ConvertFormat.B_LINEAR);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        assertFalse(Files.isRegularFile(pumpkin));
        Path blinear = region.resolve("r.3.4.b_linear");
        assertTrue(Files.isRegularFile(blinear));
        Map<Long, byte[]> actual = new HashMap<>();
        try (BufferedLinearRegionFile src = new BufferedLinearRegionFile(blinear, 6)) {
            for (int lx = 0; lx < 32; lx++) {
                for (int lz = 0; lz < 32; lz++) {
                    long pos = ChunkKey.of(3 * 32 + lx, 4 * 32 + lz);
                    if (!src.hasChunk(pos)) {
                        continue;
                    }
                    try (DataInputStream in = src.getChunkDataInputStream(pos)) {
                        if (in == null) {
                            continue;
                        }
                        byte[] payload = in.readAllBytes();
                        if (payload.length == 0) {
                            continue;
                        }
                        actual.put(pos, payload);
                    }
                }
            }
        }
        assertPayloadsEqual(expected, actual);
    }

    @Test
    public void mcaToPumpkinVsV1ShadowEscalatesBothKept() throws Exception {
        Path region = temporaryFolder.newFolder("mixed").toPath();
        Path mca = region.resolve("r.0.0.mca");
        Files.write(mca, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        Map<Long, byte[]> chunks = sampleChunks(0, 0);
        wireForwardMca(new java.util.concurrent.ConcurrentHashMap<>(chunks));
        // Cross-extension shadow: MCA source whose desired Pumpkin target path
        // (r.0.0.linear per FR7) is already occupied by a V1 file. Same target
        // path, different source extension — not two same-name .linear files
        // (same-path .linear converts are one inode via atomic swap).
        Path shadow = region.resolve("r.0.0.linear");
        writeV1File(shadow, chunks);
        long shadowBytes = Files.size(shadow);

        try {
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 0, null, false,
                LinearRegionConverter.ConvertFormat.MCA, LinearRegionConverter.ConvertFormat.PUMPKIN);
            fail("expected ConversionProtectionException for cross-extension same-target-path shadow");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue("mca source must survive", Files.isRegularFile(mca));
        assertTrue("V1 shadow must survive (never silently overwritten)", Files.isRegularFile(shadow));
        assertEquals("V1 shadow must be byte-identical (no Pumpkin overwrite)",
            shadowBytes, Files.size(shadow));
        assertEquals(RegionFormatDetector.DetectedFormat.V1, detect(shadow, 0, 0));
    }

    @Test
    public void corruptPumpkinSourceFailsClosedSourceKept() throws Exception {
        Path region = temporaryFolder.newFolder("corrupt").toPath();
        Path bad = region.resolve("r.9.9.linear");
        byte[] garbage = new byte[100];
        java.util.Arrays.fill(garbage, (byte) 0x55);
        Files.write(bad, garbage);

        try {
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 0, null, false,
                LinearRegionConverter.ConvertFormat.PUMPKIN, LinearRegionConverter.ConvertFormat.V1);
            fail("expected ConversionProtectionException for corrupt Pumpkin source");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue("corrupt source must survive", Files.isRegularFile(bad));
        assertFalse(Files.isRegularFile(region.resolve("r.9.9.b_linear")));
    }

    @Test
    public void startupAutoConversionNeverRewritesPumpkinShadow() throws Exception {
        Path region = temporaryFolder.newFolder("startup").toPath();
        Path mca = region.resolve("r.0.1.mca");
        Files.write(mca, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        Map<Long, byte[]> chunks = sampleChunks(0, 1);
        wireForwardMca(new java.util.concurrent.ConcurrentHashMap<>(chunks));
        Path pumpkinShadow = region.resolve("r.0.1.linear");
        writePumpkinFile(pumpkinShadow, chunks, 0, 1, 2);
        long pumpkinBytes = Files.size(pumpkinShadow);

        try {
            LinearRegionConverter.convertRegionFolder(region, 6, null, false);
            fail("expected protection (Pumpkin shadow must escalate, never be overwritten)");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue(Files.isRegularFile(mca));
        assertTrue("Pumpkin shadow must survive startup", Files.isRegularFile(pumpkinShadow));
        assertEquals(pumpkinBytes, Files.size(pumpkinShadow));
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, detect(pumpkinShadow, 0, 1));
    }

    @Test
    public void bLinearStartupLeavesPumpkinInPlaceNoDelete() throws Exception {
        // F1: startup toward B_LINEAR must SKIP Pumpkin .linear sources (never queue,
        // never convert, never delete); remedy names explicit convert.
        Path region = temporaryFolder.newFolder("bstartup").toPath();
        Map<Long, byte[]> chunks = sampleChunks(2, 3);
        Path pumpkin = region.resolve("r.2.3.linear");
        writePumpkinFile(pumpkin, chunks, 2, 3, 2);
        long pumpkinBytes = Files.size(pumpkin);

        java.util.List<LinearRegionConverter.FileResult> seen = new java.util.ArrayList<>();
        LinearRegionConverter.Listener listener = new LinearRegionConverter.Listener() {
            @Override
            public void onFile(LinearRegionConverter.FileResult r) {
                seen.add(r);
            }
            @Override
            public void onRetry(Path source, int attempt, String reason) {
            }
        };
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderToFormat(region, 6, listener, false,
                RegionFileFormat.B_LINEAR);
        assertEquals(0, summary.converted());
        assertEquals(0, summary.failed());
        assertTrue("Pumpkin must survive B_LINEAR startup", Files.isRegularFile(pumpkin));
        assertEquals(pumpkinBytes, Files.size(pumpkin));
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, detect(pumpkin, 2, 3));
        assertFalse("no B_LINEAR output for skipped Pumpkin",
            Files.isRegularFile(region.resolve("r.2.3.b_linear")));
        boolean skippedPumpkin = false;
        for (LinearRegionConverter.FileResult r : seen) {
            if (r.source().equals(pumpkin)
                    && r.status() == LinearRegionConverter.Status.SKIPPED
                    && r.detail().contains("explicit")) {
                skippedPumpkin = true;
            }
        }
        assertTrue("Pumpkin source must report SKIPPED with explicit-convert remedy", skippedPumpkin);
    }

    @Test
    public void mcaToPumpkinSucceeds() throws Exception {
        Path region = temporaryFolder.newFolder("m2p").toPath();
        Map<Long, byte[]> expected = sampleChunks(0, 0);
        Path mca = region.resolve("r.0.0.mca");
        Files.write(mca, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        wireForwardMca(new java.util.concurrent.ConcurrentHashMap<>(expected));

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 0, null, false,
                LinearRegionConverter.ConvertFormat.MCA, LinearRegionConverter.ConvertFormat.PUMPKIN);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        assertFalse(Files.isRegularFile(mca));
        Path pumpkin = region.resolve("r.0.0.linear");
        assertTrue(Files.isRegularFile(pumpkin));
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, detect(pumpkin, 0, 0));
        assertPayloadsEqual(expected, readLinearPayloads(pumpkin, 0, 0));
    }

    @Test
    public void pumpkinToMcaSucceeds() throws Exception {
        Path region = temporaryFolder.newFolder("p2m").toPath();
        Map<Long, byte[]> expected = sampleChunks(4, 5);
        Path pumpkin = region.resolve("r.4.5.linear");
        writePumpkinFile(pumpkin, expected, 4, 5, 2);
        Map<Long, byte[]> mcaStore = new java.util.concurrent.ConcurrentHashMap<>();
        wireMcaSink(mcaStore);

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 0, null, false,
                LinearRegionConverter.ConvertFormat.PUMPKIN, LinearRegionConverter.ConvertFormat.MCA);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        assertFalse(Files.isRegularFile(pumpkin));
        assertTrue(Files.isRegularFile(region.resolve("r.4.5.mca")));
        assertPayloadsEqual(expected, mcaStore);
    }

    @Test
    public void pumpkinToV2Succeeds() throws Exception {
        Path region = temporaryFolder.newFolder("p2v2").toPath();
        Map<Long, byte[]> expected = sampleChunks(6, 7);
        Path file = region.resolve("r.6.7.linear");
        writePumpkinFile(file, expected, 6, 7, 2);
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, detect(file, 6, 7));

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 8, null, false,
                LinearRegionConverter.ConvertFormat.PUMPKIN, LinearRegionConverter.ConvertFormat.V2);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        assertEquals(RegionFormatDetector.DetectedFormat.V2, detect(file, 6, 7));
        assertPayloadsEqual(expected, readLinearPayloads(file, 6, 7));
    }

    @Test
    public void v2ToPumpkinSucceeds() throws Exception {
        Path region = temporaryFolder.newFolder("v22p").toPath();
        Map<Long, byte[]> expected = sampleChunks(7, 8);
        Path file = region.resolve("r.7.8.linear");
        writeV2File(file, expected);
        assertEquals(RegionFormatDetector.DetectedFormat.V2, detect(file, 7, 8));

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 0, null, false,
                LinearRegionConverter.ConvertFormat.V2, LinearRegionConverter.ConvertFormat.PUMPKIN);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, detect(file, 7, 8));
        assertPayloadsEqual(expected, readLinearPayloads(file, 7, 8));
    }

    @Test
    public void bLinearToPumpkinSucceeds() throws Exception {
        Path region = temporaryFolder.newFolder("b2p").toPath();
        Map<Long, byte[]> expected = sampleChunks(8, 9);
        Path blinear = region.resolve("r.8.9.b_linear");
        writeBLinearFile(blinear, expected);

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 0, null, false,
                LinearRegionConverter.ConvertFormat.B_LINEAR, LinearRegionConverter.ConvertFormat.PUMPKIN);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        assertFalse(Files.isRegularFile(blinear));
        Path pumpkin = region.resolve("r.8.9.linear");
        assertTrue(Files.isRegularFile(pumpkin));
        assertEquals(RegionFormatDetector.DetectedFormat.PUMPKIN, detect(pumpkin, 8, 9));
        assertPayloadsEqual(expected, readLinearPayloads(pumpkin, 8, 9));
    }

    @Test
    public void preambleWipesStalePumpkinTmp() throws Exception {
        Path region = temporaryFolder.newFolder("preamble").toPath();
        Path stale = region.resolve("r.1.1.linear.pumpkin-tmp");
        Files.write(stale, new byte[]{1, 2, 3});
        Map<Long, byte[]> chunks = sampleChunks(1, 1);
        Path v1 = region.resolve("r.1.1.linear");
        writeV1File(v1, chunks);
        // Live-file witnesses: the preamble wipe must only remove *.pumpkin-tmp.
        Path mcaWitness = region.resolve("r.2.2.mca");
        Files.write(mcaWitness, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        Path blinearWitness = region.resolve("r.3.3.b_linear");
        Files.write(blinearWitness, new byte[]{9, 9, 9});

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderExplicit(region, 6, 0, null, false,
                LinearRegionConverter.ConvertFormat.V1, LinearRegionConverter.ConvertFormat.V2);
        assertEquals(0, summary.failed());
        assertFalse("preamble must wipe stale *.pumpkin-tmp", Files.isRegularFile(stale));
        assertTrue("preamble must not touch live .mca files", Files.isRegularFile(mcaWitness));
        assertTrue("preamble must not touch live .b_linear files", Files.isRegularFile(blinearWitness));
    }

    private static void wireMcaSink(Map<Long, byte[]> store) {
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new FakeMcaFile(store);
            }
            return new LinearRegionFile(file, level);
        });
        LinearRegionConverter.linear$setChunkSinkOpener((file, folder, level) -> {
            return new FakeMcaSink(file, store);
        });
    }

    /** Captures anvil writes in memory; pads a dummy on-disk file for size checks. */
    private static final class FakeMcaSink implements LinearRegionConverter.ChunkSink {
        private final Path file;
        private final Map<Long, byte[]> store;

        FakeMcaSink(Path file, Map<Long, byte[]> store) {
            this.file = file;
            this.store = store;
        }

        @Override
        public void write(long chunk, ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.duplicate().get(copy);
            store.put(chunk, copy);
        }

        @Override
        public void flush() throws IOException {
            Files.createDirectories(file.getParent());
            if (!Files.isRegularFile(file) || Files.size(file) < LinearRegionConverter.MIN_ANVIL_SIZE) {
                Files.write(file, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
            }
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    /** In-memory stand-in for a vanilla .mca handle (no NMS, no IO). */
    private static final class FakeMcaFile implements AbstractRegionFile {
        private final Map<Long, byte[]> chunks;

        FakeMcaFile(Map<Long, byte[]> chunks) {
            this.chunks = new HashMap<>(chunks);
        }

        @Override
        public DataInputStream getChunkDataInputStream(long chunk) {
            byte[] payload = chunks.get(chunk);
            if (payload == null) {
                return null;
            }
            return new DataInputStream(new ByteArrayInputStream(payload.clone()));
        }

        @Override
        public void write(long chunk, ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.duplicate().get(copy);
            chunks.put(chunk, copy);
        }

        @Override
        public boolean hasChunk(long chunk) {
            return chunks.containsKey(chunk);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isMarkedToSave() {
            return false;
        }

        @Override
        public void clearMarkedToSave() {
        }
    }
}
