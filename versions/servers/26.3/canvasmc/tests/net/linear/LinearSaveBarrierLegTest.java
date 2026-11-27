package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Files;
import java.nio.file.Path;

import io.canvasmc.canvas.GlobalConfiguration;
import net.linear.io.AtomicFiles;
import net.linear.io.PlayerFileDurability;
import net.linear.save.SaveOffLease;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.SaveAllCommand;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Canvas leg of the save barrier (overlay {@code SaveAllCommand},
 * {@code MinecraftServer}, {@code PlayerDataStorage},
 * {@code ServerStatsCounter}, {@code PlayerAdvancements} and the global
 * config): save-all keeps its flush branch, the save-all helper and the lease
 * hook exist, the two new keys default like the Folia leg, the player data
 * folder is swept at construction, and stats are replaced atomically (new
 * inode) with the flag on and in place with it off. Canvas publishes the
 * keys from its config load (no per-Part post-process), so the publish
 * itself is covered by the core resolve test.
 */
public class LinearSaveBarrierLegTest {

    private static final String UUID = "85a1efec-0000-4000-8000-00000000abcd";

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() {
        PlayerFileDurability.setEnabled(true);
        SaveOffLease.resetForTests();
    }

    @Test
    public void saveBarrierKeysDefaultLikeTheOtherLegs() throws Exception {
        GlobalConfiguration.Linear linear = new GlobalConfiguration.Linear();
        assertEquals(120L, linear.saveOffMaxDuration, "save-off-max-duration default 120 s");
        assertTrue(linear.durablePlayerData, "durable-player-data default true");
        assertEquals(120L, SaveOffLease.resolveMaxDurationSeconds(-3, message -> { }), "negative falls back to 120");
    }

    @Test
    public void saveAllIsRegisteredWithItsFlushBranch() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        SaveAllCommand.register(dispatcher);
        assertNotNull(dispatcher.getRoot().getChild("save-all"), "save-all registered");
        assertNotNull(dispatcher.getRoot().getChild("save-all").getChild("flush"), "save-all flush registered");
    }

    @Test
    public void saveOffLeaseHookAndRegionSaveAreWired() throws Exception {
        assertNotNull(MinecraftServer.class.getDeclaredMethod("linear$releaseSaveOffLease"));
        assertNotNull(SaveAllCommand.class.getDeclaredMethod("linear$saveCurrentRegion", MinecraftServer.class,
            net.minecraft.server.level.ServerLevel.class, net.linear.save.SaveAllBarrier.Ticket.class, boolean.class));
    }

    @Test
    public void playerDataFolderIsSweptAtConstruction(@TempDir Path root) throws Exception {
        try (LevelStorageSource.LevelStorageAccess access = LevelStorageSource.createDefault(root).createAccess("world")) {
            Path players = access.getLevelPath(LevelResource.PLAYER_DATA_DIR);
            Files.createDirectories(players);
            Files.write(players.resolve(UUID + ".dat"), new byte[] {1});
            Files.write(players.resolve(UUID + "-4242.dat"), new byte[] {2});
            new PlayerDataStorage(access, null);
            assertFalse(Files.exists(players.resolve(UUID + "-4242.dat")), "orphan temp moved");
            assertArrayEquals(new byte[] {2}, Files.readAllBytes(
                players.resolve(PlayerFileDurability.QUARANTINE_DIR).resolve(UUID + "-4242.dat")), "never deleted");
            assertTrue(Files.exists(players.resolve(UUID + ".dat")));
        }
    }

    @Test
    public void statsAreReplacedAtomicallyWithTheFlagAndInPlaceWithout(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(UUID + ".json");
        ServerStatsCounter stats = new ServerStatsCounter(null, file);

        Files.writeString(file, "sentinel");
        Object before = Files.getAttribute(file, "unix:ino");
        stats.save();
        String saved = Files.readString(file);
        assertTrue(saved.contains("DataVersion"), saved);
        assertNotEquals(before, Files.getAttribute(file, "unix:ino"), "atomic replace: new inode");
        assertFalse(Files.exists(dir.resolve(UUID + ".json" + AtomicFiles.TMP_SUFFIX)), "no temp left");

        PlayerFileDurability.setEnabled(false);
        Object vanilla = Files.getAttribute(file, "unix:ino");
        stats.save();
        assertEquals(vanilla, Files.getAttribute(file, "unix:ino"), "flag off: vanilla in-place write");
    }
}
