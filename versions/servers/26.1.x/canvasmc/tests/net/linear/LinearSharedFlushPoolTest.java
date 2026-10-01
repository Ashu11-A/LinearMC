package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Minecraft-0012: shared flush pool + direct-into-image.
 *
 * <p>NMS-light (TempDir + bootstrap): verifies the serial default
 * ({@code <=1} keeps the pre-patch loop), the parallel barrier
 * (caller participates, joins before returning, all files flushed), and the
 * direct-into-image rewrite (multi-slot round-trip byte-identical, no
 * {@code plain[]} copy).
 */
public class LinearSharedFlushPoolTest {

    private static final int COMPRESSION = 3;

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void resetPool() {
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    private static byte[] pattern(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) ((seed * 31 + i * 17) & 0xFF);
        }
        return out;
    }

    private static byte[] readAll(LinearRegionFile region, long chunk) throws Exception {
        try (DataInputStream in = region.getChunkDataInputStream(chunk)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Test
    public void serialDefaultKeepsLoop() throws Exception {
        Path dir = this.tempDir.resolve("serial16");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        LinearFlushCoordinator.linear$setFlushThreadsForTests(1);
        // Force immediate (age 0) so flushDirty drains.
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);

        LinearRegionFile a = new LinearRegionFile(dir.resolve("r.0.0.linear"), COMPRESSION);
        LinearRegionFile b = new LinearRegionFile(dir.resolve("r.1.0.linear"), COMPRESSION);
        try {
            a.write(ChunkKey.of(0, 0), ByteBuffer.wrap(pattern(64, 1)));
            b.write(ChunkKey.of(32, 0), ByteBuffer.wrap(pattern(64, 2)));
            coordinator.markDirty(a);
            coordinator.markDirty(b);
            coordinator.flushDirty();
            LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
            assertEquals(2L, snap.flush().count(), "serial: 2 files == 2 flush events");
            assertEquals(2L, snap.filesFlushed(), "serial: filesFlushed == 2");
        } finally {
            try { a.close(); } catch (Exception ignored) {}
            try { b.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    public void parallelBarrierFlushesAll() throws Exception {
        Path dir = this.tempDir.resolve("parallel16");
        dir.toFile().mkdirs();
        LinearFlushCoordinator coordinator = LinearFlushCoordinator.forFolder(dir);
        coordinator.resetForTests();
        LinearFlushCoordinator.linear$setFlushThreadsForTests(4);
        // Force immediate (age 0) so flushDirty drains.
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);

        final int n = 6;
        LinearRegionFile[] files = new LinearRegionFile[n];
        try {
            for (int i = 0; i < n; i++) {
                Path f = dir.resolve("r." + i + ".0.linear");
                files[i] = new LinearRegionFile(f, COMPRESSION);
                files[i].write(ChunkKey.of(i * 32, 0), ByteBuffer.wrap(pattern(128, 100 + i)));
                coordinator.markDirty(files[i]);
            }
            // Durability barrier: returns only after every file is done.
            coordinator.flushDirty();
            LinearRegionTimings.LinearFolderSnapshot snap = coordinator.snapshot();
            assertEquals(n, snap.flush().count(), "parallel: N files == N flush events");
            assertEquals(n, snap.filesFlushed(), "parallel: filesFlushed == N");
            assertEquals(0, coordinator.dirtyCount(), "dirty set drained (barrier joined)");
        } finally {
            for (LinearRegionFile f : files) {
                if (f != null) {
                    try { f.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    @Test
    public void directIntoImageRoundTrip() throws Exception {
        Path dir = this.tempDir.resolve("direct16");
        dir.toFile().mkdirs();
        Path file = dir.resolve("r.0.0.linear");
        byte[][] payloads = new byte[16][];
        long[] positions = new long[16];
        LinearRegionFile region = new LinearRegionFile(file, COMPRESSION);
        try {
            for (int i = 0; i < 16; i++) {
                int x = i % 32;
                int z = (i / 32) % 32;
                positions[i] = ChunkKey.of(x, z);
                payloads[i] = pattern(256 + i * 37, 7 + i);
                region.write(positions[i], ByteBuffer.wrap(payloads[i]));
            }
            region.flush();
            for (int i = 0; i < 16; i++) {
                assertArrayEquals(payloads[i], readAll(region, positions[i]),
                    "slot " + i + " round-trip (direct-into-image)");
            }
        } finally {
            try { region.close(); } catch (Exception ignored) {}
        }
        // Reopen from disk: image written via direct offsets must decode.
        LinearRegionFile reopened = new LinearRegionFile(file, COMPRESSION);
        try {
            for (int i = 0; i < 16; i++) {
                assertArrayEquals(payloads[i], readAll(reopened, positions[i]),
                    "reopened slot " + i);
            }
        } finally {
            try { reopened.close(); } catch (Exception ignored) {}
        }
    }
}
