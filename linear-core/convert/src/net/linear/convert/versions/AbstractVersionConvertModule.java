package net.linear.convert.versions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.linear.LinearRegionConverter;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.RegionFormatDetector;
import net.linear.versions.Version;
import net.linear.versions.VersionWriteParams;

/**
 * Additive convert-module shim: uniform per-format convert surface.
 *
 * <p>{@code LinearRegionConverter} still owns all live behavior; later wiring
 * will delegate to these modules. This base carries the seven uniform methods
 * plus shared file IO helpers. No NMS imports: slot parse/flush go through
 * the {@code Version} codecs on whole-file images only.</p>
 */
public abstract class AbstractVersionConvertModule {

    private static final Pattern REGION_NAME =
        Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(mca|linear|b_linear)$");

    /** Short format id ({@code v1/v2/v3/pumpkin/mca/b_linear}). */
    public abstract String sourceFormat();

    /** True when this module can read {@code file} (extension + structural detect). */
    public abstract boolean canRead(Path file) throws IOException;

    /** Canonical parse of {@code file} into slot tables (detector-routed, never wire-byte alone). */
    public abstract ChunkSlotTable parseToSlots(Path file, int x, int z) throws IOException;

    /**
     * Renders {@code slots} into a whole file image via the version codec and
     * commits it to {@code stagingTarget} (tmp + fsync + atomic-move with
     * plain-move fallback). Validation and the final move stay with the caller.
     */
    public abstract void writeFromSlots(ChunkSlotTable slots, VersionWriteParams params, Path stagingTarget)
        throws IOException;

    /** On-disk extension this module writes (e.g. {@code .linear}). */
    public abstract String targetExtension();

    /**
     * Effective grid for {@code requested} (0/off-whitelist maps to the format
     * default; formats without a grid return {@code requested} untouched).
     */
    public abstract int resolveGrid(int requested);

    /** Header/footer sanity for files this module reads. Pure byte logic. */
    public abstract void headerSanity(Path file) throws IOException;

    /** Region coords parsed from a file name, or {@code null} when it does not match. */
    protected static int[] parseRegionName(String fileName) {
        Matcher m = REGION_NAME.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Region coords parsed from {@code file}'s name, fail-closed when unparseable. */
    protected static int[] probeCoords(Path file) throws IOException {
        int[] coords = parseRegionName(file.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable region name: " + file);
        }
        return coords;
    }

    /** Whole-file image read (position 0), fail-closed with file context. */
    protected static ByteBuffer readImage(Path file) throws IOException {
        try {
            return ByteBuffer.wrap(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IOException("Cannot read source " + file + ": " + e.getMessage(), e);
        }
    }

    /** Single validating layout for {@code image}, fail-closed with file context. */
    protected static RegionFormatDetector.DetectedFormat detectImage(ByteBuffer image, int x, int z, Path file)
        throws IOException {
        try {
            return RegionFormatDetector.shared().detect(image, x, z);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " in " + file, e);
        }
    }

    /**
     * Commits a flushed file image to {@code stagingTarget}: write to a
     * {@code .tmp} sibling, fsync, then atomic-move with a plain-move
     * fallback (same durability discipline as the facade's Pumpkin/restage paths).
     */
    protected static void commitImage(ByteBuffer image, Path stagingTarget) throws IOException {
        Path tmp = stagingTarget.resolveSibling(stagingTarget.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            while (image.hasRemaining()) {
                channel.write(image);
            }
            channel.force(true);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
            throw e;
        }
        try {
            Files.move(tmp, stagingTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(tmp, stagingTarget, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
