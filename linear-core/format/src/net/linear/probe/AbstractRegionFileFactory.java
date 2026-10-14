package net.linear.probe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import net.linear.AbstractRegionFile;
import net.linear.BufferedLinearRegionFile;
import net.linear.LinearRegionFile;
import net.linear.RegionFileFormat;

/**
 * Construction seam between {@code RegionFileStorage} (and the world
 * upgrader) and the concrete region-file implementations.
 *
 * <p>Dispatch rule: file names ending in {@code .b_linear} produce the
 * buffered implementation via the injected {@link BufferedOpener}; names
 * ending in {@code .linear} produce {@code net.linear.LinearRegionFile}
 * (V1/V2/V3/Pumpkin resolved by the detector at open); anything else takes
 * the vanilla anvil path via the injected {@link AnvilOpener}. This class is NMS-free
 * by design: the anvil opener receives the storage info (NMS
 * {@code RegionStorageInfo}) as {@code Object} and the leg-provided opener
 * casts it back, so core compiles and loads with zero NMS or Paper API
 * types on the classpath.</p>
 *
 * <p>Two overloads are provided. The 3-arg form is the exact shared
 * contract signature ({@code get(Path file, Object info, int
 * compressionLevel)}); it derives {@code folder = file.getParent()} and
 * defaults {@code sync} to {@code false}. Dispatch sites that own a
 * {@code RegionFileStorage} MUST call the 5-arg form so the anvil branch
 * inherits the storage's real folder/sync settings.</p>
 *
 * <p>Paper config mapping note: this class no longer names the Paper config
 * format enum. Legs map their Paper config value to the core
 * {@link RegionFileFormat} enum inline (Canvas {@code RegionFileFormat::map},
 * Horizon {@code LinearFormatPolicy}); core-side parsing stays string-based
 * via {@link RegionFileFormat#fromString}.</p>
 */
public final class AbstractRegionFileFactory {

    // Linear - single-sourced in net.linear.RegionFileFormat (core); aliases kept
    // so existing call sites (storage, upgrader, converter trigger) keep compiling.
    public static final String LINEAR_EXTENSION = RegionFileFormat.LINEAR_EXTENSION;
    public static final String ANVIL_EXTENSION = RegionFileFormat.ANVIL_EXTENSION;
    public static final String B_LINEAR_EXTENSION = RegionFileFormat.B_LINEAR_EXTENSION;

    /** Fallback compression when the configured level is out of range. */
    public static final int DEFAULT_COMPRESSION_LEVEL = RegionFileFormat.DEFAULT_COMPRESSION_LEVEL;
    /** Inclusive upper bound (matches Kaiiju 0003 range 1..22). */
    public static final int MAX_COMPRESSION_LEVEL = RegionFileFormat.MAX_COMPRESSION_LEVEL;

    private AbstractRegionFileFactory() {}

    /**
     * Opens a vanilla anvil region file. Implemented by the legs (which own
     * NMS): {@code info} is the NMS {@code RegionStorageInfo} passed through
     * as {@code Object} to keep core NMS-free; the leg opener casts it back
     * and returns its NMS {@code RegionFile} (which implements
     * {@link AbstractRegionFile} on the leg classpath).
     */
    public interface AnvilOpener {
        AbstractRegionFile open(Object info, Path file, Path externalDir, boolean sync) throws IOException;
    }

    private static volatile AnvilOpener anvilOpener = (info, file, externalDir, sync) -> {
        throw new IOException("no AnvilOpener registered");
    };

    /** Legs register their NMS anvil opener here at startup. */
    public static void linear$setAnvilOpener(AnvilOpener opener) {
        anvilOpener = Objects.requireNonNull(opener, "opener");
    }

    /**
     * Opens a Linear region file. Symmetric with {@link AnvilOpener}: the
     * implementation lives in codec ({@code LinearRegionFile}), which
     * format must not depend on (dependency would be cyclic), so legs
     * register it at startup — typically alongside the anvil opener in
     * {@code linear$wireCoreSeams()}. The default throws, loudly, instead of
     * silently producing an anvil file.
     */
    public interface LinearOpener {
        AbstractRegionFile open(Path file, int compressionLevel) throws IOException;
    }

    private static volatile LinearOpener linearOpener = (file, level) -> {
        throw new IOException("no LinearOpener registered");
    };

    /** Legs register {@code LinearRegionFile::new} here at startup. */
    public static void linear$setLinearOpener(LinearOpener opener) {
        linearOpener = Objects.requireNonNull(opener, "opener");
    }

    /**
     * Opens a buffered-linear region file. Symmetric with {@link LinearOpener}:
     * the implementation lives in codec ({@code BufferedLinearRegionFile}),
     * which format must not depend on, so legs register it at startup. The
     * default throws, loudly, instead of silently producing another format.
     */
    public interface BufferedOpener {
        AbstractRegionFile open(Path file, int compressionLevel) throws IOException;
    }

    private static volatile BufferedOpener bufferedOpener = (file, level) -> {
        throw new IOException("no BufferedOpener registered");
    };

    /** Legs register {@code BufferedLinearRegionFile::new} here at startup. */
    public static void linear$setBufferedOpener(BufferedOpener opener) {
        bufferedOpener = Objects.requireNonNull(opener, "opener");
    }

    /**
     * Contract-exact factory entry point. The Linear file MUST
     * declare {@code implements net.linear.AbstractRegionFile} for the
     * {@code *.linear} branch to link.
     */
    public static AbstractRegionFile get(Path file, Object info, int compressionLevel) throws IOException {
        return get(file, info, file.toAbsolutePath().getParent(), false, compressionLevel);
    }

    /**
     * Full factory entry point for storage-owned dispatch. Preferred: pass
     * {@code this.folder} and {@code this.sync} from {@code RegionFileStorage}.
     */
    public static AbstractRegionFile get(
        Path file, Object info, Path folder, boolean sync, int compressionLevel
    ) throws IOException {
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(B_LINEAR_EXTENSION)) {
            return bufferedOpener.open(file, clampCompressionLevel(compressionLevel));
        }
        if (fileName.endsWith(LINEAR_EXTENSION)) {
            return linearOpener.open(file, clampCompressionLevel(compressionLevel));
        }
        Path externalDir = folder != null ? folder : file.toAbsolutePath().getParent();
        return anvilOpener.open(info, file, externalDir, sync);
    }

    /** Extension (with dot) used when creating new region files. */
    public static String extensionFor(RegionFileFormat format) {
        if (format == RegionFileFormat.LINEAR) {
            return LINEAR_EXTENSION;
        }
        if (format == RegionFileFormat.B_LINEAR) {
            return B_LINEAR_EXTENSION;
        }
        return ANVIL_EXTENSION;
    }

    /**
     * Defensive clamp only. Validation/ownership of the configured value
     * belongs to world config; the factory never throws for range errors.
     */
    public static int clampCompressionLevel(int level) {
        return RegionFileFormat.clampCompressionLevel(level);
    }
}
