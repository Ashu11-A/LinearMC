package io.linearmc.horizon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import net.linear.RegionFileFormat;
import net.linear.config.LinearFileConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** config.yml (global + per-world) reaches the core file layer the mixins read. */
public class LinearConfigLoaderTest {

    @AfterEach
    void reset() {
        LinearFileConfig.setFileValues(null, null);
        LinearFileConfig.setFileWriterValues(null, null);
        LinearFileConfig.setFileSymlinkGuard(null);
        LinearFileConfig.setWorldValues(null);
    }

    @Test
    void shippedDefaultIsLinearAndWrittenOnFirstLoad(@TempDir Path dir) throws Exception {
        final YamlConfiguration cfg = LinearConfigLoader.loadOrCreate(dir);
        assertTrue(Files.isRegularFile(dir.resolve("config.yml")));
        LinearConfigLoader.push(cfg);
        assertEquals(RegionFileFormat.LINEAR, LinearFileConfig.resolveFormat());
        assertEquals(6, LinearFileConfig.resolveLevel());
    }

    @Test
    void perWorldOverridesResolveByStorageFolder() throws Exception {
        final YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(String.join("\n",
            "region-format:",
            "  format: LINEAR",
            "  linear:",
            "    compression-level: 6",
            "worlds:",
            "  \"minecraft:the_nether\":",
            "    format: ANVIL",
            "  \"minecraft:the_end\":",
            "    compression-level: 9",
            ""));
        LinearConfigLoader.push(cfg);
        assertEquals(RegionFileFormat.ANVIL,
            LinearFileConfig.resolveFormatFor("/srv/world/dimensions/minecraft/the_nether/region"));
        assertEquals(9, LinearFileConfig.resolveLevelFor("/srv/world/dimensions/minecraft/the_end/poi"));
        assertEquals(RegionFileFormat.LINEAR,
            LinearFileConfig.resolveFormatFor("/srv/world/dimensions/minecraft/overworld/entities"));
    }
}
