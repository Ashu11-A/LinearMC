package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Quiet reporters never throw; event record carries its fields. */
public class FlushReportsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void pin() {
        LinearFlushCoordinator.linear$setFlushThreadsForTests(1);
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(3600L);
    }

    @After
    public void reset() {
        LinearFlushCoordinator.evictAll();
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    @Test
    public void nullFolderSafe() {
        FlushReports.markDirtyForFile(null, new StubRegionFile());
        FlushReports.markDirtyForFile(tmp.getRoot().toPath(), null);
        FlushReports.reportCacheHitQuietly(null);
        FlushReports.reportCacheMissQuietly(null);
        FlushReports.reportOversizeQuietly(null);
    }

    @Test
    public void quietReportersNeverThrow() throws Exception {
        Path folder = tmp.newFolder("quiet").toPath();
        FlushReports.reportCacheHitQuietly(folder);
        FlushReports.reportCacheMissQuietly(folder);
        FlushReports.reportOversizeQuietly(folder);
        FlushReports.markDirtyForFile(folder, new StubRegionFile());
        assertTrue(LinearFlushCoordinator.forFolder(folder).dirtyCount() >= 1);
        LinearFlushCoordinator.flushAllDirty(true);
    }

    @Test
    public void eventRecordFields() {
        FlushEvent event = new FlushEvent("world/region", 3, 2, 1, 42L);
        assertEquals("world/region", event.folderKey());
        assertEquals(3, event.filesAttempted());
        assertEquals(2, event.filesFlushed());
        assertEquals(1, event.failures());
        assertEquals(42L, event.elapsedMicros());
    }

    /** In-memory region file, no IO. */
    private static final class StubRegionFile implements AbstractRegionFile {
        final Map<Long, byte[]> chunks = new HashMap<>();
        boolean marked = true;

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
            marked = true;
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
            return marked;
        }

        @Override
        public void clearMarkedToSave() {
            marked = false;
        }
    }
}
