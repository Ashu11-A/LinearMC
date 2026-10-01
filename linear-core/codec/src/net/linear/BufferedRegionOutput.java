package net.linear;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Buffered chunk-output contract for the Linear region file.
 *
 * <p>The stream accumulates NBT bytes in memory (initial 8096) and its
 * {@code close()} persists them via {@code file.write(chunkKey, copy)}.
 * The caller must {@code markDirty} after close (via
 * {@code LinearFlushCoordinator.forFolder(folder).markDirty(file)} or
 * {@code FlushReports.markDirtyForFile}); this class stages bytes only.
 */
public final class BufferedRegionOutput {

    private BufferedRegionOutput() {
    }

    /**
     * Returns an {@link OutputStream} buffering in memory; {@code close()}
     * writes the captured bytes to {@code file}. Caller must markDirty after.
     */
    public static OutputStream forFile(AbstractRegionFile file, long chunkKey) {
        Objects.requireNonNull(file, "file");
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(8096);
        return new DataOutputStream(buffer) {
            private boolean closed = false;

            @Override
            public void close() throws IOException {
                if (this.closed) {
                    return;
                }
                this.closed = true;
                super.close();
                file.write(chunkKey, ByteBuffer.wrap(buffer.toByteArray()));
            }
        };
    }
}
