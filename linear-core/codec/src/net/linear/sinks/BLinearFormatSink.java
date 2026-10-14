package net.linear.sinks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.codecs.V1Linear;
import net.linear.versions.Version;
import net.linear.versions.VersionWriteParams;
import net.linear.buffered.BufferedSwapFile;
import net.linear.BufferedLinearRegionFile;
import net.linear.RegionFileFormat;

/**
 * {@code B_LINEAR} container sink ({@code .b_linear} master + {@code .swp}
 * write-ahead sidecar + {@code .tmp}/{@code .swp.tmp} staging siblings).
 *
 * <p>Staging is intentionally unsupported here: the buffered owner builds the
 * master image from swap envelopes, while {@code version.flush()} renders a
 * plain linear image — wiring the master through this sink would publish a
 * corrupt image. {@link #createStaging} therefore throws until envelope
 * extraction lands; use the buffered-owner path.</p>
 *
 * <p>Open sweeps both staging siblings (forced-but-unmoved master tmp and
 * compact staging tmp). Close prunes iff a master snapshot is present OR the
 * caller passes the empty-husk flag (owner semantics); the
 * {@link #MIN_BLINEAR_SIZE} floor is header-sanity only, never a prune gate —
 * a 1-23 B corrupt master still prunes, an empty husk prunes only via the
 * flag, and a swap that may hold the sole copy is never deleted.</p>
 */
public final class BLinearFormatSink extends AbstractFormatSink {

    /**
     * Header-sanity floor for a {@code .b_linear} master (18 B header + small
     * frame): below this a master cannot hold a valid header. Sanity only —
     * never a prune gate (see {@link #pruneOnClose(Path, boolean)}).
     */
    public static final long MIN_BLINEAR_SIZE = 24L;

    private static final BLinearFormatSink SHARED = new BLinearFormatSink(V1Linear.shared());

    /** Default singleton (V1 codec; grid-size ignored for V1). */
    public static BLinearFormatSink shared() {
        return SHARED;
    }

    private final Version version;

    /** Creates a sink rendering with {@code version} (codec axis, not container). */
    public BLinearFormatSink(Version version) {
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public RegionFileFormat format() {
        return RegionFileFormat.B_LINEAR;
    }

    @Override
    public String fileExtension() {
        return RegionFileFormat.B_LINEAR_EXTENSION;
    }

    /**
     * Always throws: B_LINEAR staging stays with the buffered owner until
     * envelope extraction lands. A {@code version.flush()} linear image is
     * not a swap-built master — rendering through this sink would publish a
     * corrupt master. Use the buffered-owner path.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public void createStaging(Path tmp, ChunkSlotView slots, VersionWriteParams params) throws IOException {
        throw new UnsupportedOperationException(
            "B_LINEAR staging stays with BufferedLinearRegionFile owner until envelope extraction lands; use owner path");
    }

    @Override
    public void commitStaging(Path tmp, Path target) throws IOException {
        atomicMove(tmp, target);
    }

    /**
     * Owner-mirror prune: deletes the {@code .swp} sidecar plus the
     * {@code .tmp}/{@code .swp.tmp} staging siblings iff a master snapshot is
     * present ({@code Files.isRegularFile && size > 0}) OR {@code swapHusk} is
     * set. The {@link #MIN_BLINEAR_SIZE} floor is header-sanity only, not a
     * prune gate: a 1-23 B corrupt master still prunes, an empty husk prunes
     * only via the flag, and a swap that may hold the sole copy (no master,
     * no husk) is never deleted. Best-effort; never throws.
     */
    public void pruneOnClose(Path target, boolean swapHusk) throws IOException {
        try {
            if (swapHusk || isMasterPresent(target)) {
                Path swap = target.resolveSibling(target.getFileName() + ".swp");
                deleteQuietly(swap);
                deleteQuietly(target.resolveSibling(target.getFileName() + ".tmp"));
                deleteQuietly(swap.resolveSibling(swap.getFileName() + ".tmp"));
            }
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Husk-agnostic entry: delegates to {@link #pruneOnClose(Path, boolean)}
     * with {@code swapHusk=false} (the husk flag is owner-only knowledge, set
     * in owner load()). The {@link Files#exists} probe keeps this
     * conservative: with no master on disk there is nothing master-present to
     * prune, and without the flag an absent-master swap may hold the sole
     * copy, so it is left for the owner path.
     */
    @Override
    public void pruneOnClose(Path target) throws IOException {
        if (target == null || !Files.exists(target)) {
            return;
        }
        pruneOnClose(target, false);
    }

    /**
     * Owner predicate mirror ({@code BufferedLinearRegionFile.isMasterPresent}):
     * a non-empty master snapshot exists. Single-copy here until envelope
     * extraction adopts {@code BufferedSwapFile.isMasterPresent}.
     */
    private static boolean isMasterPresent(Path target) {
        try {
            return target != null && Files.isRegularFile(target) && Files.size(target) > 0L;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void sweepOnOpen(Path target) throws IOException {
        Path swap = target.resolveSibling(target.getFileName() + ".swp");
        deleteQuietly(target.resolveSibling(target.getFileName() + ".tmp"));
        deleteQuietly(swap.resolveSibling(swap.getFileName() + ".tmp"));
    }
}
