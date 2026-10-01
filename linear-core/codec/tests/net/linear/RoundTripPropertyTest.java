package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Round-trip properties for the region file and the REVERSE path.
 *
 * <ul>
 *   <li>Real files ({@link TemporaryFolder}): {@code write -> flush ->
 *       reopen -> read} returns byte-identical payloads at sizes
 *       1/100/4K/64K/1M plus one exact-8 MiB boundary chunk, across
 *       distinct slots of one region.</li>
 *   <li>REVERSE path: {@code .linear -> fake ChunkSink -> read back}
 *       through {@link LinearRegionConverter#convertSingleFileReverse} is
 *       byte-identical.</li>
 *   <li>Fail-loud: garbage files, torn headers and mid-file truncation
 *       throw on open; nothing reads back as phantom data.</li>
 * </ul>
 */
public class RoundTripPropertyTest {

    private static final int[] SIZES = {1, 100, 4096, 65536, 1_048_576};
    private static final int BOUNDARY_8M = 8 * 1024 * 1024;

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

    private static byte[] randomPayload(int size, long seed) {
        byte[] payload = new byte[size];
        new Random(seed).nextBytes(payload);
        return payload;
    }

    private static byte[] drain(DataInputStream in) throws IOException {
        try (DataInputStream autoClose = in) {
            return autoClose.readAllBytes();
        }
    }

    @Test
    public void writeFlushReopenReadIsByteIdentical() throws Exception {
        Path dir = temporaryFolder.newFolder("roundtrip").toPath();
        Path file = dir.resolve("r.0.0.linear");
        Map<Long, byte[]> expected = new HashMap<>();
        // Distinct slots: chunk (i, 0) -> slot i.
        for (int i = 0; i < SIZES.length; i++) {
            expected.put(ChunkKey.of(i, 0), randomPayload(SIZES[i], 0xF00D + i));
        }
        try (LinearRegionFile region = new LinearRegionFile(file, 1)) {
            for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
                region.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            region.flush();
            // Same-handle reads agree before reopen too.
            for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
                assertArrayEquals(drain(region.getChunkDataInputStream(e.getKey())), e.getValue());
            }
        }
        assertTrue(Files.isRegularFile(file));
        try (LinearRegionFile reopened = new LinearRegionFile(file, 1)) {
            for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
                assertTrue(reopened.hasChunk(e.getKey()));
                assertArrayEquals("slot " + ChunkKey.format(e.getKey()),
                    e.getValue(), drain(reopened.getChunkDataInputStream(e.getKey())));
            }
            assertFalse(reopened.hasChunk(ChunkKey.of(31, 31)));
            assertNull(reopened.getChunkDataInputStream(ChunkKey.of(31, 31)));
        }
    }

    @Test
    public void boundary8MiBSingleChunkRoundTrips() throws Exception {
        Path dir = temporaryFolder.newFolder("boundary").toPath();
        Path file = dir.resolve("r.1.0.linear");
        byte[] payload = randomPayload(BOUNDARY_8M, 0xBEEF);
        long key = ChunkKey.of(32, 0);
        try (LinearRegionFile region = new LinearRegionFile(file, 1)) {
            region.write(key, ByteBuffer.wrap(payload));
            region.flush();
        }
        try (LinearRegionFile reopened = new LinearRegionFile(file, 1)) {
            assertArrayEquals(payload, drain(reopened.getChunkDataInputStream(key)));
        }
    }

    @Test
    public void zeroLengthWriteDeletesSlotAndPersists() throws Exception {
        Path dir = temporaryFolder.newFolder("delete").toPath();
        Path file = dir.resolve("r.2.0.linear");
        long key = ChunkKey.of(64, 0);
        byte[] payload = randomPayload(128, 0xD31);
        try (LinearRegionFile region = new LinearRegionFile(file, 1)) {
            region.write(key, ByteBuffer.wrap(payload));
            assertTrue(region.hasChunk(key));
            region.clear(key);
            assertFalse(region.hasChunk(key));
            assertNull(region.getChunkDataInputStream(key));
            region.flush();
        }
        try (LinearRegionFile reopened = new LinearRegionFile(file, 1)) {
            assertFalse(reopened.hasChunk(key));
            assertNull(reopened.getChunkDataInputStream(key));
        }
    }

    @Test
    public void reversePathLinearToSinkReadsBackIdentical() throws Exception {
        Path region = temporaryFolder.newFolder("reverse").toPath();
        Path source = region.resolve("r.0.1.linear");
        Map<Long, byte[]> expected = new HashMap<>();
        for (int i = 0; i < 8; i++) {
            expected.put(ChunkKey.of(i, 32), randomPayload(64 + i * 37, 0xA9 + i));
        }
        try (LinearRegionFile src = new LinearRegionFile(source, 1)) {
            for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
                src.write(e.getKey(), ByteBuffer.wrap(e.getValue()));
            }
            src.flush();
        }

        Map<Long, byte[]> sunk = new ConcurrentHashMap<>();
        AtomicInteger sinkOpens = new AtomicInteger(0);
        LinearRegionConverter.linear$setRegionOpener((f, folder, sync, format, level) -> {
            if (f.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new FakeMcaFile(sunk);
            }
            return new LinearRegionFile(f, level);
        });
        LinearRegionConverter.linear$setChunkSinkOpener((f, folder, level) -> {
            sinkOpens.incrementAndGet();
            return new FakeSink(f, sunk);
        });

        Path target = region.resolve("r.0.1.mca");
        LinearRegionConverter.convertSingleFileReverse(source, target, 1);

        assertEquals(1, sinkOpens.get());
        assertEquals(expected.size(), sunk.size());
        for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
            assertArrayEquals(e.getValue(), sunk.get(e.getKey()));
        }
        // Read-back through the fake anvil handle drains identical bytes.
        try (AbstractRegionFile readBack = new FakeMcaFile(sunk)) {
            for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
                assertArrayEquals(e.getValue(), drain(readBack.getChunkDataInputStream(e.getKey())));
            }
        }
        assertTrue(Files.size(target) >= LinearRegionConverter.MIN_ANVIL_SIZE);
    }

    @Test
    public void garbageFileFailsLoudOnOpen() throws Exception {
        Path dir = temporaryFolder.newFolder("garbage").toPath();
        Path file = dir.resolve("r.0.0.linear");
        byte[] garbage = new byte[256];
        new Random(0x6A).nextBytes(garbage);
        Files.write(file, garbage);
        try {
            new LinearRegionFile(file, 1).close();
            fail("expected IOException for garbage superblock");
        } catch (IOException expected) {
        }
    }

    @Test
    public void tornHeaderFailsLoudAndStoresNothingReadable() throws Exception {
        Path dir = temporaryFolder.newFolder("torn").toPath();
        Path file = dir.resolve("r.0.0.linear");
        long key = ChunkKey.of(0, 0);
        byte[] payload = randomPayload(1024, 0x70);
        try (LinearRegionFile region = new LinearRegionFile(file, 1)) {
            region.write(key, ByteBuffer.wrap(payload));
            region.flush();
        }
        byte[] good = Files.readAllBytes(file);
        assertTrue(good.length > 64);
        // Torn mid-file image: every truncation below the full length must
        // fail on open — never yield phantom chunks.
        for (int cut : new int[] {1, 16, 32, good.length / 2, good.length - 1}) {
            Files.write(file, java.util.Arrays.copyOf(good, cut));
            try {
                new LinearRegionFile(file, 1).close();
                fail("expected IOException for " + cut + "-byte truncation");
            } catch (IOException expected) {
            }
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
}
