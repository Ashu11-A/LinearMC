package net.linear;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.linear.probe.AbstractRegionFileFactory;
import net.linear.probe.LinearFolderNames;
import net.linear.probe.LinearStorageProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Leg-neutral startup auto-conversion of one dimension, before any world
 * loads: every storage folder still holding {@code .mca} regions converts to
 * the dimension's linear-family target (fork contract: Folia
 * {@code paper-0011}, Canvas {@code LinearStartupConversion}). ANVIL targets
 * never convert. The caller registers the region openers first and halts the
 * server when protection trips.
 */
public final class StartupConversion {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    private StartupConversion() {
    }

    /** Effective target for one dimension (format already sysprop/per-world resolved). */
    public record Target(RegionFileFormat format, int level) {
    }

    /** Whether {@code folder} holds at least one {@code r.<x>.<z>.mca} (stops at the first). */
    public static boolean needsConversion(Path folder) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "r.*.mca")) {
            for (Path file : files) {
                if (LinearStorageProbe.parseRegionCoords(file.getFileName().toString()) != null) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
        return false;
    }

    /**
     * Converts every storage folder of {@code dimensionFolder} toward a
     * linear-family {@code target}.
     *
     * @return files converted, or {@code -1} when protection tripped (terminal
     *     alert logged with per-file detail; the caller must halt and abort boot)
     */
    public static int convertDimension(Path dimensionFolder, Target target) {
        if (target == null || !RegionFileFormat.isLinearFamily(target.format())) {
            return 0;
        }
        final int level = AbstractRegionFileFactory.clampCompressionLevel(target.level());
        int converted = 0;
        for (final String name : LinearFolderNames.STORAGE_FOLDERS) {
            final Path folder = dimensionFolder.resolve(name);
            if (!Files.isDirectory(folder) || !needsConversion(folder)) {
                continue;
            }
            LOGGER.info("[Linear] Startup conversion: converting {} to {} (level {})...", folder, target.format(), level);
            try {
                final LinearRegionConverter.ConversionSummary s = LinearRegionConverter.convertRegionFolderToFormat(
                    folder, level, new RetryLog(folder), true, target.format());
                LOGGER.info("[Linear] Startup conversion done: {} (converted={}, validated={}, deleted={}, failed={}).",
                    folder, s.converted(), s.validated(), s.deleted(), s.failed());
                converted += s.converted();
            } catch (final LinearRegionConverter.ConversionProtectionException ex) {
                final LinearRegionConverter.ConversionSummary s = ex.getSummary();
                LOGGER.error("[Linear] Startup conversion PROTECTION HALT: {} file(s) failed in {} after 3 attempts;"
                    + " stopping the server before worlds load (converted={}, validated={}, deleted={}, failed={}).",
                    s.failures().size(), folder, s.converted(), s.validated(), s.deleted(), s.failed());
                for (final LinearRegionConverter.FileResult f : s.failures()) {
                    LOGGER.error("[Linear] Startup conversion failure: {} -> {} [{}] {}",
                        f.source(), f.target(), f.status(), f.detail());
                }
                return -1;
            } catch (final RuntimeException ex) {
                // Fail closed per folder: the remaining .mca stay readable (existing-first probe).
                LOGGER.warn("[Linear] Startup conversion: unexpected error converting {}; skipping folder.", folder, ex);
            }
        }
        return converted;
    }

    /** Per-file progress stays quiet; retries warn. */
    private record RetryLog(Path folder) implements LinearRegionConverter.Listener {
        @Override
        public void onFile(LinearRegionConverter.FileResult result) {
        }

        @Override
        public void onRetry(Path source, int attempt, String reason) {
            LOGGER.warn("[Linear] Startup conversion: retry {}/3 for {} in {} ({})", attempt, source, this.folder, reason);
        }
    }
}
