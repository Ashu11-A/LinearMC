package io.linearmc.horizon;

import net.linear.LinearImplementation;
import net.linear.RegionFileFormat;
import net.linear.config.LinearFileConfig;
import net.linear.config.LinearPolicy;
import net.linear.probe.AbstractRegionFileFactory;

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

    /**
     * Pushes the raw writer file values ({@code null} when unset):
     * {@code region-format.linear.implementation} and
     * {@code region-format.linear.grid-size}. Called once by
     * {@code LinearPlugin#onEnable}.
     */
    public static void setFileWriterValues(String implementation, Integer gridSize) {
        LinearFileConfig.setFileWriterValues(implementation, gridSize);
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
     * {@link RegionFileFormat#ANVIL} (new-writes default; reads triple-probe
     * all extensions regardless).
     */
    public static RegionFileFormat resolveFormat(String configured) {
        return LinearPolicy.resolveFormat(configured, null, LinearPolicy.FALLBACK_FORMAT);
    }

    /**
     * Write implementation: sysprop wins over the config.yml value, which
     * wins over {@code V1}.
     */
    public static LinearImplementation resolveImplementation() {
        return LinearFileConfig.resolveImplementation();
    }

    /**
     * Bucket grid size: sysprop wins over the config.yml value, which wins
     * over {@code 8}.
     */
    public static int resolveGridSize() {
        return LinearFileConfig.resolveGridSize();
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

    /**
     * Flush width for the shared pool behind
     * {@code region-format.linear.flush-max-threads} (docs default 1):
     * Kaiiju relative-value semantics (negative means
     * {@code ncpu + raw}, floored at 1; otherwise at least 1). Pure mapping
     * for the {@code LinearFlushCoordinator} threads supplier; never throws.
     */
    public static int resolveFlushThreads(int raw, int ncpu) {
        return LinearPolicy.resolveThreads(raw, ncpu);
    }

    /**
     * Age gate in seconds behind
     * {@code region-format.linear.flush-frequency} (docs default 10): below
     * 1 falls back to 10. Pure mapping for the
     * {@code LinearFlushCoordinator} frequency supplier; never throws.
     */
    public static int resolveFlushFrequency(long raw) {
        return LinearPolicy.clampFrequency(raw);
    }

    /**
     * zstd worker count behind
     * {@code region-format.linear.compression-workers} (docs default 0 =
     * serial): negative falls back to 0. Pure mapping for the codec workers
     * supplier; never throws.
     */
    public static int resolveCompressionWorkers(int raw) {
        return LinearPolicy.clampNonNegative(raw, LinearPolicy.DEFAULT_WORKERS);
    }

    /**
     * zstd LDM windowLog behind
     * {@code region-format.linear.long-distance-matching} (docs default 0 =
     * off): negative falls back to 0. Pure mapping for the codec LDM
     * supplier; never throws.
     */
    public static int resolveLongDistanceMatching(int raw) {
        return LinearPolicy.clampNonNegative(raw, LinearPolicy.DEFAULT_LDM);
    }
}
