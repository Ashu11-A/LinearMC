package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pure-helper validation for the conversion pipeline plus observable
 * {@link LinearRegionConverter#convertRegionFolder} behavior through the
 * {@link LinearRegionConverter#linear$setRegionOpener} seam (no NMS).
 *
 * <p>Same package ({@code net.linear}) so package-visible helpers are
 * reachable without widening production visibility.
 */
public class ConverterValidationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void restoreDefaultOpener() {
        // No reset hook exists on the seam, so re-register behavior identical
        // to the production default (LINEAR direct, anything else rejected).
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (format == null || format == RegionFileFormat.LINEAR) {
                return new LinearRegionFile(file, level);
            }
            throw new IOException("No RegionOpener registered for non-LINEAR open: " + file);
        });
    }

    @Test
    public void targetForSourceSwapsExtension() {
        Path source = Path.of("region", "r.1.-2.mca");
        assertEquals(
            source.resolveSibling("r.1.-2.linear"),
            LinearRegionConverter.targetForSource(source));
    }

    @Test
    public void targetForSourceAppendsWhenNotMca() {
        Path source = Path.of("region", "oddname");
        assertEquals(
            source.resolveSibling("oddname.linear"),
            LinearRegionConverter.targetForSource(source));
    }

    @Test
    public void parseRegionNameAcceptsSignedPairs() {
        assertNotNull(LinearRegionConverter.parseRegionName("r.0.0.mca"));
        assertNotNull(LinearRegionConverter.parseRegionName("r.-1.2.linear"));
        assertNotNull(LinearRegionConverter.parseRegionName("r.12.-34.mca"));
    }

    @Test
    public void parseRegionNameRejectsJunk() {
        assertNull(LinearRegionConverter.parseRegionName("foo.mca"));
        assertNull(LinearRegionConverter.parseRegionName("r.1.mca"));
        assertNull(LinearRegionConverter.parseRegionName("r.a.b.mca"));
        assertNull(LinearRegionConverter.parseRegionName("r.1.2.txt"));
        assertNull(LinearRegionConverter.parseRegionName("r.1.2.mca.bak"));
        // Integer overflow is unparseable, never throws.
        assertNull(LinearRegionConverter.parseRegionName("r.99999999999999999999.0.mca"));
    }

    @Test
    public void countsMatchIsStrictEquality() {
        assertTrue(LinearRegionConverter.countsMatch(0, 0));
        assertTrue(LinearRegionConverter.countsMatch(7, 7));
        assertFalse(LinearRegionConverter.countsMatch(1, 2));
        assertFalse(LinearRegionConverter.countsMatch(0, 1));
    }

    @Test
    public void transitionWalksStagesInOrder() {
        assertEquals(LinearRegionConverter.FileState.CONVERTED,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.CONVERTING, true));
        assertEquals(LinearRegionConverter.FileState.VALIDATING,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.CONVERTED, true));
        assertEquals(LinearRegionConverter.FileState.VALIDATED,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.VALIDATING, true));
        assertEquals(LinearRegionConverter.FileState.DELETING,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.VALIDATED, true));
        assertEquals(LinearRegionConverter.FileState.DELETED,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.DELETING, true));
    }

    @Test
    public void transitionFailureCollapsesToFailed() {
        for (LinearRegionConverter.FileState state : LinearRegionConverter.FileState.values()) {
            assertEquals(LinearRegionConverter.FileState.FAILED,
                LinearRegionConverter.transition(state, false));
        }
        // Terminal states stay put on success (never resurrect).
        assertEquals(LinearRegionConverter.FileState.FAILED,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.FAILED, true));
        assertEquals(LinearRegionConverter.FileState.DELETED,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.DELETED, true));
    }

    @Test
    public void shouldRetryAllowsTwoRetriesThenStops() {
        assertTrue(LinearRegionConverter.shouldRetry(0));
        assertTrue(LinearRegionConverter.shouldRetry(1));
        assertTrue(LinearRegionConverter.shouldRetry(2));
        assertFalse(LinearRegionConverter.shouldRetry(3));
        assertFalse(LinearRegionConverter.shouldRetry(4));
        assertEquals(3, LinearRegionConverter.MAX_ATTEMPTS);
    }

    @Test
    public void minSizeConstantsSanity() {
        // Header (32 B) + footer (8 B): nothing valid is smaller.
        assertEquals(40L, LinearRegionConverter.MIN_VALID_SIZE);
        // Two 4 KiB sectors: vanilla pads every region file to this on open.
        assertEquals(8192L, LinearRegionConverter.MIN_ANVIL_SIZE);
    }

    @Test
    public void emptyFolderYieldsZeroSummary() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolder(region, 6, null);
        assertEquals(0, summary.converted());
        assertEquals(0, summary.validated());
        assertEquals(0, summary.deleted());
        assertEquals(0, summary.failed());
        assertTrue(summary.failures().isEmpty());
    }

    @Test
    public void truncatedSourceEntersProtectionAndIsNeverDeleted() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.0.0.mca");
        Files.write(source, new byte[100]);
        RecordingListener events = new RecordingListener();
        try {
            LinearRegionConverter.convertRegionFolder(region, 6, events);
            fail("expected ConversionProtectionException");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
            assertEquals(1, expected.getSummary().failures().size());
        }
        // Never delete before validation: the corrupt source stays in place,
        // no target is left behind, and all 3 attempts burned (2 retry callbacks).
        assertTrue(Files.isRegularFile(source));
        assertFalse(Files.isRegularFile(region.resolve("r.0.0.linear")));
        assertEquals(2, events.retries.size());
    }

    @Test
    public void fakeOpenerRoundTripConvertsValidatesDeletes() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.1.2.mca");
        // Real on-disk size must clear the truncated-source guard; chunk
        // bytes themselves come from the fake opener below.
        Files.write(source, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        Map<Long, byte[]> chunks = new HashMap<>();
        chunks.put(ChunkKey.of(1 * 32, 2 * 32), new byte[]{1, 2, 3});
        chunks.put(ChunkKey.of(1 * 32 + 1, 2 * 32), new byte[]{4, 5, 6, 7});
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new FakeMcaFile(chunks);
            }
            return new LinearRegionFile(file, level);
        });
        RecordingListener events = new RecordingListener();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolder(region, 6, events);
        assertEquals(1, summary.converted());
        assertEquals(1, summary.validated());
        assertEquals(1, summary.deleted());
        assertEquals(0, summary.failed());
        // Deletion is last: source gone only after the staged target validated.
        assertFalse(Files.isRegularFile(source));
        assertTrue(Files.isRegularFile(region.resolve("r.1.2.linear")));
        boolean sawDeleted = false;
        for (LinearRegionConverter.FileResult r : events.files) {
            if (r.status() == LinearRegionConverter.Status.DELETED) {
                sawDeleted = true;
            }
        }
        assertTrue(sawDeleted);
        assertTrue(events.retries.isEmpty());
    }

    @Test
    public void invalidShadowRequeuesForFullConvert() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.2.3.mca");
        Files.write(source, new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        // Invalid .linear shadow: nonzero but below the header+footer floor.
        Files.write(region.resolve("r.2.3.linear"), new byte[10]);
        Map<Long, byte[]> chunks = new HashMap<>();
        chunks.put(ChunkKey.of(2 * 32, 3 * 32), new byte[]{5, 6});
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new FakeMcaFile(chunks);
            }
            return new LinearRegionFile(file, level);
        });
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolder(region, 6, null);
        assertEquals(1, summary.deleted());
        assertEquals(0, summary.failed());
        assertFalse(Files.isRegularFile(source));
        assertTrue(Files.isRegularFile(region.resolve("r.2.3.linear")));
        // The invalid shadow was replaced, not kept.
        assertTrue(Files.size(region.resolve("r.2.3.linear")) > 10L);
    }

    @Test
    public void corruptSourceWithShadowEscalatesWithBothKept() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.8.8.mca");
        // Nonzero but below the anvil floor with an unreadable payload path:
        // source guard fails closed before any shadow is touched.
        Files.write(source, new byte[100]);
        Files.write(region.resolve("r.8.8.linear"), new byte[10]);
        try {
            LinearRegionConverter.convertRegionFolder(region, 6, null);
            fail("expected ConversionProtectionException for corrupt source + shadow");
        } catch (LinearRegionConverter.ConversionProtectionException expected) {
            assertEquals(1, expected.getSummary().failed());
        }
        assertTrue(Files.isRegularFile(source));
        assertTrue(Files.isRegularFile(region.resolve("r.8.8.linear")));
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
