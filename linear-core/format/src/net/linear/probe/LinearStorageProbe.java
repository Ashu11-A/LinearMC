package net.linear.probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.linear.RegionFileFormat;

/**
 * Filename + existence probes and the conversion/host-open registries shared
 * by every leg's region storage and the converter.
 *
 * <p>Pure JDK + format: no NMS, no codec dependency.</p>
 */
public final class LinearStorageProbe {

    private LinearStorageProbe() {
    }

    /** {@code r.<x>.<z>.<mca|linear|b_linear>}; groups: x, z, extension without the dot. */
    public static final Pattern REGION_NAME = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(mca|linear|b_linear)$");

    /** Region coordinates {@code {x, z}} from a region file name, or {@code null}. */
    public static int[] parseRegionCoords(String name) {
        Matcher m = REGION_NAME.matcher(name);
        if (!m.matches()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /**
     * Format-independent identity of one region ({@code <abs folder>/r.<x>.<z>}):
     * the .mca, .linear and .b_linear files of a region share it.
     */
    public static String regionKey(Path folder, int x, int z) {
        return folder.toAbsolutePath().normalize() + "/r." + x + "." + z;
    }

    /** {@link #regionKey(Path, int, int)} of a region file, or its normalized path if not a region name. */
    public static String regionKey(Path file) {
        Path parent = file.getParent();
        int[] c = parseRegionCoords(file.getFileName().toString());
        return c == null || parent == null ? file.toAbsolutePath().normalize().toString() : regionKey(parent, c[0], c[1]);
    }

    /** Region key to the source file a converter is working on right now. */
    private static final Map<String, Path> CONVERTING = new ConcurrentHashMap<>();

    /** Converter hook: {@code source} is being converted (until {@link #unmarkConverting}). */
    public static void markConverting(Path source) {
        CONVERTING.put(regionKey(source), source.toAbsolutePath().normalize());
    }

    /** Converter hook: the conversion of {@code source} finished (any outcome); keeps a later mark of the region. */
    public static void unmarkConverting(Path source) {
        CONVERTING.remove(regionKey(source), source.toAbsolutePath().normalize());
    }

    /** Anvil files a host region storage opened since startup; guarded by itself. */
    private static final Set<Path> HOST_OPENED = new HashSet<>();

    /** Set while the converter opens its own handles: those are never host opens. */
    private static final ThreadLocal<Boolean> CONVERTER_OPEN = new ThreadLocal<>();

    /** An open that may throw {@link IOException}. */
    @FunctionalInterface
    public interface IoOpen<T> {
        T open() throws IOException;
    }

    /** Runs a converter-owned open: {@link #hostOpening} does not record it. */
    public static <T> T converterOpen(IoOpen<T> open) throws IOException {
        if (CONVERTER_OPEN.get() != null) {
            return open.open();
        }
        CONVERTER_OPEN.set(Boolean.TRUE);
        try {
            return open.open();
        } finally {
            CONVERTER_OPEN.remove();
        }
    }

    /**
     * Host storage open hook, called before the host's exists check or open.
     * An Anvil file is recorded so a live conversion never deletes it: the
     * host may hold it open, and writes through a handle to a deleted
     * {@code .mca} are lost. Returns the file to open: a region converted away
     * after the host probed it opens its new file instead of reading as absent.
     */
    public static Path hostOpening(Path file) {
        if (CONVERTER_OPEN.get() != null) {
            return file;
        }
        String name = file.getFileName().toString();
        if (name.endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
            synchronized (HOST_OPENED) {
                HOST_OPENED.add(file.toAbsolutePath().normalize());
            }
        }
        Path parent = file.getParent();
        int[] c = parseRegionCoords(name);
        if (parent == null || c == null || Files.exists(file)) {
            return file;
        }
        Path moved = probeExisting(parent, c[0], c[1]);
        return moved != null ? moved : file;
    }

    /**
     * Converter source delete, atomic with {@link #hostOpening}: refuses an
     * Anvil file a host storage opened since startup (a convert re-run after a
     * restart, before the region loads, finishes it).
     *
     * @return {@code false} when the source was kept
     */
    public static boolean deleteUnlessHostOpened(Path source) throws IOException {
        synchronized (HOST_OPENED) {
            if (HOST_OPENED.contains(source.toAbsolutePath().normalize())) {
                return false;
            }
            Files.deleteIfExists(source);
            return true;
        }
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
     * Existing-first region file name for {@code (x, z)}: the name of the
     * file {@link #probeExisting} finds ({@code .mca} over {@code .linear}
     * over {@code .b_linear}), else the {@code configured} format's name.
     *
     * <p>Host storages that compute the region path from the name and then
     * short-circuit on {@code Files.exists}/{@code isRegularFile} before any
     * open hook runs (Paper Moonrise {@code getRegionFile(pos, false)} and
     * {@code moonrise$getRegionFileIfExists}) must resolve the name here, or
     * an existing file in another format reads as an absent region and the
     * chunks are regenerated. Also keeps a region in one file: a write to a
     * region that already exists lands in that file, never in a new sibling.
     */
    public static String resolveFileName(Path folder, int x, int z, RegionFileFormat configured) {
        Objects.requireNonNull(configured, "configured");
        if (folder != null) {
            Path existing = probeExisting(folder, x, z);
            if (existing != null) {
                return existing.getFileName().toString();
            }
        }
        return fileNameFor(x, z, configured);
    }

    /**
     * Triple-read probe: returns the existing region path for {@code (x, z)},
     * preferring {@code .mca} over {@code .linear} over {@code .b_linear}.
     * Returns {@code null} when none exists (negative cache = null).
     */
    public static Path probeExisting(Path folder, int x, int z) {
        Objects.requireNonNull(folder, "folder");
        if (!CONVERTING.isEmpty()) {
            // A region mid-conversion keeps serving (and receiving writes in) its
            // SOURCE until the converter finishes: a failed validation deletes the
            // target. Late writes to a Linear source are caught by the pre-delete
            // dirty check; an Anvil source a host opened is never deleted
            // (deleteUnlessHostOpened).
            Path source = CONVERTING.get(regionKey(folder, x, z));
            if (source != null && Files.isRegularFile(source)) {
                return source;
            }
        }
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
