package net.linear.config;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.linear.LinearImplementation;
import net.linear.RegionFileFormat;
import net.linear.probe.LinearBucketGrid;
import net.linear.probe.LinearFolderNames;

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
    private static volatile String fileImplementationRaw;
    private static volatile Integer fileGridSizeRaw;
    private static volatile Boolean fileSymlinkGuardRaw;
    private static volatile Map<String, WorldValues> worldValues = Map.of();

    /**
     * Per-world file overrides ({@code null} field = inherit the global file
     * value). Keyed by {@link LinearFolderNames#worldKey}
     * (dimension id such as {@code minecraft:the_nether}, or the dimension
     * directory name).
     */
    public record WorldValues(String format, Integer level) {
    }

    private LinearFileConfig() {
    }

    /**
     * Pushes the raw file values ({@code null} when unset).
     */
    public static void setFileValues(String format, Integer level) {
        fileFormatRaw = format;
        fileLevelRaw = level;
    }

    /**
     * Pushes the raw writer file values ({@code null} when unset):
     * {@code region-format.linear.implementation} and
     * {@code region-format.linear.grid-size}. Additive: existing
     * {@link #setFileValues} call sites keep working.
     */
    public static void setFileWriterValues(String implementation, Integer gridSize) {
        fileImplementationRaw = implementation;
        fileGridSizeRaw = gridSize;
    }

    /** Pushes the per-world overrides (replaces the previous map; {@code null} clears). */
    public static void setWorldValues(Map<String, WorldValues> values) {
        worldValues = values == null ? Map.of() : Map.copyOf(values);
    }

    /** Per-world override for a storage folder, or {@code null}. */
    private static WorldValues worldValuesFor(String folder) {
        final Map<String, WorldValues> map = worldValues;
        if (map.isEmpty() || folder == null) {
            return null;
        }
        return map.get(LinearFolderNames.worldKey(folder));
    }

    /** Raw format for a storage folder: per-world override, else the global file value. */
    public static String fileFormatFor(String folder) {
        final WorldValues w = worldValuesFor(folder);
        return w != null && w.format() != null ? w.format() : fileFormatRaw;
    }

    /** Raw level for a storage folder: per-world override, else the global file value. */
    private static Integer fileLevelFor(String folder) {
        final WorldValues w = worldValuesFor(folder);
        return w != null && w.level() != null ? w.level() : fileLevelRaw;
    }

    /** Sysprop wins over the folder's (per-world, else global) file value, then the default. */
    public static RegionFileFormat resolveFormatFor(String folder) {
        return LinearPolicy.resolveFormat(
            System.getProperty(LinearPolicy.SYSPROP_FORMAT),
            fileFormatFor(folder),
            LinearPolicy.FALLBACK_FORMAT);
    }

    /** Sysprop wins over the folder's (per-world, else global) file level, then {@code 6}. */
    public static int resolveLevelFor(String folder) {
        return LinearPolicy.resolveLevel(
            System.getProperty(LinearPolicy.SYSPROP_LEVEL),
            fileLevelFor(folder),
            LinearPolicy.DEFAULT_LEVEL);
    }

    private static final AtomicBoolean WRITER_LOGGED =
        new AtomicBoolean();

    /**
     * Leg entry point for the writer keys ({@code region-format.linear.implementation}
     * and {@code .grid-size}): validates them (bad values warn once and fall
     * back to {@code V1} / {@code 8}, the documented messages), publishes them
     * to the core writer, and names every effective
     * {@code -Dlinearmc.implementation} / {@code -Dlinearmc.grid-size}
     * override once per process. Every leg calls this, so the keys behave the
     * same on the forks and on Horizon.
     */
    public static void pushWriterValues(String implementation, Integer gridSize,
        Consumer<String> warn, Consumer<String> info) {
        String impl = implementation;
        Integer grid = gridSize;
        final boolean first = WRITER_LOGGED.compareAndSet(false, true);
        if (impl != null && LinearImplementation.fromString(impl) == null) {
            if (first && warn != null) {
                warn.accept(LinearPolicy.badImplementationMessage(impl));
            }
            impl = null;
        }
        if (grid != null && !LinearBucketGrid.isValid(grid)) {
            if (first && warn != null) {
                warn.accept(LinearPolicy.badGridSizeMessage(grid));
            }
            grid = null;
        }
        setFileWriterValues(impl, grid);
        if (first && info != null) {
            overrideLineIfNeeded(LinearPolicy.SYSPROP_IMPLEMENTATION).ifPresent(info);
            overrideLineIfNeeded(LinearPolicy.SYSPROP_GRID_SIZE).ifPresent(info);
        }
    }

    /** Test hook: re-arm the once-per-process writer log. */
    static void resetWriterLogForTests() {
        WRITER_LOGGED.set(false);
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
        return resolveFormatFor(null);
    }

    /**
     * No-arg convenience: sysprop wins over the cached file value, which
     * wins over the shipped {@code 6} default.
     */
    public static int resolveLevel() {
        return resolveLevelFor(null);
    }

    /** Raw {@code region-format.linear.implementation} from the file, or {@code null}. */
    public static String fileImplementation() {
        return fileImplementationRaw;
    }

    /** Raw {@code region-format.linear.grid-size} from the file, or {@code null}. */
    public static Integer fileGridSize() {
        return fileGridSizeRaw;
    }

    /**
     * Pushes the raw file value ({@code null} when unset):
     * {@code region-format.linear.crash-on-broken-symlink}. Additive:
     * existing {@link #setFileValues} / {@link #setFileWriterValues} call
     * sites keep working.
     */
    public static void setFileSymlinkGuard(Boolean crashOnBrokenSymlink) {
        fileSymlinkGuardRaw = crashOnBrokenSymlink;
    }

    /** Raw {@code region-format.linear.crash-on-broken-symlink}, or {@code null}. */
    public static Boolean fileSymlinkGuard() {
        return fileSymlinkGuardRaw;
    }

    /**
     * No-arg convenience: the cached file value wins over the shipped
     * {@code true} default. Sysprop-free by design (there is no
     * {@code linearmc.*} override for this key): the file value wins when
     * non-null, else {@link LinearPolicy#DEFAULT_CRASH_ON_BROKEN_SYMLINK}.
     */
    public static boolean resolveCrashOnBrokenSymlink() {
        return LinearPolicy.resolveFlag(null, fileSymlinkGuardRaw,
            LinearPolicy.DEFAULT_CRASH_ON_BROKEN_SYMLINK);
    }

    /**
     * No-arg convenience: sysprop wins over the cached file value, which
     * wins over the shipped {@code V1} default.
     */
    public static LinearImplementation resolveImplementation() {
        return LinearPolicy.resolveImplementation(
            System.getProperty(LinearPolicy.SYSPROP_IMPLEMENTATION),
            fileImplementationRaw);
    }

    /**
     * No-arg convenience: sysprop wins over the cached file value, which
     * wins over the shipped {@code 8} default.
     */
    public static int resolveGridSize() {
        return LinearPolicy.resolveGridSize(
            System.getProperty(LinearPolicy.SYSPROP_GRID_SIZE),
            fileGridSizeRaw);
    }

    /**
     * Sysprop-override log line for {@code key}
     * ({@link LinearPolicy#SYSPROP_FORMAT},
     * {@link LinearPolicy#SYSPROP_LEVEL},
     * {@link LinearPolicy#SYSPROP_IMPLEMENTATION} or
     * {@link LinearPolicy#SYSPROP_GRID_SIZE}).
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
        if (LinearPolicy.SYSPROP_IMPLEMENTATION.equals(key)) {
            String fileValue = String.valueOf(fileImplementationRaw);
            if (raw.trim().equals(fileValue)) {
                return Optional.empty();
            }
            return Optional.of(LinearPolicy.overrideLine(
                key, raw, fileValue, resolveImplementation().name(), "server"));
        }
        if (LinearPolicy.SYSPROP_GRID_SIZE.equals(key)) {
            String fileValue = String.valueOf(fileGridSizeRaw);
            if (raw.trim().equals(fileValue)) {
                return Optional.empty();
            }
            return Optional.of(LinearPolicy.overrideLine(
                key, raw, fileValue, String.valueOf(resolveGridSize()), "server"));
        }
        return Optional.empty();
    }
}
