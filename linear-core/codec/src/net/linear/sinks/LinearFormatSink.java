package net.linear.sinks;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.codecs.V1Linear;
import net.linear.versions.Version;
import net.linear.versions.VersionWriteParams;
import net.linear.io.LinearDirectStreams;
import net.linear.RegionFileFormat;

/**
 * {@code LINEAR} container sink ({@code .linear} master + sibling {@code .tmp}).
 *
 * <p>Staging renders the held {@link Version} codec image into the tmp file
 * (writeFully + force), then {@link #commitStaging} atomically publishes it.
 * Open sweeps the sibling {@code .tmp} (a forced-but-unmoved crashed-flush
 * orphan; the master is old truth until the move lands). Close prunes nothing:
 * steady state is the master alone and there are no sidecars.</p>
 */
public final class LinearFormatSink extends AbstractFormatSink {

    private static final LinearFormatSink SHARED = new LinearFormatSink(V1Linear.shared());

    /** Default singleton (V1 codec; grid-size ignored for V1). */
    public static LinearFormatSink shared() {
        return SHARED;
    }

    private final Version version;

    /** Creates a sink rendering with {@code version} (codec axis, not container). */
    public LinearFormatSink(Version version) {
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public RegionFileFormat format() {
        return RegionFileFormat.LINEAR;
    }

    @Override
    public String fileExtension() {
        return RegionFileFormat.LINEAR_EXTENSION;
    }

    @Override
    public void createStaging(Path tmp, ChunkSlotView slots, VersionWriteParams params) throws IOException {
        final ByteBuffer image;
        try {
            image = this.version.flush(slots, params);
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

    @Override
    public void commitStaging(Path tmp, Path target) throws IOException {
        atomicMove(tmp, target);
    }

    @Override
    public void pruneOnClose(Path target) throws IOException {
        // No-op: no sidecars exist beside a linear master.
    }

    @Override
    public void sweepOnOpen(Path target) throws IOException {
        deleteQuietly(target.resolveSibling(target.getFileName() + ".tmp"));
    }
}
