package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import io.papermc.paper.configuration.GlobalConfiguration;
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
 * Folia leg of the save barrier (minecraft-0019 / paper-0012): the
 * save-all command is registered again, the lease hook is in
 * {@code MinecraftServer}, the two new keys default and publish to the core,
 * the player data folder is swept at construction, and stats are replaced
 * atomically (new inode) with the flag on and in place with it off.
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

    private static GlobalConfiguration.RegionFormat.Linear newLinear() {
        GlobalConfiguration outer = new GlobalConfiguration();
        GlobalConfiguration.RegionFormat regionFormat = outer.new RegionFormat();
        return regionFormat.new Linear();
    }

    private static void runPostProcess(Object linear) throws Exception {
        Method m = linear.getClass().getDeclaredMethod("postProcess");
        m.setAccessible(true);
        m.invoke(linear);
    }

    @Test
    public void saveBarrierKeysDefaultAndPublishToTheCore() throws Exception {
        GlobalConfiguration.RegionFormat.Linear linear = newLinear();
        assertEquals(120L, linear.saveOffMaxDuration, "save-off-max-duration default 120 s");
        assertTrue(linear.durablePlayerData, "durable-player-data default true");
        linear.saveOffMaxDuration = -3;
        linear.durablePlayerData = false;
        runPostProcess(linear);
        assertEquals(120L, linear.saveOffMaxDuration, "negative falls back to 120");
        assertEquals(120L, SaveOffLease.maxDurationSeconds());
        assertFalse(PlayerFileDurability.enabled(), "flag published to the core");
        linear.saveOffMaxDuration = 0;
        runPostProcess(linear);
        assertEquals(0L, SaveOffLease.maxDurationSeconds(), "0 disables the lease");
    }

    @Test
    public void saveAllIsRegisteredWithItsFlushBranch() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        SaveAllCommand.register(dispatcher);
        assertNotNull(dispatcher.getRoot().getChild("save-all"), "save-all registered");
        assertNotNull(dispatcher.getRoot().getChild("save-all").getChild("flush"), "save-all flush registered");
    }

    @Test
    public void saveOffLeaseHookIsWiredIntoSetAutoSave() throws Exception {
        assertNotNull(MinecraftServer.class.getDeclaredMethod("linear$releaseSaveOffLease"));
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

        net.linear.save.CoupledSave.configure(false, 20L);
        PlayerFileDurability.setEnabled(false);
        Object vanilla = Files.getAttribute(file, "unix:ino");
        stats.save();
        assertEquals(vanilla, Files.getAttribute(file, "unix:ino"), "flag off: vanilla in-place write");
    }
}
