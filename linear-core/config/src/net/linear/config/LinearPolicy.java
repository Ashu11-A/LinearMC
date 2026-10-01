package net.linear.config;

import net.linear.RegionFileFormat;

/**
 * Unified resolution policy: system property wins over file config, which
 * wins over the shipped default. Pure JDK plus format; owns the
 * {@code linearmc.*} sysprop semantics shared by the Horizon plugin
 * ({@code LinearFormatPolicy} / {@code RegionFileStorageMixin} /
 * {@code LinearPlugin}) and the Paper {@code @PostProcess} fallbacks.
 */
public final class LinearPolicy {

    /** Sysprop selecting the world format ({@code ANVIL} or {@code LINEAR}). */
    public static final String SYSPROP_FORMAT = "linearmc.format";
    /** Sysprop overriding the zstd compression level (1..22). */
    public static final String SYSPROP_LEVEL = "linearmc.compression-level";

    /** Shipped default: new writes are Linear (see release/region-format.yml). */
    public static final RegionFileFormat DEFAULT_FORMAT = RegionFileFormat.LINEAR;
    /**
     * Fallback for unknown values: reads probe {@code .mca} first, and the
     * file-layer {@code @PostProcess} substitutes {@code ANVIL} with a log
     * line, so an unrecognised {@code format:} never silently enables Linear
     * writes.
     */
    public static final RegionFileFormat FALLBACK_FORMAT = RegionFileFormat.ANVIL;
    /** Shipped default compression level (v1.0.0+). */
    public static final int DEFAULT_LEVEL = 6;
    /** Shipped default age-based flush gate, seconds. */
    public static final int DEFAULT_FLUSH_FREQUENCY = 10;
    /** Shipped default flush threads (serial path). */
    public static final int DEFAULT_FLUSH_THREADS = 1;
    /** Shipped default zstd workers (0 = serial). */
    public static final int DEFAULT_WORKERS = 0;
    /** Shipped default zstd LDM windowLog (0 = off). */
    public static final int DEFAULT_LDM = 0;
    /** Shipped default: halt on a broken Linear symlink (data-loss guard). */
    public static final boolean DEFAULT_CRASH_ON_BROKEN_SYMLINK = true;
    /** Shipped default: flush-batch logging stays inert (warn-only). */
    public static final boolean DEFAULT_LOG_FLUSH_BATCHES = false;

    private LinearPolicy() {
    }

    /**
     * File layer: accepts only the exact strings {@code "ANVIL"} and
     * {@code "LINEAR"} (case-sensitive, after trimming). {@code null} or
     * anything else yields {@code defaultValue}, mirroring
     * docs/configuration.md: the comparison is case-sensitive so lowercase
     * {@code linear} is unknown.
     */
    public static RegionFileFormat resolveFileFormat(String fileValue, RegionFileFormat defaultValue) {
        if (fileValue != null) {
            String trimmed = fileValue.trim();
            if ("ANVIL".equals(trimmed)) {
                return RegionFileFormat.ANVIL;
            }
            if ("LINEAR".equals(trimmed)) {
                return RegionFileFormat.LINEAR;
            }
        }
        return defaultValue;
    }

    /**
     * Sysprop (when non-null) wins via case-insensitive parse; the file value
     * goes through the case-sensitive file layer. Composition: sysprop
     * present (non-null) and blank (empty/whitespace) is present-but-bad and
     * falls through to the file layer like BOGUS (never straight to the
     * default, so a live override/file value still wins); sysprop valid
     * yields it; sysprop invalid falls back to the file layer (not straight
     * to the default); sysprop absent ({@code null}) yields the file layer.
     */
    public static RegionFileFormat resolveFormat(String syspropValue, String fileValue,
            RegionFileFormat defaultValue) {
        if (syspropValue != null) {
            String trimmed = syspropValue.trim();
            if (trimmed.isEmpty()) {
                return resolveFileFormat(fileValue, defaultValue);
            }
            RegionFileFormat parsed = RegionFileFormat.fromString(trimmed);
            if (parsed != RegionFileFormat.INVALID) {
                return parsed;
            }
            return resolveFileFormat(fileValue, defaultValue);
        }
        return resolveFileFormat(fileValue, defaultValue);
    }

    /**
     * Runtime-override composition for live resolution sites (reverse jobs
     * flip a world/folder to {@code ANVIL} via
     * {@link LinearFormatOverride} so new writes land in {@code .mca}).
     * The first non-null override across {@code scopes} (most specific
     * first: storage-folder path, then world name) wins over the file
     * value; the sysprop still wins overall.
     */
    public static RegionFileFormat resolveFormatWithOverride(String syspropValue, String fileValue,
            RegionFileFormat defaultValue, String... scopes) {
        String override = null;
        if (scopes != null) {
            for (String scope : scopes) {
                override = LinearFormatOverride.get(scope);
                if (override != null) {
                    break;
                }
            }
        }
        String effective = override != null ? override : fileValue;
        return resolveFormat(syspropValue, effective, defaultValue);
    }

