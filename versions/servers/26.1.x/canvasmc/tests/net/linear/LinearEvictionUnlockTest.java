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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Minecraft-0011: eviction close outside the monitor.
 *
 * <p>NMS-light (TempDir + bootstrap, never halt()): writes more regions than
 * the 256-entry cache (GlobalConfiguration is null in tests, so cacheSize
 * defaults to 256), forcing {@code removeLast()} evictions. Verifies early
 * regions survive eviction (eviction close flushes) and reopen correctly.
 * The unlock itself is a code property (remove under lock, close unlocked);
 * this test pins that the refactored LRU path still persists.
 */
public class LinearEvictionUnlockTest {

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RegionFileStorage newStorage(Path dir, int compression) throws Exception {
        RegionStorageInfo info = new RegionStorageInfo("test", Level.OVERWORLD, "chunk");
        Constructor<RegionFileStorage> ctor = RegionFileStorage.class.getDeclaredConstructor(
            RegionStorageInfo.class, Path.class, boolean.class,
            RegionFileFormat.class, int.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(info, dir, false, RegionFileFormat.LINEAR, compression, true);
    }

    @Test
    public void evictedRegionsPersistAndReopen() throws Exception {
        Path dir = this.tempDir.resolve("evict");
        Files.createDirectories(dir);
        final int regions = 260; // > 256 cache to force evictions
        RegionFileStorage storage = newStorage(dir, 1);
        try {
            for (int i = 0; i < regions; i++) {
                ChunkPos pos = new ChunkPos(i * 32, 0); // region (i,0)
                CompoundTag tag = new CompoundTag();
                tag.putInt("idx", i);
                storage.write(pos, tag);
            }
            // No explicit flush(): eviction closes + final close() must persist.
        } finally {
            storage.close();
        }

        // Spot-check early (evicted), middle and late regions after reopen.
        RegionFileStorage reopened = newStorage(dir, 1);
        try {
            for (int i : new int[]{0, 1, 128, 259}) {
                ChunkPos pos = new ChunkPos(i * 32, 0);
                CompoundTag back = reopened.read(pos);
                assertNotNull(back, "evicted region " + i + " must read back");
                assertEquals(i, back.getIntOr("idx", -1), "region " + i + " value");
            }
        } finally {
            reopened.close();
        }
    }
}
