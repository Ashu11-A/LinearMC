package io.linearmc.horizon;

import java.nio.file.Path;
import net.linear.StartupConversion;
import net.linear.command.queue.JobQueue;
import net.linear.config.LinearFileConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-plugin startup conversion (fork parity: Folia {@code paper-0011}, Canvas
 * {@code LinearStartupConversion}), run by {@code DedicatedServerMixin} before
 * plugins load and before any world storage opens. Each dimension converts to
 * the effective format of its {@code region/} folder.
 */
public final class LinearStartup {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    private LinearStartup() {
    }

    /** @return {@code false} when protection tripped: the server was halted, abort boot. */
    public static boolean runPrePluginConversion(final MinecraftServer server) {
        HorizonSeams.registerOpeners();
        LOGGER.info(JobQueue.droppedByRestartLine(0));
        int converted = 0;
        final var stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        for (final LevelStem stem : stems) {
            final var key = stems.getResourceKey(stem);
            if (key.isEmpty()) {
                continue;
            }
            final Path dimension = server.storageSource.getDimensionPath(Registries.levelStemToLevel(key.get()));
            final String region = dimension.resolve("region").toString();
            final int n = StartupConversion.convertDimension(dimension, new StartupConversion.Target(
                LinearFileConfig.resolveFormatFor(region), LinearFileConfig.resolveLevelFor(region)));
            if (n < 0) {
                server.halt(false);
                return false;
            }
            converted += n;
        }
        if (converted > 0) {
            // A drop-in plugin: a stock server cannot read .linear, so say how to leave.
            LOGGER.warn("[Linear] {} region file(s) were converted to Linear on this boot. Before removing"
                + " LinearMC run '/linear convert <world> --to-mca --execute' for every world. Keep backups.", converted);
        }
        return true;
    }
}