    /**
     * Sysprop (when non-null) wins: trimmed, parsed as int. Blank
     * (empty/whitespace) is present-but-bad and falls through to the file
     * value like BOGUS (never straight to the default, so a live file value
     * still wins). Unparsable or out-of-range (outside 1..22) sysprop falls
     * back to the file value (clamped the same way), and only then to
     * {@code defaultValue}; a {@code null} sysprop reads the file value
     * directly. Callers can log the rejection via
     * {@link #syspropRejected(String, String)}.
     */
    public static int resolveLevel(String syspropValue, Integer fileValue, int defaultValue) {
        if (syspropValue != null) {
            String trimmed = syspropValue.trim();
            if (trimmed.isEmpty()) {
                return fileLevelOrDefault(fileValue, defaultValue);
            }
            final int parsed;
            try {
                parsed = Integer.parseInt(trimmed);
            } catch (NumberFormatException bad) {
                return fileLevelOrDefault(fileValue, defaultValue);
            }
            if (parsed < 1 || parsed > RegionFileFormat.MAX_COMPRESSION_LEVEL) {
                return fileLevelOrDefault(fileValue, defaultValue);
            }
            return parsed;
        }
        return fileLevelOrDefault(fileValue, defaultValue);
    }

    private static int fileLevelOrDefault(Integer fileValue, int defaultValue) {
        if (fileValue != null
                && fileValue >= 1
                && fileValue <= RegionFileFormat.MAX_COMPRESSION_LEVEL) {
            return fileValue;
        }
        return defaultValue;
    }

    /**
     * Boolean file flags (crash-on-broken-symlink, default true;
     * log-flush-batches, default false and inert). There are currently no
     * sysprops for these keys; the {@code syspropValue} parameter is
     * accepted-but-ignored for forward compatibility so a future
     * {@code linearmc.*} override can slot in without changing call sites.
     * Today the file value wins when non-null, else {@code defaultValue}.
     *
     * @param syspropValue reserved for a future sysprop override, ignored
     * @param fileValue value from the file config, may be {@code null}
     * @param defaultValue shipped default
     */
    public static boolean resolveFlag(String syspropValue, Boolean fileValue, boolean defaultValue) {
        return fileValue != null ? fileValue : defaultValue;
    }

    /** Below 1 falls back to {@link #DEFAULT_FLUSH_FREQUENCY}. */
    public static int clampFrequency(long v) {
        if (v < 1) {
            return DEFAULT_FLUSH_FREQUENCY;
        }
        return (int) Math.min(v, Integer.MAX_VALUE);
    }

    /**
     * Kaiiju relative-value semantics: a negative value means
     * {@code ncpu + v}, floored at 1; otherwise at least 1. A non-positive
     * {@code ncpu} is treated as 1 (no reliable core count available).
     */
    public static int resolveThreads(int v, int ncpu) {
        int cores = ncpu <= 0 ? 1 : ncpu;
        if (v < 0) {
            return Math.max(cores + v, 1);
        }
        return Math.max(v, 1);
    }

    /** Negative values fall back; zero and up pass through. */
    public static int clampNonNegative(int v, int fallback) {
        return v < 0 ? fallback : v;
    }

    /**
     * Sysprop-override log line.
     */
    public static String overrideLine(String key, String raw, String fileValue, String effective,
            String scope) {
        return "[region-format] sysprop " + key + "=" + raw + " overrides file " + fileValue
                + " -> effective " + effective + " (" + scope + ")";
    }

    /**
     * Rejection line for an unparsable sysprop: the file value stays in
     * effect.
     */
    public static String syspropRejected(String key, String raw) {
        return "[region-format] Ignoring invalid sysprop " + key + "=" + raw + ", using file value.";
    }

    /** File fallback: unknown {@code format:} value (byte-exact with docs). */
    public static String unknownFormatMessage() {
        return "[region-format] Unknown region format, expected ANVIL or LINEAR. Falling back to ANVIL.";
    }

    /** File fallback: out-of-range or unparseable compression level. Unifies to 6. */
    public static String badLevelMessage(Object offending) {
        return "[region-format] linear.compression-level must be 1-22, got " + offending
                + ". Falling back to 6.";
    }

    /** File fallback: flush frequency below 1. */
    public static String badFrequencyMessage(Object offending) {
        return "[region-format] linear.flush-frequency must be >= 1, got " + offending
                + ". Falling back to 10.";
    }

    /** File fallback: negative compression workers. */
    public static String badWorkersMessage(Object offending) {
        return "[region-format] linear.compression-workers must be >= 0, got " + offending
                + ". Falling back to 0.";
    }

    /** File fallback: negative long-distance-matching windowLog. */
    public static String badLdmMessage(Object offending) {
        return "[region-format] linear.long-distance-matching must be >= 0, got " + offending
                + ". Falling back to 0.";
    }
}
