package io.linearmc.horizon;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import net.linear.ChunkKey;
import net.linear.LinearRegionConverter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * NMS anvil write side of reverse ({@code .linear} to {@code .mca})
 * conversion.
 *
 * <p>Wraps a real vanilla {@link RegionFile} opened on the staging target:
 * raw NBT bytes from the linear source pass through unchanged and NMS
 * envelops on write (the public {@code getChunkDataOutputStream} path
 * compresses via the server-selected {@code RegionFileVersion} — normally
 * id 2/zlib — and allocates sectors on stream close, i.e. the same
 * {@code [len][version][compressed]} envelope the forward path strips in
 * {@code LinearEnvelopeCodec#toRawNbt}). No hand-rolled compression, no
 * reflective access to the protected sector writer.
 *
 * <p>Coordinates cross as core {@link ChunkKey} longs and convert at this
 * edge ({@code new ChunkPos(int, int)} links on every mappings line, unlike
 * the accessors {@link ChunkPosCoords} probes). Empty buffers are the
 * delete-slot sentinel and clear the slot, mirroring the forward path.
 */
public final class AnvilChunkSink implements LinearRegionConverter.ChunkSink {

    private final RegionFile delegate;

    /**
     * Opens (or creates) the anvil staging target at {@code file}.
     *
     * @param file staging {@code .mca} path (under the sibling
     *     {@code new_<folder>} directory; moved to its final name by the core)
     * @param folder region folder the target belongs to (passed straight
     *     through to vanilla, mirroring the injected anvil opener)
     */
    public AnvilChunkSink(final Path file, final Path folder) throws IOException {
        this.delegate = new RegionFile(
            new RegionStorageInfo("linearmc", Level.OVERWORLD, "chunk"),
            file,
            folder,
            false);
    }

    @Override
    public void write(final long chunk, final ByteBuffer data) throws IOException {
        final ChunkPos pos = new ChunkPos(ChunkKey.x(chunk), ChunkKey.z(chunk));
        final ByteBuffer src = data.duplicate();
        if (!src.hasRemaining()) {
            this.delegate.clear(pos);
            return;
        }
        final byte[] raw = new byte[src.remaining()];
        src.get(raw);
        try (DataOutputStream out = this.delegate.getChunkDataOutputStream(pos)) {
            out.write(raw);
        }
    }

    /**
     * No-op: every write closes its chunk stream, which persists sectors
     * synchronously; durability lands in {@link #close()}.
     */
    @Override
    public void flush() throws IOException {
    }

    @Override
    public void close() throws IOException {
        this.delegate.close();
    }
}
