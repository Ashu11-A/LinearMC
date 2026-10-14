package net.linear.sinks;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.Version;
import net.linear.versions.VersionWriteParams;
import net.linear.io.LinearDirectStreams;
import net.linear.LinearRegionFile;
import net.linear.RegionFileFormat;

/**
 * Container contract for region file staging (tmp render + atomic publish).
 *
 * <h2>Version x format matrix</h2>
 * <p>Two axes are independent. The <em>codec</em> ({@code V1}/{@code V2}/
 * {@code V3}/{@code Pumpkin} {@link net.linear.versions.Version}) renders slot
 * tables into a whole file image; the <em>container</em> (this hierarchy)
 * owns the file-system shape around that image:</p>
 * <ul>
 *   <li>{@code LINEAR} ({@code .linear}): single master file, sibling
 *       {@code .tmp} staging. See {@link LinearFormatSink}.</li>
 *   <li>{@code B_LINEAR} ({@code .b_linear}): master plus {@code .swp}
 *       write-ahead sidecar plus {@code .tmp}/{@code .swp.tmp} staging
 *       siblings. See {@link BLinearFormatSink}.</li>
 *   <li>{@code ANVIL} ({@code .mca}): updated in place by the caller ChunkSink
 *       pattern; staging is a no-op stub. See {@link AnvilFormatSink}.</li>
 * </ul>
 * <p>Codec notes: {@code gridSize} in {@link VersionWriteParams} is honoured
 * by V2/V3/Pumpkin and ignored for V1; the container never interprets it.</p>
 */
public abstract class AbstractFormatSink {

    /** Container this sink stages. */
    public abstract RegionFileFormat format();

    /** On-disk extension including the dot (e.g. {@code .linear}). */
    public abstract String fileExtension();

    /**
     * Renders {@code slots} into {@code tmp} (create + truncate + fsync).
     * The caller owns {@code params} (level, grid, coords, timestamps).
     *
     * <p>Failure contract: on {@link IOException} the caller must
     * {@link #deleteQuietly}{@code (tmp)} and re-mark dirty (RAM stays the
     * truth); mirrors {@code LinearRegionFile.doFlush} (markDirty +
     * deleteQuietly(tmp)). A forced-but-unmoved tmp is always garbage: the
     * master is old truth until the move lands, so the next open sweeps it
     * (see {@link #sweepOnOpen}).</p>
     */
    public abstract void createStaging(Path tmp, ChunkSlotView slots, VersionWriteParams params)
        throws IOException;

    /**
     * Publishes a staged {@code tmp} over {@code target} (atomic with fallback).
     *
     * <p>Failure contract: same as {@link #createStaging} — on
     * {@link IOException} the caller must {@link #deleteQuietly}{@code (tmp)}
     * and re-mark dirty (mirrors {@code LinearRegionFile.doFlush}); a failed
     * move never deletes a previous master.</p>
     */
    public abstract void commitStaging(Path tmp, Path target) throws IOException;

    /** Best-effort sidecar cleanup after a clean close. */
    public abstract void pruneOnClose(Path target) throws IOException;

    /** Best-effort orphan-staging cleanup before open (crashed flush leftovers). */
    public abstract void sweepOnOpen(Path target) throws IOException;

    /**
     * Parses {@code r.&lt;x&gt;.&lt;z&gt;.&lt;ext&gt;} names; unparseable input
     * yields {@code {0, 0}} (never throws; config-fallback path).
     */
    public static int[] parseRegionCoordinates(String fileName) {
        try {
            if (fileName != null) {
                String[] parts = fileName.split("\\.");
                if (parts.length >= 4) {
                    return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
                }
            }
        } catch (RuntimeException ignored) {
        }
        return new int[] {0, 0};
    }

    /** Creates the parent directory of {@code path} (no-op when absent). */
    public static void createParentDirs(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /**
     * Shared tmp-render for LINEAR-family containers: renders the codec image
     * via {@code version.flush} and streams it into {@code tmp} (create +
     * truncate + fsync). Adopted by {@link LinearFormatSink};
     * {@link BLinearFormatSink} intentionally does NOT use it — B_LINEAR
     * staging stays with the buffered owner until envelope extraction lands
     * (a {@code version.flush()} linear image is not a swap-built master, so
     * rendering through the sink would publish corruption).
     */
    protected static void renderAndForce(Version version, Path tmp, ChunkSlotView slots,
        VersionWriteParams params) throws IOException {
        final ByteBuffer image;
        try {
            image = version.flush(slots, params);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " for " + tmp, e);
        }
        createParentDirs(tmp);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
            LinearDirectStreams.writeFully(channel, image);
            channel.force(true);
        }
    }

    /** Best-effort delete; never throws. */
    public static void deleteQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /**
     * Moves staged {@code tmp} over {@code target}: atomic move with a plain
     * replace fallback for filesystems without atomic-move support. The data
     * fsync lands in {@code createStaging} (channel force before close), so
     * the moved image is already durable; only the directory entry moves here.
     */
    public static void atomicMove(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException fallback) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
