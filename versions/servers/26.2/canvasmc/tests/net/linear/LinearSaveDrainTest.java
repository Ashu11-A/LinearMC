package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Save-drain wiring (finding F fix): the Folia/Moonrise runtime never flushes
 * or closes vanilla RegionFileStorage instances, so the coordinator dirty set
 * previously drained only via bound pressure. These tests pin the two new
 * static drains that ServerChunkCache.save and ServerLevel.close call.
 *
 * <p>NMS-light (TempDir + bootstrap). Each test uses fresh folders (separate
 * coordinators) for isolation; the static BY_FOLDER registry is shared, so
 * folder names are unique per test.
 */
public class LinearSaveDrainTest {

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearOverrides() {
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(null);
        LinearFlushCoordinator.linear$setFlushThreadsForTests(null);
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    private static void writeChunk(Path file, int level, int x, int z) throws Exception {
        Files.createDirectories(file.getParent());
        try (LinearRegionFile region = new LinearRegionFile(file, level)) {
            byte[] payload = new byte[64];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (x + z + i);
            }
            region.write(ChunkKey.of(x, z), ByteBuffer.wrap(payload));
            region.flush();
        }
    }

    @Test
    public void flushAllDirtyDrainsEveryFolder() throws Exception {
        Path dirA = this.tempDir.resolve("drain-a");
        Path dirB = this.tempDir.resolve("drain-b");
        Path fileA = dirA.resolve("r.0.0.linear");
        Path fileB = dirB.resolve("r.1.1.linear");
        writeChunk(fileA, 9, 0, 0);
        writeChunk(fileB, 9, 33, 33);
        LinearFlushCoordinator coordA = LinearFlushCoordinator.forFolder(dirA);
        LinearFlushCoordinator coordB = LinearFlushCoordinator.forFolder(dirB);
        coordA.resetForTests();
        coordB.resetForTests();
        // Re-mark through the coordinator (writeChunk flushed directly).
        try (LinearRegionFile reopenA = new LinearRegionFile(fileA, 9);
                LinearRegionFile reopenB = new LinearRegionFile(fileB, 9)) {
            coordA.markDirty(reopenA);
            coordB.markDirty(reopenB);
        }
        // Young files: force=false with a huge frequency must NOT drain (age gate).
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(3_600L);
        LinearFlushCoordinator.flushAllDirty(false);
        assertEquals(0L, coordA.snapshot().filesFlushed(), "age gate holds young files");
        // Forced drain (explicit save/stop semantics): everything persists.
        LinearFlushCoordinator.flushAllDirty(true);
        assertTrue(coordA.snapshot().filesFlushed() >= 1L, "forced drain flushes folder A");
        assertTrue(coordB.snapshot().filesFlushed() >= 1L, "forced drain flushes folder B");
        assertTrue(Files.size(fileA) > 40L && Files.size(fileB) > 40L, "flushed files materialize");
    }

    @Test
    public void evictAllDropsRegistryAfterForcedFlush() throws Exception {
        Path dir = this.tempDir.resolve("drain-evict");
        Path file = dir.resolve("r.2.2.linear");
        writeChunk(file, 9, 65, 65);
        LinearFlushCoordinator coord = LinearFlushCoordinator.forFolder(dir);
        coord.resetForTests();
        try (LinearRegionFile reopen = new LinearRegionFile(file, 9)) {
            coord.markDirty(reopen);
        }
        LinearFlushCoordinator.evictAll();
        assertTrue(coord.snapshot().filesFlushed() >= 1L, "evictAll force-flushes before dropping");
        // Registry dropped: a second evictAll is a no-op, and the folder
        // re-registers cleanly on next use (idempotent stop-drain contract).
        LinearFlushCoordinator.evictAll();
        LinearFlushCoordinator again = LinearFlushCoordinator.forFolder(dir);
        again.resetForTests();
        assertEquals(0L, again.snapshot().filesFlushed(), "fresh registry starts clean");
    }

    @Test
    public void saveDrainNeverThrowsOnMissingFolder() {
        // Per-folder failures are logged, never thrown: a save must not die.
        assertDoesNotThrow(LinearFlushCoordinator::evictAll, "evictAll on live registry never throws");
        LinearFlushCoordinator.flushAllDirty(false);
        LinearFlushCoordinator.flushAllDirty(true);
    }
}
