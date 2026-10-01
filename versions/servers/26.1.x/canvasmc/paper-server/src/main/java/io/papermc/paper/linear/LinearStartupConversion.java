package io.papermc.paper.linear;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Map;
import net.linear.AbstractRegionFileFactory;
import net.linear.LinearRegionConverter;
import net.linear.command.JobQueue;
import net.linear.config.LinearPolicy;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.worldupdate.RegionStorageUpgrader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * Linear startup auto-conversion: pre-plugin boot pass (B2; paper-0011).
 *
 * <p>Runs synchronously on the main boot thread after the world-config init
 * and before any plugin code executes (trigger call sits in
 * {@code DedicatedServer.initServer} between the Canvas pre-start config
 * hook and {@code loadPlugins}).
 * For every dimension, the world target format is resolved from the Canvas
 * world files directly ({@code config/canvas-worlds.yml} defaults overlaid
 * by {@code <dimension>/canvas-patch.yml}; only the two Linear keys are
 * read, everything else ignored) — never from a level instance (no
 * {@code ServerLevel} exists yet) and never from Paper's world config
 * (dormant on this leg). When the target
 * is LINEAR and old-format ({@code .mca}) files are present, each storage
 * folder ({@code region/}, {@code entities/}, {@code poi/}) converts via the
 * NMS entry point, which fronts the retry machinery. A folder that exhausts
 * retries raises the protection exception: the pass logs a terminal alert
 * with full per-file detail, halts the server, and returns {@code false} so
 * the caller aborts boot before plugins load.</p>
 *
 * <p>P1 safety: explicit-ANVIL worlds (and unreadable configs, which fail
 * closed to ANVIL) never convert; dual-read keeps serving them. The effective
 * format is sysprop-aware (unified {@code LinearPolicy}: {@code -Dlinearmc.format}
 * wins over the world file, then the shipped LINEAR default), so
 * {@code -Dlinearmc.format=ANVIL} suppresses conversion.</p>
 *
 * <p>HIGH-4 halt-aborts-boot guarantee: a {@code false} return means the
 * server was halted by a protection trip, and the {@code DedicatedServer}
 * call site returns {@code false} from {@code initServer} at once, so NO
 * plugin code ({@code STARTUP} or {@code POSTWORLD}) executes afterwards.</p>
 */
public final class LinearStartupConversion {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    // Storage folders converted per dimension (folder names match WorldUpgrader).
    private static final List<String> STORAGE_FOLDERS = List.of("region", "entities", "poi");

    private LinearStartupConversion() {
    }

