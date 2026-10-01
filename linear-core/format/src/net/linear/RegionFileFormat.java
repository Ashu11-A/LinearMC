package net.linear;

/**
 * On-disk region format selector.
 * Unknown input parses to {@code INVALID} (see {@code fromString}).
 */
public enum RegionFileFormat {
    ANVIL,
    LINEAR,
    INVALID;

    /** On-disk extension for Anvil region files. */
    public static final String ANVIL_EXTENSION = ".mca";
    /** On-disk extension for Linear region files. */
    public static final String LINEAR_EXTENSION = ".linear";
    /** Shipped default compression level (v1.0.0+). */
    public static final int DEFAULT_COMPRESSION_LEVEL = 6;
    /** Maximum zstd level accepted. */
    public static final int MAX_COMPRESSION_LEVEL = 22;

    /**
     * Case-insensitive parse; unknown (or {@code null}) input yields
     * {@link #INVALID} so callers can fall back to {@link #ANVIL}.
     */
    public static RegionFileFormat fromString(String format) {
        if (format == null) {
            return RegionFileFormat.INVALID;
        }
        for (RegionFileFormat candidate : values()) {
            if (candidate.name().equalsIgnoreCase(format)) {
                return candidate;
            }
        }
        return RegionFileFormat.INVALID;
    }

    /**
     * Defensive level clamp: inside 1..22 returns the level, otherwise the
     * shipped default. Never throws (config fallback path).
     */
    public static int clampCompressionLevel(int level) {
        if (level < 1 || level > MAX_COMPRESSION_LEVEL) {
            return DEFAULT_COMPRESSION_LEVEL;
        }
        return level;
    }
}
