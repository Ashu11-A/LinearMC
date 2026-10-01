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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Reverse ({@code .linear} -&gt; {@code .mca}) pipeline through the
 * {@link LinearRegionConverter#linear$setRegionOpener} and
 * {@link LinearRegionConverter#linear$setChunkSinkOpener} seams (no NMS).
 *
 * <p>Sources are hand-built with the real {@link LinearRegionFile}; anvil
 * targets are in-memory fakes (writes captured by a fake {@code ChunkSink},
 * reads served by a fake {@code RegionOpener} for {@code .mca} files).
 * Same package ({@code net.linear}) so package-visible helpers are reachable.
 */
public class ConverterReverseTest {

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

    @Test
    public void roundTripLinearToMcaReadBackPayloadEquality() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.1.2.mca".replace(".mca", ".linear"));
        long base0 = ChunkKey.of(1 * 32, 2 * 32);
        long base1 = ChunkKey.of(1 * 32 + 1, 2 * 32);
        byte[] payload0 = new byte[]{1, 2, 3};
        byte[] payload1 = new byte[]{4, 5, 6, 7};
        try (LinearRegionFile src = new LinearRegionFile(source, 6)) {
            src.write(base0, ByteBuffer.wrap(payload0));
            src.write(base1, ByteBuffer.wrap(payload1));
            src.flush();
        }

        Map<Long, byte[]> mcaChunks = new ConcurrentHashMap<>();
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireFakes(mcaChunks, sinkOpens);

        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderReverse(region, 6, events);

        assertEquals(1, summary.converted());
        assertEquals(1, summary.validated());
        assertEquals(1, summary.deleted());
        assertEquals(0, summary.failed());
        assertFalse(Files.isRegularFile(source));
        Path target = region.resolve("r.1.2.mca");
        assertTrue(Files.isRegularFile(target));
        assertTrue(Files.size(target) >= LinearRegionConverter.MIN_ANVIL_SIZE);
        assertEquals(1, sinkOpens.get());
        assertArrayEquals(payload0, mcaChunks.get(base0));
        assertArrayEquals(payload1, mcaChunks.get(base1));
        // Read-back through the fake anvil handle drains identical bytes.
        try (AbstractRegionFile readBack = new FakeMcaFile(mcaChunks)) {
            try (DataInputStream in = readBack.getChunkDataInputStream(base0)) {
                assertArrayEquals(payload0, in.readAllBytes());
            }
            try (DataInputStream in = readBack.getChunkDataInputStream(base1)) {
                assertArrayEquals(payload1, in.readAllBytes());
            }
        }
    }

    @Test
    public void emptyLinearYieldsValidEmptyTarget() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.0.0.linear");
        long dummy = ChunkKey.of(0, 0);
        try (LinearRegionFile src = new LinearRegionFile(source, 6)) {
            src.write(dummy, ByteBuffer.wrap(new byte[]{0}));
            src.flush();
            src.clear(dummy);
            src.flush();
        }
        assertTrue(Files.isRegularFile(source));

        Map<Long, byte[]> mcaChunks = new ConcurrentHashMap<>();
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireFakes(mcaChunks, sinkOpens);

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderReverse(region, 6, null);

        assertEquals(1, summary.deleted());
        assertEquals(0, summary.failed());
        assertFalse(Files.isRegularFile(source));
        Path target = region.resolve("r.0.0.mca");
        assertTrue(Files.isRegularFile(target));
        assertTrue(Files.size(target) >= LinearRegionConverter.MIN_ANVIL_SIZE);
        assertTrue(mcaChunks.isEmpty());
        assertEquals(1, sinkOpens.get());
    }

    @Test
    public void corruptLinearEntersProtectionAndKeepsSource() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.0.0.linear");
        byte[] garbage = new byte[100];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = 0x55;
        }
        Files.write(source, garbage);

        Map<Long, byte[]> mcaChunks = new ConcurrentHashMap<>();
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireFakes(mcaChunks, sinkOpens);

        RecordingListener events = new RecordingListener();
        try {
            LinearRegionConverter.convertRegionFolderReverse(region, 6, events);
            fail("expected ConversionProtectionException");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue(Files.isRegularFile(source));
        assertFalse(Files.isRegularFile(region.resolve("r.0.0.mca")));
        assertEquals(2, events.retries.size());
    }

    @Test
    public void validShadowEscalatesWithNeitherSideDeleted() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.3.4.linear");
        long base = ChunkKey.of(3 * 32, 4 * 32);
        byte[] payload = new byte[]{9, 8, 7};
        try (LinearRegionFile src = new LinearRegionFile(source, 6)) {
            src.write(base, ByteBuffer.wrap(payload));
            src.flush();
        }
        // Pre-existing valid anvil shadow: same chunks, padded on-disk file.
        Map<Long, byte[]> mcaChunks = new ConcurrentHashMap<>();
        mcaChunks.put(base, payload.clone());
        Path shadow = region.resolve("r.3.4.mca");
        Files.write(shadow, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);

        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireFakes(mcaChunks, sinkOpens);

        RecordingListener events = new RecordingListener();
        try {
            LinearRegionConverter.convertRegionFolderReverse(region, 6, events);
            fail("expected ConversionProtectionException for valid reverse shadow pair");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        // Mirror of the forward path: NEITHER side deleted, no rewrite.
        assertTrue(Files.isRegularFile(source));
        assertTrue(Files.isRegularFile(shadow));
        assertEquals(0, sinkOpens.get());
    }

    @Test
    public void skipPredicateReportsSkippedAndKeepsSource() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.0.1.linear");
        long base = ChunkKey.of(0, 1 * 32);
        try (LinearRegionFile src = new LinearRegionFile(source, 6)) {
            src.write(base, ByteBuffer.wrap(new byte[]{1}));
            src.flush();
        }

        Map<Long, byte[]> mcaChunks = new ConcurrentHashMap<>();
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireFakes(mcaChunks, sinkOpens);
        LinearRegionConverter.linear$setReverseSkipPredicate(p -> true);

        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderReverse(region, 6, events);

        assertEquals(0, summary.converted());
        assertEquals(0, summary.deleted());
        assertEquals(0, summary.failed());
        assertTrue(Files.isRegularFile(source));
        assertFalse(Files.isRegularFile(region.resolve("r.0.1.mca")));
        assertEquals(0, sinkOpens.get());
        boolean sawSkipped = false;
        for (LinearRegionConverter.FileResult r : events.files) {
            if (r.status() == LinearRegionConverter.Status.SKIPPED) {
                sawSkipped = true;
            }
        }
        assertTrue(sawSkipped);
    }

    @Test
    public void invalidShadowRequeuesForFullConvert() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.5.6.linear");
        long base = ChunkKey.of(5 * 32, 6 * 32);
        byte[] payload = new byte[]{11, 22};
        try (LinearRegionFile src = new LinearRegionFile(source, 6)) {
            src.write(base, ByteBuffer.wrap(payload));
            src.flush();
        }
        // Invalid shadow: padded on-disk file but zero chunks (count mismatch).
        Map<Long, byte[]> mcaChunks = new ConcurrentHashMap<>();
        Path shadow = region.resolve("r.5.6.mca");
        Files.write(shadow, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireFakes(mcaChunks, sinkOpens);

        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderReverse(region, 6, null);

        assertEquals(1, summary.deleted());
        assertEquals(0, summary.failed());
        assertFalse(Files.isRegularFile(source));
        assertTrue(Files.isRegularFile(region.resolve("r.5.6.mca")));
        assertArrayEquals(payload, mcaChunks.get(base));
        assertEquals(1, sinkOpens.get());
    }

    @Test
    public void corruptSourceWithShadowEscalatesWithBothKept() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.7.8.linear");
        byte[] garbage = new byte[100];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = 0x55;
        }
        Files.write(source, garbage);
        Path shadow = region.resolve("r.7.8.mca");
        Files.write(shadow, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);

        Map<Long, byte[]> mcaChunks = new ConcurrentHashMap<>();
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireFakes(mcaChunks, sinkOpens);

        try {
            LinearRegionConverter.convertRegionFolderReverse(region, 6, null);
            fail("expected ConversionProtectionException for corrupt source + shadow");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue(Files.isRegularFile(source));
        assertTrue(Files.isRegularFile(shadow));
    }

    @Test
    public void reverseMismatchAndTornPayloadFailValidation() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.9.9.linear");
        long base = ChunkKey.of(9 * 32, 9 * 32);
        long extra = ChunkKey.of(9 * 32 + 1, 9 * 32);
        byte[] payload = new byte[]{3, 1};
        try (LinearRegionFile src = new LinearRegionFile(source, 6)) {
            src.write(base, ByteBuffer.wrap(payload));
            src.flush();
        }
        // Mismatch: target holds an extra chunk.
        Map<Long, byte[]> mismatch = new ConcurrentHashMap<>();
        mismatch.put(base, payload.clone());
        mismatch.put(extra, new byte[]{9});
        final Map<Long, byte[]> mismatchView = mismatch;
        Path shadow = region.resolve("r.9.9.mca");
        Files.write(shadow, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new FakeMcaFile(mismatchView);
            }
            return new LinearRegionFile(file, level);
        });
        try {
            LinearRegionConverter.validateReversePair(source, shadow);
            fail("expected chunk-count mismatch");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("mismatch"));
        }

        // Torn payload: target chunk present but empty.
        Map<Long, byte[]> torn = new ConcurrentHashMap<>();
        torn.put(base, new byte[0]);
        final Map<Long, byte[]> tornView = torn;
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new FakeMcaFile(tornView);
            }
            return new LinearRegionFile(file, level);
        });
        try {
            LinearRegionConverter.validateReversePair(source, shadow);
            fail("expected torn-payload failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("torn")
                || expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("empty"));
        }
    }

    @Test
    public void throwingSkipPredicateFailsClosedAsSkipped() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.2.2.linear");
        long base = ChunkKey.of(2 * 32, 2 * 32);
        try (LinearRegionFile src = new LinearRegionFile(source, 6)) {
            src.write(base, ByteBuffer.wrap(new byte[]{7}));
            src.flush();
        }
        Map<Long, byte[]> mcaChunks = new ConcurrentHashMap<>();
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireFakes(mcaChunks, sinkOpens);
        LinearRegionConverter.linear$setReverseSkipPredicate(p -> {
            throw new RuntimeException("predicate boom");
        });

        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderReverse(region, 6, events);

        assertEquals(0, summary.failed());
        assertEquals(0, summary.deleted());
        assertTrue(Files.isRegularFile(source));
        assertFalse(Files.isRegularFile(region.resolve("r.2.2.mca")));
        assertEquals(0, sinkOpens.get());
        boolean sawSkipped = false;
        for (LinearRegionConverter.FileResult r : events.files) {
            if (r.status() == LinearRegionConverter.Status.SKIPPED) {
                sawSkipped = true;
            }
        }
        assertTrue(sawSkipped);
    }

    @Test
    public void reversePreDeleteRecheckDefersRedirtiedFile() throws Exception {
        Path region = temporaryFolder.newFolder("revrecheck").toPath();
        Path source = region.resolve("r.2.3.linear");
        long base0 = ChunkKey.of(2 * 32, 3 * 32);
        long base1 = ChunkKey.of(2 * 32 + 1, 3 * 32);
        try (LinearRegionFile src = new LinearRegionFile(source, 6)) {
            src.write(base0, ByteBuffer.wrap(new byte[]{1, 2, 3}));
            src.write(base1, ByteBuffer.wrap(new byte[]{4, 5}));
            src.flush();
        }

        Map<Long, byte[]> mcaChunks = new ConcurrentHashMap<>();
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireFakes(mcaChunks, sinkOpens);
        // Clean at collection (first test), dirty by delete time: the source
        // must survive and no committed target may be left behind.
        AtomicInteger tests = new AtomicInteger(0);
        LinearRegionConverter.linear$setReverseSkipPredicate(
            p -> tests.getAndIncrement() > 0);

        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderReverse(region, 6, events);

        assertEquals("re-dirtied file must not convert", 0, summary.converted());
        assertEquals(0, summary.deleted());
        assertEquals(0, summary.failed());
        assertTrue("deferred source must survive", Files.isRegularFile(source));
        assertFalse("deferred file must leave no committed target behind",
            Files.isRegularFile(region.resolve("r.2.3.mca")));
        boolean sawDefer = false;
        for (LinearRegionConverter.FileResult r : events.files) {
            if (r.status() == LinearRegionConverter.Status.SKIPPED
                && r.detail() != null && r.detail().contains("deferred before delete")) {
                sawDefer = true;
            }
        }
        assertTrue("defer-before-delete SKIPPED must be reported", sawDefer);
    }

    @Test
    public void preExistingValidTargetDirtyAtDeleteKeepsBothAsSkipped() throws Exception {
        // TOCTOU race simulation: a valid .mca target lands between the
        // collection listing snapshot and the file task (production: a
        // concurrent writer; here: the skip predicate's own first call, which
        // runs after the listing is already consumed into maps, so the file
        // still queues). The task must see the valid target, skip its own
        // rewrite, then defer on the dirty-at-delete recheck with BOTH files
        // surviving as SKIPPED: a pre-existing committed target is someone
        // else's file and must never be wiped (only this attempt's own
        // staging output may go).
        Path region = temporaryFolder.newFolder("revpreshadow").toPath();
        Path source = region.resolve("r.2.4.linear");
        Path shadow = region.resolve("r.2.4.mca");
        long base0 = ChunkKey.of(2 * 32, 4 * 32);
        long base1 = ChunkKey.of(2 * 32 + 1, 4 * 32);
        byte[] payload0 = new byte[]{1, 2, 3};
        byte[] payload1 = new byte[]{4, 5};
        try (LinearRegionFile src = new LinearRegionFile(source, 6)) {
            src.write(base0, ByteBuffer.wrap(payload0));
            src.write(base1, ByteBuffer.wrap(payload1));
            src.flush();
        }

        Map<Long, byte[]> mcaChunks = new ConcurrentHashMap<>();
        mcaChunks.put(base0, payload0.clone());
        mcaChunks.put(base1, payload1.clone());
        AtomicInteger sinkOpens = new AtomicInteger(0);
        wireFakes(mcaChunks, sinkOpens);
        // Call 1 (collection, after the listing snapshot): land the valid
        // shadow on disk, report clean so the file queues. Call 2 (task
        // pre-delete recheck): report dirty so the delete defers.
        AtomicInteger tests = new AtomicInteger(0);
        LinearRegionConverter.linear$setReverseSkipPredicate(p -> {
            if (tests.getAndIncrement() == 0) {
                try {
                    Files.write(shadow, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
                } catch (IOException e) {
                    throw new RuntimeException("cannot land race shadow: " + e.getMessage(), e);
                }
                return false;
            }
            return true;
        });

        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolderReverse(region, 6, events);

        assertEquals("re-dirtied file must not convert", 0, summary.converted());
        assertEquals(0, summary.deleted());
        assertEquals(0, summary.failed());
        assertTrue("deferred source must survive", Files.isRegularFile(source));
        assertTrue("pre-existing valid target must survive the defer", Files.isRegularFile(shadow));
        assertEquals("pre-existing valid target must not be rewritten", 0, sinkOpens.get());
        boolean sawDefer = false;
        for (LinearRegionConverter.FileResult r : events.files) {
            if (r.status() == LinearRegionConverter.Status.SKIPPED
                && r.detail() != null && r.detail().contains("deferred before delete")) {
                sawDefer = true;
            }
        }
        assertTrue("defer-before-delete SKIPPED must be reported", sawDefer);
    }

    @Test
    public void tornLinearSourceFailsClosedBeforeCounting() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.4.4.linear");
        // Nonzero but below the header+footer floor: torn, never counted.
        Files.write(source, new byte[10]);
        Path target = region.resolve("r.4.4.mca");
        try {
            LinearRegionConverter.convertSingleFileReverse(source, target, 6);
            fail("expected torn-source failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("too small"));
        }
        assertTrue(Files.isRegularFile(source));
        assertFalse(Files.isRegularFile(target));
    }

    private static void wireFakes(Map<Long, byte[]> mcaChunks, AtomicInteger sinkOpens) {
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
    }
}
