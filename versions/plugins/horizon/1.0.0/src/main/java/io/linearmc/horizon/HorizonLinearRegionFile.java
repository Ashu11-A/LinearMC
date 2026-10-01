package io.linearmc.horizon;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.linear.AbstractRegionFile;
import net.linear.BufferedRegionOutput;
import net.linear.FlushReports;
import net.linear.LinearRegionFile;
import net.linear.LinearWritePath;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * A {@link RegionFile} whose bytes live in a Linear region file.
 *
 * <p>Why a subclass: Horizon cannot rebuild the server, and mixins cannot
 * change field types, so the vanilla {@code regionCache} (typed
 * {@code RegionFile}) must keep working untouched. This class IS-A
 * {@code RegionFile}, so every vanilla call site typechecks with zero
 * changes; every behavior method is overridden to delegate to the NMS-free
 * core ({@link LinearRegionFile}).
 *
 * <p>Constructor cost (documented trade-off): the superclass constructor
 * eagerly opens and parses its file, so it is pointed at a fresh empty
 * temporary asset. Cost per open is one temp file plus one 8 KiB header
 * buffer; both are released deterministically by {@link #close()} (super
 * close + asset delete). Stale assets after a crash are inert 0-byte files
 * in the system temp dir, never in world folders.
 *
 * <p>Payload symmetry: BOTH the {@code ChunkPos} and {@code long} overloads
 * normalize writes ({@link LinearWritePath#normalizeWritePayload}) and serve
 * reads tolerantly ({@link LinearWritePath#serveReadBytes}), so direct-write
 * callers on any mappings line store raw NBT and legacy enveloped slots read
 * without a migration step.
 */
public class HorizonLinearRegionFile extends RegionFile implements AbstractRegionFile {

    private final LinearRegionFile delegate;
    private final Path realPath;
    private final Path asset;

    /**
     * Opens (or creates) the Linear file at {@code realPath}.
     *
     * @param realPath destination {@code .linear} path (sibling of the region folder)
     * @param level zstd level, clamped by the core
     */
    public HorizonLinearRegionFile(final Path realPath, final int level) throws IOException {
        this(realPath, level, newAsset());
    }

    private HorizonLinearRegionFile(final Path realPath, final int level, final Path[] asset) throws IOException {
        super(
            new RegionStorageInfo("linearmc", Level.OVERWORLD, "chunk"),
            asset[0],
            asset[1],
            false
        );
        this.realPath = realPath;
        this.asset = asset[0];
        this.delegate = new LinearRegionFile(realPath, level);
    }

    /** One temp dir holding one empty asset file (parsed harmlessly by super). */
    private static Path[] newAsset() throws IOException {
        final Path dir = Files.createTempDirectory("linearmc-asset");
        return new Path[] {Files.createTempFile(dir, "empty", ".mca"), dir};
    }

    // ---- identity: the real path, never the temp asset ----

    @Override
    public Path getPath() {
        return this.realPath;
    }

    // ---- chunk contract: core owns every byte ----

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
        // Tolerant serve: slots written before payload normalization hold the
        // vanilla envelope ([len][version][compressed]); unwrap on the fly so
        // legacy data reads without a migration step. Raw slots pass through.
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
        // Normalize BEFORE delegating: direct-write callers (Moonrise async IO
        // on the 1.21 line) hand over the vanilla envelope
        // ([len][version][compressed]), while the store's canonical form is raw
        // NBT (what the streaming path stages and what reads must serve).
        // Storing the envelope serves the version byte as the NBT tag type:
        // "Root tag must be a named compound tag" + Moonrise discards the chunk.
        // Empty buffers pass through as the delete-slot sentinel.
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

    /**
     * Self-reporting: every mutation path marks the file dirty, so no
     * acknowledged write is lost even though the vanilla storage never
     * calls back into a coordinator. Mirrors the fork's
     * {@code RegionFileStorage} reporting. Best-effort by design (the bytes
     * are already staged); the quiet core bridge swallows nothing else.
     */
    private void markDirty() {
        FlushReports.markDirtyForFile(this.realPath.getParent(), this.delegate);
    }

    /**
     * Vanilla sector-streaming write entry point. Core buffered contract: the
     * stream accumulates NBT bytes in memory and its {@code close()} persists
     * them through the core (oversize payloads are rejected, never spilled to
     * sidecars). Coordinator reporting rides on the write/clear paths, as
     * before; durability itself is owned by {@link #flush()}/{@link #close()}.
     */
    @Override
    public DataOutputStream getChunkDataOutputStream(final ChunkPos pos) throws IOException {
        // Safe downcast: the core contract returns a DataOutputStream today.
        return (DataOutputStream) BufferedRegionOutput.forFile(
            this.delegate, ChunkPosCoords.toKey(pos));
    }

    // ---- lifecycle: core durability, then super cleanup + asset removal ----

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
            }
        }
    }

    // ---- anvil-only state: inert by design (no sidecars, no recalc) ----

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
