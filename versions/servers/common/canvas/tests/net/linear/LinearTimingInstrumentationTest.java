package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Linear timing instrumentation:
 * read/write/flush paths.
 *
 * <p>NMS-light: TempDir fixtures + {@code SharedConstants}/{@code Bootstrap}
 * like {@link LinearRegionFileRoundTripTest}. Exercises the file-level
 * forwarding ({@code LinearRegionFile} -&gt; {@code LinearFlushCoordinator.forFolder(parent)})
 * and the folder snapshot. Never calls {@code halt()}; never touches upstream suites.
 */
public class LinearTimingInstrumentationTest {

    private static final int COMPRESSION = 3;

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static byte[] pattern(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) ((seed * 31 + i * 17) & 0xFF);
        }
        return out;
    }

    private static byte[] readAll(LinearRegionFile region, long chunk) throws IOException {
        try (DataInputStream in = region.getChunkDataInputStream(chunk)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Test
    public void countersStartAtEmpty() {
        Path dir = this.tempDir.resolve("empty");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
        assertEquals(0L, snap.read().count(), "read starts empty");
        assertEquals(0L, snap.write().count(), "write starts empty");
        assertEquals(0L, snap.flush().count(), "flush starts empty");
        assertEquals(0L, snap.load().count(), "load starts empty");
        assertEquals(LinearRegionTimings.LinearTimings.EMPTY, snap.read());
        assertEquals(LinearRegionTimings.LinearTimings.EMPTY, snap.write());
        assertEquals(LinearRegionTimings.LinearTimings.EMPTY, snap.flush());
        assertEquals(LinearRegionTimings.LinearTimings.EMPTY, snap.load());
        assertEquals(0, snap.dirtyDepth());
    }

    @Test
    public void writeFlushProducesWriteAndFlushCounts() throws IOException {
        Path dir = this.tempDir.resolve("writeflush");
        dir.toFile().mkdirs();
        Path file = dir.resolve("r.0.0.linear");
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();

        long pos = ChunkKey.of(0, 0);
        byte[] payload = pattern(128, 11);
        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        try {
            region.write(pos, ByteBuffer.wrap(payload));
            region.flush();
            LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
            assertTrue(snap.write().count() >= 1, "write.count>=1 after write");
            assertTrue(snap.flush().count() >= 1, "flush.count>=1 after flush");
            assertTrue(snap.write().totalMicros() >= 0, "write totalMicros>=0");
            assertTrue(snap.flush().totalMicros() >= 0, "flush totalMicros>=0");
            assertTrue(snap.write().maxMicros() >= 0, "write maxMicros>=0");
            assertTrue(snap.flush().maxMicros() >= 0, "flush maxMicros>=0");
        } finally {
            region.close();
        }
    }

    @Test
    public void reopenReadProducesReadCount() throws IOException {
        Path dir = this.tempDir.resolve("reopen");
        dir.toFile().mkdirs();
        Path file = dir.resolve("r.0.0.linear");
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();

        long pos = ChunkKey.of(3, 4);
        byte[] payload = pattern(256, 17);
        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        region.write(pos, ByteBuffer.wrap(payload));
        region.flush();
        region.close();

        // Reopen simulates a restart; ctor load() + read both report.
        coordinator.resetForTests();
        LinearRegionFile reopened = new LinearRegionFile(file, COMPRESSION);
        try {
            byte[] back = readAll(reopened, pos);
            assertArrayEquals(payload, back, "reopen re-read");
            LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
            assertTrue(snap.read().count() >= 1, "read.count>=1 after reopen+read");
            assertTrue(snap.read().totalMicros() >= 0, "read totalMicros>=0");
            assertTrue(snap.load().count() >= 1, "load.count>=1 after reopen");
        } finally {
            reopened.close();
        }
    }

    @Test
    public void snapshotDeltasAreMonotonicAndResetClears() throws IOException {
        Path dir = this.tempDir.resolve("monotonic");
        dir.toFile().mkdirs();
        Path file = dir.resolve("r.1.1.linear");
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();

        long a = ChunkKey.of(32, 32);
        long b = ChunkKey.of(33, 32);
        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        try {
            region.write(a, ByteBuffer.wrap(pattern(64, 1)));
            region.flush();
            LinearRegionTimings.LinearFolderSnapshot before = coordinator.snapshot();
            region.write(b, ByteBuffer.wrap(pattern(64, 2)));
            region.flush();
            readAll(region, a);
            LinearRegionTimings.LinearFolderSnapshot after = coordinator.snapshot();
            assertTrue(after.write().count() >= before.write().count(), "write deltas monotonic");
            assertTrue(after.flush().count() >= before.flush().count(), "flush deltas monotonic");
            assertTrue(after.read().count() >= before.read().count(), "read deltas monotonic");
            assertTrue(after.write().totalMicros() >= before.write().totalMicros(), "write totals monotonic");
            assertTrue(after.flush().totalMicros() >= before.flush().totalMicros(), "flush totals monotonic");

            coordinator.resetForTests();
            LinearRegionTimings.LinearFolderSnapshot cleared = coordinator.snapshot();
            assertEquals(0L, cleared.read().count(), "reset clears read");
            assertEquals(0L, cleared.write().count(), "reset clears write");
            assertEquals(0L, cleared.flush().count(), "reset clears flush");
            assertEquals(0L, cleared.load().count(), "reset clears load");
            assertEquals(LinearRegionTimings.LinearTimings.EMPTY, cleared.read());
            assertEquals(LinearRegionTimings.LinearTimings.EMPTY, cleared.write());
        } finally {
            region.close();
        }
    }
}
