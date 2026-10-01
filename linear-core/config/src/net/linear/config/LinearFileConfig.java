package net.linear.config;

import java.util.Objects;
import java.util.Optional;
import net.linear.RegionFileFormat;

/**
 * Core-owned file-layer cache: raw {@code region-format} file values pushed
 * once at startup, read back by selectors that cannot reference the
 * platform plugin class.
 *
 * <p>Pure JDK plus format: no NMS, no codec dependency. Resolution semantics
 * stay in {@link LinearPolicy} (sysprop wins over file, which wins over the
 * shipped fail-open defaults); this class owns the cached file values and
 * the no-arg conveniences over them.
 */
public final class LinearFileConfig {

    private static volatile String fileFormatRaw;
    private static volatile Integer fileLevelRaw;

    private LinearFileConfig() {
    }

    /**
     * Pushes the raw file values ({@code null} when unset).
     */
    public static void setFileValues(String format, Integer level) {
        fileFormatRaw = format;
        fileLevelRaw = level;
    }

    /** Raw {@code region-format.format} from the file, or {@code null}. */
    public static String fileFormat() {
        return fileFormatRaw;
    }

    /** Raw {@code region-format.linear.compression-level}, or {@code null}. */
    public static Integer fileLevel() {
        return fileLevelRaw;
    }

    /**
     * No-arg convenience: sysprop wins over the cached file value, which
     * wins over the shipped fail-open {@code ANVIL} default.
     */
    public static RegionFileFormat resolveFormat() {
        return LinearPolicy.resolveFormat(
            System.getProperty(LinearPolicy.SYSPROP_FORMAT),
            fileFormatRaw,
            LinearPolicy.FALLBACK_FORMAT);
    }

    /**
     * No-arg convenience: sysprop wins over the cached file value, which
     * wins over the shipped {@code 6} default.
     */
    public static int resolveLevel() {
        return LinearPolicy.resolveLevel(
            System.getProperty(LinearPolicy.SYSPROP_LEVEL),
            fileLevelRaw,
            LinearPolicy.DEFAULT_LEVEL);
    }

    /**
     * Sysprop-override log line for {@code key}
     * ({@link LinearPolicy#SYSPROP_FORMAT} or
     * {@link LinearPolicy#SYSPROP_LEVEL}).
     *
     * <p>Present iff the sysprop is set (non-null) and its trimmed value
     * differs from the cached file value ({@code String.valueOf}, matching
     * the fork overlays); otherwise empty. Unknown keys yield empty. The
     * effective value comes from the matching no-arg resolver, scope is
     * fixed to {@code "server"}.
     */
    public static Optional<String> overrideLineIfNeeded(String key) {
        Objects.requireNonNull(key, "key");
        String raw = System.getProperty(key);
        if (raw == null) {
            return Optional.empty();
        }
        if (LinearPolicy.SYSPROP_FORMAT.equals(key)) {
            String fileValue = String.valueOf(fileFormatRaw);
            if (raw.trim().equals(fileValue)) {
                return Optional.empty();
            }
            return Optional.of(LinearPolicy.overrideLine(
                key, raw, fileValue, resolveFormat().name(), "server"));
        }
        if (LinearPolicy.SYSPROP_LEVEL.equals(key)) {
            String fileValue = String.valueOf(fileLevelRaw);
            if (raw.trim().equals(fileValue)) {
                return Optional.empty();
            }
            return Optional.of(LinearPolicy.overrideLine(
                key, raw, fileValue, String.valueOf(resolveLevel()), "server"));
        }
        return Optional.empty();
    }
}
