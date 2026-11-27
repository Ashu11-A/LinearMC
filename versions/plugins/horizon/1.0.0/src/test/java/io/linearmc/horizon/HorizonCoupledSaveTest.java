package io.linearmc.horizon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.linear.io.PlayerFileDurability;
import net.linear.save.CoupledSave;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Horizon leg of the coupled save order: the keys ship off / 20 s like the
 * forks and reach the core; the chunk I/O fences (Moonrise's private task
 * map, read reflectively) resolve on this dev bundle, so a host that drifts
 * fails here instead of running coupled save without fences; the periodic
 * player save stays as before while the flag is off.
 */
public class HorizonCoupledSaveTest {

    @AfterEach
    void reset() {
        CoupledSave.resetForTests();
        PlayerFileDurability.setEnabled(true);
    }

    @Test
    void fencesResolveOnThisBundle() {
        assertTrue(HorizonCoupledSave.fencesResolved(), "MoonriseRegionFileIO chunk I/O task map readable");
        assertFalse(HorizonCoupledSave.hostHasRegions(), "a Paper dev bundle has no region scheduler");
    }

    @Test
    void shippedDefaultsMatchTheForks(@TempDir Path dir) throws Exception {
        CoupledSave.configure(false, 5L);
        LinearConfigLoader.push(LinearConfigLoader.loadOrCreate(dir));
        assertTrue(CoupledSave.enabled(), "coupled-save.enabled default true");
        assertEquals(20L, CoupledSave.intervalSeconds(), "coupled-save.interval default 20 s");
    }

    @Test
    void configuredValuesReachTheCore() throws Exception {
        final YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(String.join("\n",
            "region-format:",
            "  linear:",
            "    durable-player-data: false",
            "    coupled-save:",
            "      enabled: true",
            "      interval: 0",
            ""));
        LinearConfigLoader.push(cfg);
        assertTrue(CoupledSave.enabled());
        assertEquals(20L, CoupledSave.intervalSeconds(), "below 1 s falls back to 20");
        assertTrue(PlayerFileDurability.enabled(), "coupled save implies durable player data");
    }

    @Test
    void periodicPlayerSaveIsUntouchedWhileOff() {
        assertFalse(HorizonCoupledSave.periodic(null), "off: the host's periodic player save runs");
    }

    @Test
    void departureHookIsANoOpWithoutAServerAndItsTargetsAreHostOptional() throws Exception {
        CoupledSave.configure(true, 20L);
        HorizonCoupledSave.departure(null);
        final String source = java.nio.file.Files.readString(
            Path.of("src/main/java/io/linearmc/horizon/mixins/ServerPlayerMixin.java"));
        assertTrue(MixinDescriptorsTest.optionalSelectorsIn(source).contains("preChangeDimension"),
            "Folia/Canvas-only target must be require = 0");
        assertTrue(java.nio.file.Files.readString(Path.of("src/main/resources/mixins.linearmc.json"))
            .contains("\"ServerPlayerMixin\""), "registered in the mixin config");
    }

    @Test
    void mainThreadSaveAllWaitStaysBelowTheWatchdog() {
        assertTrue(HorizonSaveBarrier.MAIN_THREAD_WAIT_MILLIS <= 20_000L,
            "Paper waits on the main thread: the budget must stay well below the 60 s watchdog");
    }
}
