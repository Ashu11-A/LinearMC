package io.linearmc.horizon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.linear.config.LinearFileConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Pushes {@code plugins/LinearMC/config.yml} into the core file layer
 * ({@link LinearFileConfig}), which the mixin side reads.
 *
 * <p>Pushed from {@link LinearBootstrapper}, before the server initializes,
 * so the startup conversion and the first world storages already see the
 * file values (the forks read their config before worlds load too).
 * {@code LinearPlugin#onEnable} pushes again only if that push did not
 * complete.</p>
 */
public final class LinearConfigLoader {

    static final String FILE_NAME = "config.yml";

    private LinearConfigLoader() {
    }

    /**
     * Loads {@code <dataDir>/config.yml}, writing the bundled default first
     * when the file does not exist yet (same as {@code saveDefaultConfig}).
     */
    public static YamlConfiguration loadOrCreate(final Path dataDir) throws IOException {
        final Path file = dataDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            Files.createDirectories(dataDir);
            try (InputStream defaults = LinearConfigLoader.class.getClassLoader().getResourceAsStream(FILE_NAME)) {
                if (defaults != null) {
                    Files.copy(defaults, file);
                }
            }
        }
        return YamlConfiguration.loadConfiguration(file.toFile());
    }

    private static volatile boolean pushed;

    /** onEnable fallback: push only when the bootstrapper could not. */
    static void pushIfMissing(final Path dataDir, final java.util.logging.Logger log) {
        if (pushed) {
            return;
        }
        try {
            push(loadOrCreate(dataDir));
        } catch (IOException e) {
            log.warning("LinearMC: could not read config.yml: " + e.getMessage());
        }
    }

    /** Pushes every file value the core reads (global, writer, symlink guard, per-world). */
    public static void push(final ConfigurationSection root) {
        LinearFileConfig.setFileValues(
            root.getString("region-format.format", null),
            root.isInt("region-format.linear.compression-level")
                ? root.getInt("region-format.linear.compression-level")
                : null);
        // Same core entry point as the forks: validates (bad values warn once
        // and fall back) and names -Dlinearmc.implementation/grid-size overrides.
        final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("LinearMC");
        LinearFileConfig.pushWriterValues(
            root.getString("region-format.linear.implementation", null),
            root.isInt("region-format.linear.grid-size")
                ? root.getInt("region-format.linear.grid-size")
                : null,
            log::warn, log::info);
        LinearFileConfig.setFileSymlinkGuard(
            root.isBoolean("region-format.linear.crash-on-broken-symlink")
                ? root.getBoolean("region-format.linear.crash-on-broken-symlink")
                : null);
        LinearFileConfig.setWorldValues(worldValues(root.getConfigurationSection("worlds")));
        // Save barrier keys (fork parity: paper-0012 / Canvas global config).
        // Pushed here, before the server initializes, so the player data
        // folder sweep at PlayerDataStorage construction sees the flag.
        net.linear.save.SaveOffLease.setMaxDurationSeconds(net.linear.save.SaveOffLease.resolveMaxDurationSeconds(
            root.getLong("region-format.linear.save-off-max-duration", net.linear.save.SaveOffLease.DEFAULT_MAX_SECONDS),
            log::error));
        net.linear.io.PlayerFileDurability.setEnabled(root.getBoolean("region-format.linear.durable-player-data", true));
        // Coupled save keys (fork parity: paper-0013 / Canvas global config).
        HorizonCoupledSave.configure(
            root.getBoolean("region-format.linear.coupled-save.enabled", true),
            net.linear.save.CoupledSave.resolveIntervalSeconds(
                root.getLong("region-format.linear.coupled-save.interval", net.linear.save.CoupledSave.DEFAULT_INTERVAL_SECONDS),
                log::error));
        pushed = true;
    }

    /**
     * {@code worlds.<dimension-id>.format} / {@code .compression-level}.
     * Keys are dimension ids ({@code minecraft:the_nether}) or dimension
     * directory names; YAML keys containing ':' must be quoted.
     */
    static Map<String, LinearFileConfig.WorldValues> worldValues(final ConfigurationSection worlds) {
        final Map<String, LinearFileConfig.WorldValues> out = new HashMap<>();
        if (worlds == null) {
            return out;
        }
        for (final String key : worlds.getKeys(false)) {
            final ConfigurationSection w = worlds.getConfigurationSection(key);
            if (w == null) {
                continue;
            }
            final String format = w.getString("format", null);
            final Integer level = w.isInt("compression-level") ? w.getInt("compression-level") : null;
            if (format != null || level != null) {
                out.put(key, new LinearFileConfig.WorldValues(format, level));
            }
        }
        return out;
    }
}
