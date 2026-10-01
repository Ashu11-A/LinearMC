package io.linearmc.horizon;

import net.linear.RegionFileFormat;
import net.linear.config.LinearFileConfig;
import net.linear.config.LinearPolicy;

/**
 * Horizon-side counterpart of {@code AbstractRegionFileFactory#fromConfig()}.
 *
 * <p>Thin adapter over core {@link LinearFileConfig}: the file-value holder
 * plus resolution semantics (sysprop wins, unknown format → {@code ANVIL},
 * out-of-range level → the shipped fallback) are core-owned; this class keeps
 * the Horizon method names stable. NMS-free so it stays unit-testable outside
 * the Horizon dev environment.
 *
 * <p>The config.yml file layer is pushed once by {@code LinearPlugin#onEnable}
 * via {@link #setFileValues}; every selector reads it back through core.
 */
public final class LinearFormatPolicy {

    /** Shipped fallback level (mirrors the factory default). */
    public static final int FALLBACK_LEVEL = LinearPolicy.DEFAULT_LEVEL;

    private LinearFormatPolicy() {
    }

    /**
     * Pushes the raw config.yml file values ({@code null} when unset) into
     * the core holder. Called once by {@code LinearPlugin#onEnable}.
     */
    public static void setFileValues(String format, Integer level) {
        LinearFileConfig.setFileValues(format, level);
    }

    /** Raw {@code region-format.format} from config.yml, or {@code null}. */
    public static String fileFormat() {
        return LinearFileConfig.fileFormat();
    }

    /** Raw {@code region-format.linear.compression-level}, or {@code null}. */
    public static Integer fileLevel() {
        return LinearFileConfig.fileLevel();
    }

    /**
     * Case-insensitive parse; {@code null} or unknown input yields
     * {@link RegionFileFormat#ANVIL} (new-writes default; reads dual-probe
     * both extensions regardless).
     */
    public static RegionFileFormat resolveFormat(String configured) {
        return LinearPolicy.resolveFormat(configured, null, LinearPolicy.FALLBACK_FORMAT);
    }

    /**
     * File-aware resolution: sysprop wins over the config.yml value, which
     * wins over {@code defaultValue} (fail-open {@code ANVIL}).
     */
    public static RegionFileFormat resolveFormat(
            String syspropValue, String fileValue, RegionFileFormat defaultValue) {
        return LinearPolicy.resolveFormat(syspropValue, fileValue, defaultValue);
    }

    /**
     * Runtime-override composition for live selectors (reverse jobs flip a
     * storage scope to {@code ANVIL} via
     * {@link net.linear.config.LinearFormatOverride} so new writes land in
     * {@code .mca} while unconverted {@code .linear} files keep serving
     * reads through dual-read). The first non-null override across
     * {@code scopes} (most specific first: storage-folder path, then world
     * name) wins over the config.yml file value; the sysprop still wins
     * overall.
     */
    public static RegionFileFormat resolveEffectiveFormat(String fileValue, String... scopes) {
        return LinearPolicy.resolveFormatWithOverride(
            System.getProperty(LinearPolicy.SYSPROP_FORMAT),
            fileValue,
            LinearPolicy.FALLBACK_FORMAT,
            scopes);
    }

    /**
     * Returns {@code configured} when it is inside zstd 1..22, else
     * {@link #FALLBACK_LEVEL}. Never throws.
     */
    public static int clampLevel(int configured) {
        return RegionFileFormat.clampCompressionLevel(configured);
    }

    /**
     * File-aware level: sysprop wins over the config.yml value, which wins
     * over {@code defaultValue} (shipped {@code 6}). Never throws.
     */
    public static int resolveLevel(String syspropValue, Integer fileValue, int defaultValue) {
        return LinearPolicy.resolveLevel(syspropValue, fileValue, defaultValue);
    }
}
