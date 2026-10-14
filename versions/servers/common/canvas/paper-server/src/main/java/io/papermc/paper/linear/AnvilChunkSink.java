package io.papermc.paper.linear;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import net.linear.ChunkKey;
import net.linear.LinearRegionConverter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * NMS anvil writer for the core reverse ({@code .linear} to {@code .mca})
 * pipeline ({@link LinearRegionConverter.ChunkSink}).
 *
 * <p>The core hands over raw NBT bytes per chunk (legs envelop): each payload
 * is parsed to a {@link CompoundTag} and written through the leg's
 * {@code getChunkDataOutputStream} path, so compression framing
 * ({@code RegionFileVersion} inside) matches a normal server write exactly.
 * Coordinates cross the core boundary as {@link ChunkKey} longs. Anvil writes
 * are synchronous (sectors + header hit the channel inside the stream close),
 * so {@link #flush()} is a no-op and {@link #close()} just closes the file.
 */
public final class AnvilChunkSink implements LinearRegionConverter.ChunkSink {

    private final RegionFile delegate;

    public AnvilChunkSink(Path file, Path folder) throws IOException {
        this.delegate = new RegionFile(
            new RegionStorageInfo("converter", Level.OVERWORLD, "chunk"), file, folder, false);
    }

    @Override
    public void write(long chunk, ByteBuffer data) throws IOException {
        final ChunkPos pos = new ChunkPos(ChunkKey.x(chunk), ChunkKey.z(chunk));
        final ByteBuffer in = data.duplicate();
        if (!in.hasRemaining()) {
            this.delegate.clear(pos);
            return;
        }
        final byte[] raw = new byte[in.remaining()];
        in.get(raw);
        final CompoundTag tag;
        try (DataInputStream rawIn = new DataInputStream(new ByteArrayInputStream(raw))) {
            tag = NbtIo.read(rawIn);
        }
        try (DataOutputStream out = this.delegate.getChunkDataOutputStream(pos)) {
            NbtIo.write(tag, out);
        }
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public void close() throws IOException {
        this.delegate.close();
    }
}