    /**
     * Conversion pass entry point. Synchronous and blocking: boot waits while
     * folders convert (no scheduler exists yet and no plugin is loaded, so
     * correctness requires blocking the boot thread here).
     *
     * @return {@code true} when the pass finished without tripping protection
     *     (boot may continue to plugin load); {@code false} when a protection
     *     trip halted the server — the caller must abort boot immediately.
     */
    public static boolean runPrePluginConversion(final MinecraftServer server) {
        // The /linear convert queue is in-memory only: name the (empty at
        // boot) drop count so operators know a restart discards queued jobs.
        LOGGER.info(JobQueue.droppedByRestartLine(0));
        final var stemRegistry = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        for (final LevelStem stem : stemRegistry) {
            if (!server.isRunning()) {
                // Protection halt fired in an earlier dimension: stop the pass.
                return false;
            }
            final ResourceKey<LevelStem> stemKey = stemRegistry.getResourceKey(stem).orElse(null);
            if (stemKey == null) {
                continue;
            }
            if (!convertDimension(server, stemKey)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts one dimension's storage folders when its target is LINEAR.
     *
     * @return {@code false} when protection tripped (terminal alert logged and
     *     server halted; boot must abort, no further folders or dimensions run).
     */
    private static boolean convertDimension(final MinecraftServer server, final ResourceKey<LevelStem> stemKey) {
        final ResourceKey<Level> dimensionKey = io.papermc.paper.world.PaperWorldLoader.dimensionKey(stemKey);
        final Path worldFolder = server.storageSource.getDimensionPath(dimensionKey);
        final CanvasTarget canvasTarget = readCanvasTarget(worldFolder, Path.of("config/canvas-worlds.yml"));
        if (canvasTarget == null) {
            // Config unreadable: fail closed (treat as ANVIL, convert nothing).
            LOGGER.warn("[Linear] Startup conversion: could not read world config for {}, skipping (treating as ANVIL).", worldFolder);
            return true;
        }
        // P1 SAFETY INVARIANT (negative assertion): resolveTargetFormat returns
        // ANVIL for explicit-ANVIL worlds (and for unset/invalid values), and
        // conversion below runs ONLY when the returned target is LINEAR. Hence
        // NO explicit-ANVIL path can reach conversion: dual-read (.mca
        // preferred, negative cache means neither) keeps serving those worlds.
        // The canvas target already carries the core enum (no mapping step).
        final net.linear.RegionFileFormat target = RegionStorageUpgrader.resolveTargetFormat(
            worldFolder, canvasTarget.format);
        if (target != net.linear.RegionFileFormat.LINEAR) {
            return true;
        }
        // Same configured level the live write side uses (B1's tuned default flows
        // via the shared constant, never a literal). Sysprop-aware: unified
        // LinearPolicy lets -Dlinearmc.compression-level win over the file.
        final String sysLevel = System.getProperty(LinearPolicy.SYSPROP_LEVEL);
        final int compressionLevel = LinearPolicy.resolveLevel(
            sysLevel, canvasTarget.compressionLevel,
            net.linear.RegionFileFormat.DEFAULT_COMPRESSION_LEVEL);
        if (sysLevel != null) {
            LOGGER.info("[Linear] Startup conversion: sysprop {}={} overrides world file level (file='{}') for {} -> effective {}.",
                LinearPolicy.SYSPROP_LEVEL, sysLevel, canvasTarget.compressionLevel, worldFolder, compressionLevel);
        }
        for (final String folderName : STORAGE_FOLDERS) {
            final Path folder = worldFolder.resolve(folderName);
            if (!Files.isDirectory(folder) || !RegionStorageUpgrader.needsConversion(folder)) {
                continue;
            }
            LOGGER.info("[Linear] Startup conversion: converting {} to LINEAR (level {})...", folder, compressionLevel);
            try {
                final LinearRegionConverter.ConversionSummary summary = RegionStorageUpgrader.convertFolderToLinear(
                    folder, compressionLevel, new LogListener(folder));
                LOGGER.info("[Linear] Startup conversion done: {} (converted={}, validated={}, deleted={}, failed={}).",
                    folder, summary.converted(), summary.validated(), summary.deleted(), summary.failed());
            } catch (final LinearRegionConverter.ConversionProtectionException ex) {
                // Terminal alert: full per-file detail from the summary, then
                // HALT before plugins load (same halt call as the
                // broken-symlink guard in RegionFileStorage). Returning false
                // makes the DedicatedServer call site abort initServer, so no
                // plugin code runs after this trip.
                final LinearRegionConverter.ConversionSummary summary = ex.getSummary();
                LOGGER.error("[Linear] Startup conversion PROTECTION HALT: {} file(s) failed in {} after 3 attempts;"
                    + " stopping the server before plugins load (converted={}, validated={}, deleted={}, failed={}).",
                    summary.failures().size(), folder,
                    summary.converted(), summary.validated(), summary.deleted(), summary.failed());
                for (final LinearRegionConverter.FileResult failure : summary.failures()) {
                    LOGGER.error("[Linear] Startup conversion failure: {} -> {} [{}] {}",
                        failure.source(), failure.target(), failure.status(), failure.detail());
                }
                server.halt(false);
                return false;
            } catch (final RuntimeException ex) {
                // Unexpected error: fail closed per folder (remaining .mca files
                // stay dual-readable; nothing hidden, nothing half-wired).
                LOGGER.error("[Linear] Startup conversion: unexpected error converting {}; skipping folder.", folder, ex);
            }
        }
        return true;
    }

    // Resolved per-dimension target from the Canvas world files. Only the two
    // Linear keys are read ({@code region-format.format},
    // {@code region-format.compression-level}); everything else is ignored so
    // Canvas-side renames elsewhere never break conversion. Merge order:
    // {@code <dimension>/canvas-patch.yml} over {@code config/canvas-worlds.yml}
    // over live defaults (LINEAR, 6 — identical to the write side). Null when
    // a file is UNREADABLE (fail closed to ANVIL at the call site); absent
    // files/keys resolve to the live defaults (a fresh world has no .mca
    // either way, so no conversion can trigger); present-but-unknown values
    // fail closed to ANVIL. Public for unit tests (pure file logic, no
    // server needed); the boot path passes the live defaults file explicitly
    // so tests can point at temp dirs (no cwd dependence).
    public record CanvasTarget(net.linear.RegionFileFormat format, int compressionLevel) {}

    public static CanvasTarget readCanvasTarget(final Path worldFolder, final Path defaultsFile) {
        try {
            final Yaml yaml = new Yaml();
            Map<String, Object> merged = readSection(yaml, defaultsFile);
            final Map<String, Object> patch = readSection(yaml, worldFolder.resolve("canvas-patch.yml"));
            if (patch != null) {
                merged = new java.util.HashMap<>(merged);
                merged.putAll(patch);
            }
            final Object rawFormat = merged.get("format");
            final String fileValue = rawFormat != null ? rawFormat.toString() : null;
            // Sysprop-aware effective format (unified LinearPolicy: sysprop
            // wins, then the world file, then the default). Absent keys mean
            // "fresh world, live defaults" (LINEAR); present-but-garbage
            // fails closed to ANVIL. In particular -Dlinearmc.format=ANVIL
            // resolves ANVIL here, so the LINEAR target-gate below skips
            // conversion.
            final String sysprop = System.getProperty(LinearPolicy.SYSPROP_FORMAT);
            final String effectiveFileValue = fileValue != null ? fileValue : "LINEAR";
            final net.linear.RegionFileFormat format = LinearPolicy.resolveFormat(
                sysprop, effectiveFileValue, net.linear.RegionFileFormat.ANVIL);
            if (sysprop != null) {
                LOGGER.info("[Linear] Startup conversion: sysprop {}={} overrides world file format (file='{}') for {} -> effective {}.",
                    LinearPolicy.SYSPROP_FORMAT, sysprop, fileValue, worldFolder, format);
            } else if (fileValue != null
                    && net.linear.RegionFileFormat.fromString(fileValue)
                        == net.linear.RegionFileFormat.INVALID) {
                // Unknown file value fails closed to ANVIL — keep it loud.
                LOGGER.warn("[Linear] Startup conversion: unknown region-format.format '{}' for {}; failing closed to ANVIL.",
                    fileValue, worldFolder);
            }
            int level = AbstractRegionFileFactory.DEFAULT_COMPRESSION_LEVEL;
            final Object rawLevel = merged.get("compression-level");
            if (rawLevel instanceof Number number) {
                level = AbstractRegionFileFactory.clampCompressionLevel(number.intValue());
            }
            return new CanvasTarget(format, level);
        } catch (final Exception ex) {
            LOGGER.warn("[Linear] Startup conversion: failed to read world config for {}", worldFolder, ex);
            return null;
        }
    }

    // Reads ONLY the region-format section (null when the file is absent;
    // throws when present-but-unparseable so the caller fails closed).
    // NOTE: an EMPTY file parses to null and means "no overrides" (Canvas
    // itself auto-creates empty per-dimension patch files) — only a
    // present-but-malformed structure throws.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readSection(final Yaml yaml, final Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        final Object loaded;
        try (final var in = Files.newInputStream(file)) {
            loaded = yaml.load(in);
        }
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalStateException("top-level mapping expected");
        }
        final Object section = root.get("region-format");
        if (section == null) {
            return Map.of();
        }
        if (!(section instanceof Map<?, ?> map)) {
            throw new IllegalStateException("region-format mapping expected");
        }
        return (Map<String, Object>) map;
    }

    // Listener for the conversion contract: per-file progress stays at debug
    // (folder totals log at INFO at the call site); retries always warn.
    private static final class LogListener implements LinearRegionConverter.Listener {
        private final Path folder;

        private LogListener(final Path folder) {
            this.folder = folder;
        }

        @Override
        public void onFile(final LinearRegionConverter.FileResult result) {
            LOGGER.debug("[Linear] Startup conversion: {} -> {} [{}]",
                result.source(), result.target(), result.status());
        }

        @Override
        public void onRetry(final Path source, final int attempt, final String reason) {
            LOGGER.warn("[Linear] Startup conversion: retry {}/3 for {} in {} ({})",
                attempt, source, this.folder, reason);
        }
    }
}
