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
 * Converter properties through the opener/sink seams (no NMS).
 *
 * <ul>
 *   <li>Mirror: forward ({@code .mca -> .linear}) then reverse
 *       ({@code .linear -> .mca}) with a fake opener + fake sink and an
 *       in-memory {@code .mca} model returns byte-identical payloads.</li>
 *   <li>Shadow pairs: a VALID shadow is never deleted in either direction
 *       (both files survive, protection escalates, no rewrite happens).</li>
 *   <li>Retry exhaustion: 3 failed attempts raise protection with the source
 *       intact, no target left behind and exactly 2 retry callbacks.</li>
 * </ul>
 */
public class ConverterMirrorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void restoreDefaultSeams() {
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (format == null || format == RegionFileFormat.LINEAR) {
                return new LinearRegionFile(file, level);
            }
            throw new IOException("No RegionOpener registered for non-LINEAR open: " + file);
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

    /** Forward seam: .mca reads come from the model, .linear is the real file. */
    private static void wireForwardMca(Map<Long, byte[]> mcaChunks) {
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new FakeMcaFile(mcaChunks);
            }
            return new LinearRegionFile(file, level);
        });
    }

    /** Reverse seam: .linear reads are real files, .mca reads/writes hit the model. */
    private static void wireReverseMca(Map<Long, byte[]> mcaChunks, AtomicInteger sinkOpens) {
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new FakeMcaFile(mcaChunks);
            }
            return new LinearRegionFile(file, level);
        });
        LinearRegionConverter.linear$setChunkSinkOpener((file, folder, level) -> {
            sinkOpens.incrementAndGet();
            return new FakeSink(file, mcaChunks);
        });
    }

    @Test
    public void forwardThenReverseReturnsIdenticalPayloads() throws Exception {
        Path region = temporaryFolder.newFolder("mirror").toPath();
        Path source = region.resolve("r.2.3.mca");
        Files.write(source, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        Map<Long, byte[]> model = new ConcurrentHashMap<>(sampleChunks(2, 3));
        Map<Long, byte[]> expected = new HashMap<>();
        for (Map.Entry<Long, byte[]> e : model.entrySet()) {
            expected.put(e.getKey(), e.getValue().clone());
        }
        wireForwardMca(model);

        RecordingListener forwardEvents = new RecordingListener();
        LinearRegionConverter.ConversionSummary forward =
            LinearRegionConverter.convertRegionFolder(region, 6, forwardEvents);
        assertEquals(1, forward.converted());
        assertEquals(1, forward.deleted());
        assertEquals(0, forward.failed());
        assertFalse(Files.isRegularFile(source));
        Path linear = region.resolve("r.2.3.linear");
        assertTrue(Files.isRegularFile(linear));

        // Reverse leg into a FRESH model: equality is against the original bytes.
        Map<Long, byte[]> back = new ConcurrentHashMap<>();
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireReverseMca(back, sinkOpens);
        RecordingListener reverseEvents = new RecordingListener();
        LinearRegionConverter.ConversionSummary reverse =
            LinearRegionConverter.convertRegionFolderReverse(region, 6, reverseEvents);
        assertEquals(1, reverse.converted());
        assertEquals(1, reverse.deleted());
        assertEquals(0, reverse.failed());
        assertFalse(Files.isRegularFile(linear));
        assertEquals(1, sinkOpens.get());
        assertEquals(expected.size(), back.size());
        for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
            assertArrayEquals("chunk " + ChunkKey.format(e.getKey()), e.getValue(), back.get(e.getKey()));
        }
    }

    @Test
    public void forwardValidShadowNeverDeletesEitherSide() throws Exception {
        Path region = temporaryFolder.newFolder("fwshadow").toPath();
        Path source = region.resolve("r.4.5.mca");
        Files.write(source, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        Map<Long, byte[]> chunks = new ConcurrentHashMap<>(sampleChunks(4, 5));
        wireForwardMca(chunks);
        // Materialize the shadow by converting once into a scratch folder is
        // overkill: write the same chunks straight to the shadow file.
        Path shadow = region.resolve("r.4.5.linear");
        try (LinearRegionFile dst = new LinearRegionFile(shadow, 6)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                dst.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            dst.flush();
        }

        try {
            LinearRegionConverter.convertRegionFolder(region, 6, null);
            fail("expected ConversionProtectionException for valid forward shadow pair");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue("source must survive", Files.isRegularFile(source));
        assertTrue("valid shadow must survive", Files.isRegularFile(shadow));
    }

    @Test
    public void reverseValidShadowNeverDeletesEitherSide() throws Exception {
        Path region = temporaryFolder.newFolder("revshadow").toPath();
        Path source = region.resolve("r.6.7.linear");
        Map<Long, byte[]> chunks = new ConcurrentHashMap<>(sampleChunks(6, 7));
        try (LinearRegionFile src = new LinearRegionFile(source, 6)) {
            for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
                src.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            src.flush();
        }
        Path shadow = region.resolve("r.6.7.mca");
        Files.write(shadow, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireReverseMca(new ConcurrentHashMap<>(chunks), sinkOpens);

        try {
            LinearRegionConverter.convertRegionFolderReverse(region, 6, null);
            fail("expected ConversionProtectionException for valid reverse shadow pair");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue("source must survive", Files.isRegularFile(source));
        assertTrue("valid shadow must survive", Files.isRegularFile(shadow));
        assertEquals("valid shadow must not be rewritten", 0, sinkOpens.get());
    }

    @Test
    public void forwardRetryExhaustionKeepsSourceAndReportsTwice() throws Exception {
        Path region = temporaryFolder.newFolder("fwretry").toPath();
        Path source = region.resolve("r.0.0.mca");
        byte[] truncated = new byte[100];
        java.util.Arrays.fill(truncated, (byte) 0x55);
        Files.write(source, truncated);
        RecordingListener events = new RecordingListener();
        try {
            LinearRegionConverter.convertRegionFolder(region, 6, events);
            fail("expected ConversionProtectionException");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue("source intact after exhaustion", Files.isRegularFile(source));
        assertFalse(Files.isRegularFile(region.resolve("r.0.0.linear")));
        assertEquals(2, events.retries.size());
    }

    @Test
    public void forwardSkipPredicateDefersAndKeepsSource() throws Exception {
        Path region = temporaryFolder.newFolder("fwskip").toPath();
        Path source = region.resolve("r.0.1.mca");
        Files.write(source, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        wireForwardMca(new ConcurrentHashMap<>(sampleChunks(0, 1)));
        LinearRegionConverter.linear$setForwardSkipPredicate(p -> true);

        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolder(region, 6, events);

        assertEquals(0, summary.converted());
        assertEquals(0, summary.deleted());
        assertEquals(0, summary.failed());
        assertTrue("skipped source must survive", Files.isRegularFile(source));
        assertFalse("skipped file must leave no target",
            Files.isRegularFile(region.resolve("r.0.1.linear")));
        assertTrue("SKIPPED must be reported", events.sawStatus(
            LinearRegionConverter.Status.SKIPPED));
    }

    @Test
    public void forwardSkipPredicateThrowDefersClosed() throws Exception {
        Path region = temporaryFolder.newFolder("fwskipthrow").toPath();
        Path source = region.resolve("r.0.2.mca");
        Files.write(source, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        wireForwardMca(new ConcurrentHashMap<>(sampleChunks(0, 2)));
        LinearRegionConverter.linear$setForwardSkipPredicate(p -> {
            throw new RuntimeException("predicate boom");
        });

        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolder(region, 6, events);

        assertEquals(0, summary.converted());
        assertEquals(0, summary.deleted());
        assertEquals(0, summary.failed());
        assertTrue("source must survive a throwing skip check", Files.isRegularFile(source));
        assertFalse(Files.isRegularFile(region.resolve("r.0.2.linear")));
        assertTrue(events.sawStatus(LinearRegionConverter.Status.SKIPPED));
    }

    @Test
    public void forwardPreDeleteRecheckDefersRedirtiedFile() throws Exception {
        Path region = temporaryFolder.newFolder("fwrecheck").toPath();
        Path source = region.resolve("r.0.3.mca");
        Files.write(source, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        wireForwardMca(new ConcurrentHashMap<>(sampleChunks(0, 3)));
        // Clean at collection (first test), dirty by delete time: the file
        // must defer instead of being deleted from under the live writer.
        AtomicInteger tests = new AtomicInteger(0);
        LinearRegionConverter.linear$setForwardSkipPredicate(
            p -> tests.getAndIncrement() > 0);

        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolder(region, 6, events);

        assertEquals("re-dirtied file must not convert", 0, summary.converted());
        assertEquals(0, summary.deleted());
        assertEquals(0, summary.failed());
        assertTrue("deferred source must survive", Files.isRegularFile(source));
        assertFalse("deferred file must leave no committed target behind",
            Files.isRegularFile(region.resolve("r.0.3.linear")));
        assertTrue("defer-before-delete SKIPPED must be reported",
            events.sawDetail("deferred before delete"));
    }

    @Test
    public void reverseRetryExhaustionKeepsSourceAndReportsTwice() throws Exception {
        Path region = temporaryFolder.newFolder("revretry").toPath();
        Path source = region.resolve("r.0.0.linear");
        byte[] garbage = new byte[100];
        java.util.Arrays.fill(garbage, (byte) 0x55);
        Files.write(source, garbage);
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireReverseMca(new ConcurrentHashMap<>(), sinkOpens);
        RecordingListener events = new RecordingListener();
        try {
            LinearRegionConverter.convertRegionFolderReverse(region, 6, events);
            fail("expected ConversionProtectionException");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue("source intact after exhaustion", Files.isRegularFile(source));
        assertFalse(Files.isRegularFile(region.resolve("r.0.0.mca")));
        assertEquals(2, events.retries.size());
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

    /** Captures reverse writes in memory; pads a dummy on-disk file for size checks. */
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

    /** Thread-safe event sink (workers call back from pool threads). */
    private static final class RecordingListener implements LinearRegionConverter.Listener {
        final List<LinearRegionConverter.FileResult> files =
            Collections.synchronizedList(new ArrayList<>());
        final List<String> retries =
            Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onFile(LinearRegionConverter.FileResult r) {
            files.add(r);
        }

        @Override
        public void onRetry(Path source, int attempt, String reason) {
            retries.add(source + "#" + attempt + ":" + reason);
        }

        boolean sawStatus(LinearRegionConverter.Status status) {
            synchronized (files) {
                for (LinearRegionConverter.FileResult r : files) {
                    if (r.status() == status) {
                        return true;
                    }
                }
            }
            return false;
        }

        boolean sawDetail(String fragment) {
            synchronized (files) {
                for (LinearRegionConverter.FileResult r : files) {
                    if (r.detail() != null && r.detail().contains(fragment)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
