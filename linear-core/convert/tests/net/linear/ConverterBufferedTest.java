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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Buffered-tier conversion: {@code .mca <-> .b_linear} and
 * {@code .linear <-> .b_linear} through the validate-before-delete
 * pipeline, with fake NMS seams mirroring {@code ConverterMirrorTest}.
 */
public class ConverterBufferedTest {

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
        Random random = new Random(0xB11A4);
        for (int i = 0; i < 6; i++) {
            byte[] payload = new byte[64 + i * 37];
            random.nextBytes(payload);
            chunks.put(ChunkKey.of(regionX * 32 + i, regionZ * 32 + (i % 4)), payload);
        }
        return chunks;
    }

    private static void wireForwardMca(Map<Long, byte[]> mcaChunks) {
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new FakeMcaFile(mcaChunks);
            }
            String name = file.getFileName().toString();
            if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                return new BufferedLinearRegionFile(file, level);
            }
            return new LinearRegionFile(file, level);
        });
    }

    private static void wireReverseMca(Map<Long, byte[]> mcaChunks, AtomicInteger sinkOpens) {
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new FakeMcaFile(mcaChunks);
            }
            String name = file.getFileName().toString();
            if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                return new BufferedLinearRegionFile(file, level);
            }
            return new LinearRegionFile(file, level);
        });
        LinearRegionConverter.linear$setChunkSinkOpener((file, folder, level) -> {
            sinkOpens.incrementAndGet();
            return new FakeSink(file, mcaChunks);
        });
    }

    @Test
    public void forwardMcaToBlinear() throws Exception {
        Path region = temporaryFolder.newFolder("fwb").toPath();
        Path source = region.resolve("r.2.3.mca");
        Files.write(source, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        Map<Long, byte[]> model = new ConcurrentHashMap<>(sampleChunks(2, 3));
        wireForwardMca(model);

        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary = LinearRegionConverter.convertRegionFolderToFormat(
            region, 6, events, true, RegionFileFormat.B_LINEAR);
        assertEquals(1, summary.converted());
        assertEquals(1, summary.deleted());
        assertEquals(0, summary.failed());
        assertFalse(Files.isRegularFile(source));
        Path target = region.resolve("r.2.3.b_linear");
        assertTrue(Files.isRegularFile(target));
        // Finished B_LINEAR targets ship master-only (WAL pruned after
        // validation); the next open rebuilds swap state from the master.
        assertFalse(Files.isRegularFile(target.resolveSibling("r.2.3.b_linear.swp")));
        restoreDefaultSeams();
        try (BufferedLinearRegionFile read = new BufferedLinearRegionFile(target, 6)) {
            for (Map.Entry<Long, byte[]> e : model.entrySet()) {
                DataInputStream in = read.getChunkDataInputStream(e.getKey());
                assertTrue(in != null);
                assertArrayEquals(e.getValue(), in.readAllBytes());
                in.close();
            }
        }
    }

    @Test
    public void forwardBlinearToLinearUnion() throws Exception {
        Path region = temporaryFolder.newFolder("blunion").toPath();
        Map<Long, byte[]> chunks = sampleChunks(1, 1);
        Path source = region.resolve("r.1.1.b_linear");
        try (BufferedLinearRegionFile dst = new BufferedLinearRegionFile(source, 6)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                dst.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            dst.flush();
        }
        wireForwardMca(new ConcurrentHashMap<>());

        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolder(region, 6, events);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        assertFalse(Files.isRegularFile(source));
        assertFalse(Files.isRegularFile(source.resolveSibling("r.1.1.b_linear.swp")));
        Path target = region.resolve("r.1.1.linear");
        assertTrue(Files.isRegularFile(target));
        restoreDefaultSeams();
        try (LinearRegionFile read = new LinearRegionFile(target, 6)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                DataInputStream in = read.getChunkDataInputStream(e.getKey());
                assertTrue(in != null);
                assertArrayEquals(e.getValue(), in.readAllBytes());
                in.close();
            }
        }
    }

    @Test
    public void reverseBlinearToMca() throws Exception {
        Path region = temporaryFolder.newFolder("revb").toPath();
        Map<Long, byte[]> chunks = sampleChunks(5, 5);
        Path source = region.resolve("r.5.5.b_linear");
        try (BufferedLinearRegionFile src = new BufferedLinearRegionFile(source, 6)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                src.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            src.flush();
        }
        Map<Long, byte[]> back = new ConcurrentHashMap<>();
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireReverseMca(back, sinkOpens);

        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderReverse(region, 6, events);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        assertEquals(1, sinkOpens.get());
        assertFalse(Files.isRegularFile(source));
        assertEquals(chunks.size(), back.size());
        for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
            assertArrayEquals(e.getValue(), back.get(e.getKey()));
        }
    }

    @Test
    public void blinearShadowEscalates() throws Exception {
        Path region = temporaryFolder.newFolder("bshadow").toPath();
        Path source = region.resolve("r.4.5.mca");
        Files.write(source, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        Map<Long, byte[]> chunks = new ConcurrentHashMap<>(sampleChunks(4, 5));
        wireForwardMca(chunks);
        Path shadow = region.resolve("r.4.5.b_linear");
        try (BufferedLinearRegionFile dst = new BufferedLinearRegionFile(shadow, 6)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                dst.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            dst.flush();
        }
        try {
            LinearRegionConverter.convertRegionFolderToFormat(region, 6, null, true, RegionFileFormat.B_LINEAR);
            fail("expected ConversionProtectionException for valid buffered shadow pair");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue("source must survive", Files.isRegularFile(source));
        assertTrue("valid shadow must survive", Files.isRegularFile(shadow));
    }

    @Test
    public void emptyBlinearSourceMaterializes() throws Exception {
        Path region = temporaryFolder.newFolder("bempty").toPath();
        Path source = region.resolve("r.9.9.b_linear");
        try (BufferedLinearRegionFile empty = new BufferedLinearRegionFile(source, 6)) {
            empty.flush();
        }
        // An untouched buffered file has no master yet: seed one chunk, then
        // clear it, so the source is a valid empty master.
        try (BufferedLinearRegionFile seed = new BufferedLinearRegionFile(source, 6)) {
            long dummy = ChunkKey.of(9 * 32, 9 * 32);
            seed.write(dummy, ByteBuffer.wrap(new byte[] {1}));
            seed.flush();
            seed.clear(dummy);
            seed.flush();
        }
        wireForwardMca(new ConcurrentHashMap<>());
        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderToFormat(region, 6, events, true, RegionFileFormat.LINEAR);
        assertEquals(1, summary.converted());
        assertEquals(0, summary.failed());
        assertTrue(Files.isRegularFile(region.resolve("r.9.9.linear")));
    }

    @Test
    public void mixedPairEscalatesForward() throws Exception {
        Path region = temporaryFolder.newFolder("mixfwd").toPath();
        Path mca = region.resolve("r.0.0.mca");
        Files.write(mca, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        Map<Long, byte[]> chunks = new ConcurrentHashMap<>(sampleChunks(0, 0));
        wireForwardMca(chunks);
        Path blin = region.resolve("r.0.0.b_linear");
        try (BufferedLinearRegionFile dst = new BufferedLinearRegionFile(blin, 6)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                dst.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            dst.flush();
        }
        try {
            LinearRegionConverter.convertRegionFolder(region, 6, null);
            fail("expected ConversionProtectionException for ambiguous same-coordinates sources");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue("both sources must survive", Files.isRegularFile(mca));
        assertTrue("both sources must survive", Files.isRegularFile(blin));
        assertTrue("no target may appear",
            !Files.isRegularFile(region.resolve("r.0.0.linear")));
    }

    @Test
    public void mixedPairEscalatesReverse() throws Exception {
        Path region = temporaryFolder.newFolder("mixrev").toPath();
        Map<Long, byte[]> chunks = sampleChunks(0, 0);
        Path lin = region.resolve("r.0.0.linear");
        try (LinearRegionFile src = new LinearRegionFile(lin, 6)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                src.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            src.flush();
        }
        Path blin = region.resolve("r.0.0.b_linear");
        try (BufferedLinearRegionFile dst = new BufferedLinearRegionFile(blin, 6)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                dst.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            dst.flush();
        }
        wireReverseMca(new ConcurrentHashMap<>(), new AtomicInteger(0));
        try {
            LinearRegionConverter.convertRegionFolderReverse(region, 6, null);
            fail("expected ConversionProtectionException for ambiguous reverse sources");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue(Files.isRegularFile(lin));
        assertTrue(Files.isRegularFile(blin));
    }

    @Test
    public void targetForSourceCoversAllFormats() {
        Path mca = Path.of("r.1.2.mca");
        assertEquals("r.1.2.linear",
            LinearRegionConverter.targetForSource(mca, RegionFileFormat.LINEAR).getFileName().toString());
        assertEquals("r.1.2.b_linear",
            LinearRegionConverter.targetForSource(mca, RegionFileFormat.B_LINEAR).getFileName().toString());
        Path lin = Path.of("r.1.2.linear");
        assertEquals("r.1.2.b_linear",
            LinearRegionConverter.targetForSource(lin, RegionFileFormat.B_LINEAR).getFileName().toString());
        Path blin = Path.of("r.1.2.b_linear");
        assertEquals("r.1.2.mca",
            LinearRegionConverter.targetForSourceReverse(blin).getFileName().toString());
        assertEquals("r.1.2.mca",
            LinearRegionConverter.targetForSourceReverse(lin).getFileName().toString());
    }

    @Test
    public void bufferedHeaderSanity() throws Exception {
        Path dir = temporaryFolder.newFolder("bsane").toPath();
        Path good = dir.resolve("r.0.0.b_linear");
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(good, 6)) {
            region.write(ChunkKey.of(0, 0), ByteBuffer.wrap(new byte[] {7}));
            region.flush();
        }
        LinearRegionConverter.checkHeaderSanity(good);
        Path bad = dir.resolve("r.0.1.b_linear");
        Files.write(bad, new byte[64]);
        try {
            LinearRegionConverter.checkHeaderSanity(bad);
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

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

    private static final class FakeSink implements LinearRegionConverter.ChunkSink {
        private final Path file;
        private final Map<Long, byte[]> store;

        FakeSink(Path file, Map<Long, byte[]> store) {
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

    private static final class RecordingListener implements LinearRegionConverter.Listener {
        final List<LinearRegionConverter.FileResult> files =
            Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onFile(LinearRegionConverter.FileResult r) {
            files.add(r);
        }

        @Override
        public void onRetry(Path source, int attempt, String reason) {
        }
    }
}
