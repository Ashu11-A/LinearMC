package net.linear;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Filename + existence probes shared by the Canvas overlay and the Horizon
 * mixin (Phase D callers; not wired yet).
 *
 * <p>Pure JDK + format: no NMS, no codec dependency.</p>
 */
public final class LinearStorageProbe {

    private LinearStorageProbe() {
    }

    /**
     * Region file name for {@code (x, z)} in format {@code f}:
     * {@code r.&lt;x&gt;.&lt;z&gt;.mca} for Anvil, {@code r.&lt;x&gt;.&lt;z&gt;.linear}
     * for Linear. Non-Linear formats (including {@code INVALID}) fall back to
     * {@code .mca}, matching the {@link RegionFileFormat#fromString} fallback.
     */
    public static String fileNameFor(int x, int z, RegionFileFormat f) {
        String ext = f == RegionFileFormat.LINEAR
            ? RegionFileFormat.LINEAR_EXTENSION
            : RegionFileFormat.ANVIL_EXTENSION;
        return "r." + x + "." + z + ext;
    }

    /**
     * Dual-read probe: returns the existing region path for {@code (x, z)},
     * preferring {@code .mca} over {@code .linear}. Returns {@code null} when
     * neither exists (negative cache = null).
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
        return null;
    }

    /**
     * Whether a region path must be refused as a symlink: true iff the active
     * format is Linear and {@code p} is a broken symlink
     * ({@link Files#isSymbolicLink} and the target does not exist).
     */
    public static boolean shouldRefuseSymlink(RegionFileFormat f, Path p) {
        Objects.requireNonNull(f, "f");
        Objects.requireNonNull(p, "p");
        return f == RegionFileFormat.LINEAR
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
     * Single decision point for NEW files: true iff the configured format
     * is Linear. Existing files still dual-probe via
     * {@link #probeExisting} / {@link #siblingIfProbed} regardless.
     */
    public static boolean shouldUseLinear(Path folder, int x, int z, RegionFileFormat configured) {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(configured, "configured");
        return configured == RegionFileFormat.LINEAR;
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
}
