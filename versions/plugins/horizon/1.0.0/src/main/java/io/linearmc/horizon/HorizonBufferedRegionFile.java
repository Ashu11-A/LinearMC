package io.linearmc.horizon;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.linear.AbstractRegionFile;
import net.linear.BufferedLinearRegionFile;
import net.linear.flush.FlushReports;
import net.linear.io.LinearWritePath;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * A {@link RegionFile} whose bytes live in a buffered-linear region file
 * ({@code .b_linear} master + {@code .b_linear.swp} swap).
 *
 * <p>Same IS-A discipline as {@link HorizonLinearRegionFile} (Horizon cannot
 * rebuild the server, so the vanilla {@code regionCache} type must keep
 * working): every behavior method delegates to the NMS-free core
 * ({@link BufferedLinearRegionFile}), including the temp-asset constructor
 * cost. Payload symmetry matches the linear wrapper (normalize on write,
 * tolerant serve on read), so both wrappers store raw NBT and read legacy
 * enveloped data without migration.</p>
 */
public class HorizonBufferedRegionFile extends RegionFile implements AbstractRegionFile {

    private final BufferedLinearRegionFile delegate;
    private final Path realPath;
    private final Path asset;
    private final Path assetDir;

    /**
     * Opens (or creates) the buffered file at {@code realPath}.
     *
     * @param realPath destination {@code .b_linear} path (sibling of the region folder)
     * @param level zstd level, clamped by the core
     */
    public HorizonBufferedRegionFile(final Path realPath, final int level) throws IOException {
        this(realPath, level, newAsset());
    }

    private HorizonBufferedRegionFile(final Path realPath, final int level, final Path[] asset) throws IOException {
        super(
            new RegionStorageInfo("linearmc", Level.OVERWORLD, "chunk"),
            asset[0],
            asset[1],
            false
        );
        this.realPath = realPath;
        this.asset = asset[0];
        this.assetDir = asset[1];
        this.delegate = new BufferedLinearRegionFile(realPath, level);
    }

    /** One temp dir holding one empty asset file (parsed harmlessly by super). */
    private static Path[] newAsset() throws IOException {
        final Path dir = Files.createTempDirectory("linearmc-asset");
        return new Path[] {Files.createTempFile(dir, "empty", ".mca"), dir};
    }

    @Override
    public Path getPath() {
        return this.realPath;
    }

    @Override
    public DataInputStream getChunkDataInputStream(final ChunkPos pos) throws IOException {
        return this.getChunkDataInputStream(ChunkPosCoords.toKey(pos));
    }

    @Override
    public DataInputStream getChunkDataInputStream(final long chunk) throws IOException {
        final DataInputStream stored = this.delegate.getChunkDataInputStream(chunk);
        if (stored == null) {
            return null;
        }
        final byte[] raw;
        try (DataInputStream autoClose = stored) {
            raw = LinearWritePath.serveReadBytes(autoClose.readAllBytes());
        }
        return new DataInputStream(new ByteArrayInputStream(raw));
    }

    @Override
    public boolean hasChunk(final ChunkPos pos) {
        return this.hasChunk(ChunkPosCoords.toKey(pos));
    }

    @Override
    public boolean hasChunk(final long chunk) {
        return this.delegate.hasChunk(chunk);
    }

    @Override
    public void write(final ChunkPos pos, final ByteBuffer data) throws IOException {
        this.write(ChunkPosCoords.toKey(pos), data);
    }

    @Override
    public void write(final long chunk, final ByteBuffer data) throws IOException {
        this.delegate.write(chunk, LinearWritePath.normalizeWritePayload(data));
        this.markDirty();
    }

    @Override
    public void clear(final ChunkPos pos) throws IOException {
        this.clear(ChunkPosCoords.toKey(pos));
    }

    @Override
    public void clear(final long chunk) throws IOException {
        this.delegate.clear(chunk);
        this.markDirty();
    }

    private void markDirty() {
        FlushReports.markDirtyForFile(this.realPath.getParent(), this.delegate);
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(final ChunkPos pos) throws IOException {
        return stagedStream(ChunkPosCoords.toKey(pos));
    }

    /**
     * ChunkBuffer entry point, mirroring {@link #write(long, ByteBuffer)}:
     * bytes accumulate in memory and persist (normalized, self-reported) on
     * close. Normalization here is load-bearing, not cosmetic: the direct
     * path strips the vanilla envelope before storing, so an unnormalized
     * streaming write would store a different byte shape for the same chunk.
     */
    private DataOutputStream stagedStream(final long chunk) {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(8096);
        return new DataOutputStream(buffer) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (this.closed) {
                    return;
                }
                this.closed = true;
                try {
                    super.close();
                    final byte[] raw = buffer.toByteArray();
                    final ByteBuffer normalized = raw.length == 0
                        ? ByteBuffer.allocate(0)
                        : LinearWritePath.normalizeWritePayload(ByteBuffer.wrap(raw));
                    delegate.write(chunk, normalized);
                } finally {
                    markDirty();
                }
            }
        };
    }

    @Override
    public void flush() throws IOException {
        this.delegate.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            this.delegate.close();
        } finally {
            try {
                super.close();
            } finally {
                Files.deleteIfExists(this.asset);
                Files.deleteIfExists(this.assetDir);
            }
        }
    }

    @Override
    public boolean isMarkedToSave() {
        return this.delegate.isMarkedToSave();
    }

    @Override
    public void clearMarkedToSave() {
        this.delegate.clearMarkedToSave();
    }

    @Override
    public int getRecalculateCount() {
        return 0;
    }
}
