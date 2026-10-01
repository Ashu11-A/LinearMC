package net.linear;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Minimal region-file abstraction shared by the vanilla anvil
 * implementation and the Linear implementation
 * ({@code net.linear.LinearRegionFile}).
 *
 * <p>Method set is the shared region-file contract (package {@code net.linear},
 * {@code LinearRegionFile(Path file, int compressionLevel)}), so dispatch
 * code may only call the seven methods below. {@link AutoCloseable} is
 * extended purely so implementations are usable in try-with-resources
 * (required by {@code RegionStorageUpgrader}); it adds no new methods since
 * {@link #close()} is already in the contract.</p>
 *
 * <p>Additive deviation from the contract (does not constrain callers): one
 * {@code default} method, {@link #clear(long)}, used by the DELETE paths
 * in {@code RegionFileStorage}. If the Linear file provides {@code clear(long)}
 * it overrides this default; otherwise the default applies
 * write-empty semantics (zero uncompressed bytes read back as absent).</p>
 *
 * <p>Chunk coordinates cross this boundary as opaque {@link ChunkKey} longs;
 * adapters convert at the edge ({@code ChunkKey.of(pos.getX(), pos.getZ())}).</p>
 */
public interface AbstractRegionFile extends AutoCloseable {

    /**
     * Opens the stored NBT payload for a chunk, or returns {@code null} when
     * the chunk is absent. The caller owns the returned stream.
     */
    DataInputStream getChunkDataInputStream(long chunk) throws IOException;

    /**
     * Stores a chunk payload. For Linear this buffers (LZ4) in memory and
     * marks the file dirty; for anvil it writes sectors synchronously.
     */
    void write(long chunk, ByteBuffer data) throws IOException;

    /** Returns whether a payload is stored for {@code chunk}. */
    boolean hasChunk(long chunk);

    /** Persists any buffered state to disk. */
    void flush() throws IOException;

    /** Flushes and releases all resources. */
    @Override
    void close() throws IOException;

    /**
     * Whether the file holds unflushed writes. Anvil always returns
     * {@code false} (writes are synchronous); Linear returns its dirty flag.
     * Leaves the flag set: use {@link #clearMarkedToSave()} to reset.
     */
    boolean isMarkedToSave();

    /** Resets the dirty flag set by {@link #write}. */
    void clearMarkedToSave();

    /**
     * Deletes the payload for {@code pos} and marks the file dirty.
     * Default implementation writes an empty buffer, which every
     * contract implementation must read back as absent
     * ({@link #hasChunk} {@code false}, {@link #getChunkDataInputStream}
     * {@code null}); {@code RegionFile} and {@code LinearRegionFile}
     * override this with a real delete.
     */
    default void clear(long chunk) throws IOException {
        this.write(chunk, ByteBuffer.allocate(0));
    }
}
