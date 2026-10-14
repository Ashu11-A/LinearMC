package net.linear.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.linear.RegionFileFormat;

/**
 * Filename + existence probes shared by the Canvas overlay and the Horizon
 * mixin (RegionFileStorageMixin.linear$openProbed, Canvas RegionStorageUpgrader).
 *
 * <p>Pure JDK + format: no NMS, no codec dependency.</p>
 */
public final class LinearStorageProbe {

    private LinearStorageProbe() {
    }

    /**
     * Region file name for {@code (x, z)} in format {@code f}:
     * {@code r.&lt;x&gt;.&lt;z&gt;.mca} for Anvil, {@code r.&lt;x&gt;.&lt;z&gt;.linear}
     * for Linear, {@code r.&lt;x&gt;.&lt;z&gt;.b_linear} for buffered-linear.
     * Non-linear formats (including {@code INVALID}) fall back to
     * {@code .mca}, matching the {@link RegionFileFormat#fromString} fallback.
     */
    public static String fileNameFor(int x, int z, RegionFileFormat f) {
        String ext;
        if (f == RegionFileFormat.LINEAR) {
            ext = RegionFileFormat.LINEAR_EXTENSION;
        } else if (f == RegionFileFormat.B_LINEAR) {
            ext = RegionFileFormat.B_LINEAR_EXTENSION;
        } else {
            ext = RegionFileFormat.ANVIL_EXTENSION;
        }
        return "r." + x + "." + z + ext;
    }

    /**
     * Triple-read probe: returns the existing region path for {@code (x, z)},
     * preferring {@code .mca} over {@code .linear} over {@code .b_linear}.
     * Returns {@code null} when none exists (negative cache = null).
     */
    public static Path probeExisting(Path folder, int x, int z) {
        Objects.requireNonNull(folder, "folder");
        Path mca = folder.resolve(fileNameFor(x, z, RegionFileFormat.ANVIL));
        if (Files.isRegularFile(mca)) {
            return mca;
        }
        Path linear = folder.resolve(fileNameFor(x, z, RegionFileFormat.LINEAR));
        if (Files.isRegularFile(linear)) {
            return linear;
        }
        Path buffered = folder.resolve(fileNameFor(x, z, RegionFileFormat.B_LINEAR));
        if (Files.isRegularFile(buffered)) {
            return buffered;
        }
        return null;
    }

    /**
     * Whether a region path must be refused as a symlink: true iff the active
     * format is linear-family and {@code p} is a broken symlink
     * ({@link Files#isSymbolicLink} and the target does not exist).
     */
    public static boolean shouldRefuseSymlink(RegionFileFormat f, Path p) {
        Objects.requireNonNull(f, "f");
        Objects.requireNonNull(p, "p");
        return RegionFileFormat.isLinearFamily(f)
            && Files.isSymbolicLink(p)
            && !Files.exists(p);
    }

    /**
     * Maps an {@code .mca} source to its sibling {@code .linear} path (same
     * parent directory). Names lacking the {@code .mca} suffix get
     * {@code .linear} appended instead of replaced.
     */
    public static Path targetForSource(Path mcaSource) {
        Objects.requireNonNull(mcaSource, "mcaSource");
        String name = mcaSource.getFileName().toString();
        String targetName = name.endsWith(RegionFileFormat.ANVIL_EXTENSION)
            ? name.substring(0, name.length() - RegionFileFormat.ANVIL_EXTENSION.length())
                + RegionFileFormat.LINEAR_EXTENSION
            : name + RegionFileFormat.LINEAR_EXTENSION;
        Path parent = mcaSource.getParent();
        return parent == null ? Path.of(targetName) : parent.resolve(targetName);
    }

    /**
     * Maps any region source to its sibling {@code .b_linear} path (same
     * parent directory): a known region extension is replaced, anything else
     * gets {@code .b_linear} appended.
     */
    public static Path bufferedTargetForSource(Path source) {
        Objects.requireNonNull(source, "source");
        String name = source.getFileName().toString();
        String targetName = name;
        for (String known : new String[] {RegionFileFormat.ANVIL_EXTENSION, RegionFileFormat.LINEAR_EXTENSION,
                RegionFileFormat.B_LINEAR_EXTENSION}) {
            if (name.endsWith(known)) {
                targetName = name.substring(0, name.length() - known.length())
                    + RegionFileFormat.B_LINEAR_EXTENSION;
                break;
            }
        }
        if (targetName.equals(name)) {
            targetName = name + RegionFileFormat.B_LINEAR_EXTENSION;
        }
        Path parent = source.getParent();
        return parent == null ? Path.of(targetName) : parent.resolve(targetName);
    }

    /**
     * Single decision point for NEW files: true iff the configured format
     * is Linear. Existing files still triple-probe via
     * {@link #probeExisting} / {@link #siblingIfProbed} regardless.
     */
    public static boolean shouldUseLinear(Path folder, int x, int z, RegionFileFormat configured) {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(configured, "configured");
        return configured == RegionFileFormat.LINEAR;
    }

    /**
     * Single decision point for NEW files: true iff the configured format
     * is buffered-linear.
     */
    public static boolean shouldUseBuffered(Path folder, int x, int z, RegionFileFormat configured) {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(configured, "configured");
        return configured == RegionFileFormat.B_LINEAR;
    }

    /**
     * Dual-probe step: returns the {@code .linear} sibling path for
     * {@code (x, z)} when it exists as a regular file, else {@code null}.
     */
    public static Path siblingIfProbed(Path folder, int x, int z) {
        Objects.requireNonNull(folder, "folder");
        Path linear = folder.resolve(fileNameFor(x, z, RegionFileFormat.LINEAR));
        if (Files.isRegularFile(linear)) {
            return linear;
        }
        return null;
    }

    /**
     * Buffered-probe step: returns the {@code .b_linear} sibling path for
     * {@code (x, z)} when it exists as a regular file, else {@code null}.
     */
    public static Path bufferedSiblingIfProbed(Path folder, int x, int z) {
        Objects.requireNonNull(folder, "folder");
        Path buffered = folder.resolve(fileNameFor(x, z, RegionFileFormat.B_LINEAR));
        if (Files.isRegularFile(buffered)) {
            return buffered;
        }
        return null;
    }
}
