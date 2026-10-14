package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Minecraft-0011: flush batch outside the monitor.
 *
 * <p>NMS-light (TempDir + SharedConstants/Bootstrap, never halt()): exercises
 * {@code RegionFileStorage.flush()} and {@code close()} with LINEAR files via
 * reflection (protected ctor; keeps this test in {@code net.linear} so the
 * existing {@code cp *.java net/linear} staging still works). Verifies
 * functional correctness after the unlock (write -&gt; flush -&gt; file exists
 * -&gt; reopen -&gt; read back; write -&gt; close without explicit flush -&gt;
 * persists). The lock-freedom itself is a code property (snapshot under lock,
 * I/O outside); these tests pin that the refactored paths still persist.
 */
public class LinearFlushUnlockTest {

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void resetCoordinator() {
        // Age-based flush (flush coordinator): storage.flush() test forces immediate age.
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    private static RegionFileStorage newStorage(Path dir, int compression) throws Exception {
        RegionStorageInfo info = new RegionStorageInfo("test", Level.OVERWORLD, "chunk");
        Constructor<RegionFileStorage> ctor = RegionFileStorage.class.getDeclaredConstructor(
            RegionStorageInfo.class, Path.class, boolean.class,
            RegionFileFormat.class, int.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(info, dir, false, RegionFileFormat.LINEAR, compression, true);
    }

    private static CompoundTag tagWith(String key, int value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(key, value);
        return tag;
    }

    @Test
    public void storageFlushPersistsAndReopens() throws Exception {
        Path dir = this.tempDir.resolve("flushunlock");
        Files.createDirectories(dir);
        // Force immediate age so storage.flush() drains.
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(0L);
        RegionFileStorage storage = newStorage(dir, 3);
        ChunkPos pos = new ChunkPos(0, 0);
        try {
            storage.write(pos, tagWith("v", 42));
            storage.flush();
            assertTrue(Files.exists(dir.resolve("r.0.0.linear")),
                "flush() must materialise r.0.0.linear");
        } finally {
            storage.close();
        }

        RegionFileStorage reopened = newStorage(dir, 3);
        try {
            CompoundTag back = reopened.read(pos);
            assertNotNull(back, "reopened storage must read back chunk");
            assertEquals(42, back.getIntOr("v", -1), "round-trip value");
        } finally {
            reopened.close();
        }
    }

    @Test
    public void storageCloseWithoutFlushPersists() throws Exception {
        Path dir = this.tempDir.resolve("closeflush");
        Files.createDirectories(dir);
        ChunkPos pos = new ChunkPos(32, 0); // region (1,0)
        RegionFileStorage storage = newStorage(dir, 1);
        storage.write(pos, tagWith("w", 7));
        // No explicit flush(): close() must persist via file close + evict.
        storage.close();
        assertTrue(Files.exists(dir.resolve("r.1.0.linear")),
            "close() must materialise r.1.0.linear even without flush()");

        RegionFileStorage reopened = newStorage(dir, 1);
        try {
            CompoundTag back = reopened.read(pos);
            assertNotNull(back, "close-persisted chunk must read back");
            assertEquals(7, back.getIntOr("w", -1));
        } finally {
            reopened.close();
        }
    }
}
