package net.linear.sinks;

import java.nio.file.Path;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.VersionWriteParams;
import net.linear.RegionFileFormat;

/**
 * {@code ANVIL} container sink ({@code .mca}).
 *
 * <p>Both {@link #sweepOnOpen} and {@link #pruneOnClose} are no-ops by design:
 * Anvil files are updated in place (sector read-modify-write through a
 * {@code RandomAccessFile}-style handle), so there is never a staging tmp or a
 * sidecar to sweep or prune. Crash orphans cannot exist at this layer.</p>
 *
 * <p>{@link #createStaging} and {@link #commitStaging} are additive stubs that
 * throw {@link UnsupportedOperationException}: Anvil persistence delegates to
 * the caller ChunkSink pattern (see StorageAdapters); wiring the container
 * through this sink is future work and must not change behaviour today.</p>
 */
public final class AnvilFormatSink extends AbstractFormatSink {

    private static final AnvilFormatSink SHARED = new AnvilFormatSink();

    /** Default singleton. */
    public static AnvilFormatSink shared() {
        return SHARED;
    }

    @Override
    public RegionFileFormat format() {
        return RegionFileFormat.ANVIL;
    }

    @Override
    public String fileExtension() {
        return RegionFileFormat.ANVIL_EXTENSION;
    }

    @Override
    public void createStaging(Path tmp, ChunkSlotView slots, VersionWriteParams params) {
        throw new UnsupportedOperationException(
            "Anvil staging delegates to the caller ChunkSink pattern (see StorageAdapters)");
    }

    @Override
    public void commitStaging(Path tmp, Path target) {
        throw new UnsupportedOperationException(
            "Anvil commit delegates to the caller ChunkSink pattern (see StorageAdapters)");
    }

    @Override
    public void pruneOnClose(Path target) {
        // No-op: Anvil has no sidecars (in-place sector updates only).
    }

    @Override
    public void sweepOnOpen(Path target) {
        // No-op: Anvil has no staging tmps (in-place sector updates only).
    }
}
