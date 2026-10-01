package net.linear.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.linear.AbstractRegionFile;
import net.linear.ChunkKey;
import net.linear.LinearRegionConverter;
import net.linear.LinearRegionFile;
import net.linear.RegionFileFormat;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** command: job runner direction routing + quiesce check. */
public class JobRunnerTest {

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
    }

    @Test
    public void reverseRunsConvertRegionFolderReverse() throws Exception {
        Path region = temporaryFolder.newFolder("region").toPath();
        Path source = region.resolve("r.0.0.linear");
        long chunk = ChunkKey.of(0, 0);
        byte[] payload = new byte[]{1, 2, 3};
        try (LinearRegionFile src = new LinearRegionFile(source, 6)) {
            src.write(chunk, ByteBuffer.wrap(payload));
            src.flush();
        }

        Map<Long, byte[]> mcaChunks = new ConcurrentHashMap<>();
        AtomicInteger sinkOpens = new AtomicInteger(0);
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

        ConvertJob job = new ConvertJob(
            ConvertDirection.LINEAR_TO_MCA, "world", 6, 1, false, 0);
        JobRunner.run(job, List.of(region), 6, 1);

        assertEquals(JobState.DONE, job.state());
        assertEquals(1, sinkOpens.get());
        assertFalse(Files.isRegularFile(source));
        assertTrue(Files.isRegularFile(region.resolve("r.0.0.mca")));
    }

    @Test
    public void quiesceCheckReturnsList() {
        List<String> dirty = JobRunner.quiesceCheck();
        assertNotNull(dirty);
        assertTrue(dirty instanceof List);
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
            // Vanilla pads every .mca to 8192 bytes; the converter
            // validates this minimum (see LinearRegionConverter).
            if (!Files.isRegularFile(file) || Files.size(file) < 8192L) {
                Files.write(file, new byte[8192]);
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
