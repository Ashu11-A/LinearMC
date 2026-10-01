package io.linearmc.horizon;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.AbstractRegionFile;
import net.linear.ChunkKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * Anvil side of the {@link AbstractRegionFile} duality on the Horizon leg:
 * owns one vanilla {@link RegionFile} and bridges {@code long} keys to
 * {@code ChunkPos}, mirroring the fork legs (where {@code RegionFile}
 * implements the interface directly via patch).
 *
 * <p>Composition, not interface injection: no {@code @Shadow} descriptor
 * risk across MC lines, no mixin json change. Uses only long-stable vanilla
 * surface ({@code (RegionStorageInfo, Path, Path, boolean)} ctor,
 * {@code write/clear/hasChunk/getChunkDataInputStream(ChunkPos)},
 * {@code flush/close}).
 */
public final class HorizonAnvilRegionFile implements AbstractRegionFile {

    private final RegionFile delegate;

    public HorizonAnvilRegionFile(
        final RegionStorageInfo info, final java.nio.file.Path file,
        final java.nio.file.Path externalDir, final boolean sync
    ) throws IOException {
        this.delegate = new RegionFile(info, file, externalDir, sync);
    }

    private static ChunkPos pos(final long chunk) {
        return new ChunkPos(ChunkKey.x(chunk), ChunkKey.z(chunk));
    }

    @Override
    public DataInputStream getChunkDataInputStream(final long chunk) throws IOException {
        return this.delegate.getChunkDataInputStream(pos(chunk));
    }

    @Override
    public void write(final long chunk, final ByteBuffer data) throws IOException {
        // Deliberately unsupported: vanilla RegionFile.write is protected on
        // stock servers (forks widen it via patch), and no caller writes
        // through opener handles — forward conversion reads .mca sources,
        // reverse validation only counts/drains them, and all .mca WRITES go
        // through ChunkSink (AnvilChunkSink), which owns its own handle and
        // the public getChunkDataOutputStream path. Fail loud, never silently.
        throw new UnsupportedOperationException(
            "HorizonAnvilRegionFile is read/count-only; write .mca via ChunkSink");
    }

    @Override
    public boolean hasChunk(final long chunk) {
        return this.delegate.hasChunk(pos(chunk));
    }

    @Override
    public void clear(final long chunk) throws IOException {
        this.delegate.clear(pos(chunk));
    }

    @Override
    public void flush() throws IOException {
        this.delegate.flush();
    }

    @Override
    public void close() throws IOException {
        this.delegate.close();
    }

    @Override
    public boolean isMarkedToSave() {
        // Anvil writes sectors synchronously: never anything deferred.
        return false;
    }

    @Override
    public void clearMarkedToSave() {
        // No-op: nothing to reset (see isMarkedToSave).
    }
}
