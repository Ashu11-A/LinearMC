package net.linear;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import net.jpountz.lz4.LZ4Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xymb Linear region file (v2 writer, v1/v2 reader) for the Linear Folia fork.
 *
 * <h2>On-disk format (big-endian, identical to the reference {@code linear.py})</h2>
 * <pre>
 *   header (32 B): int64 SUPERBLOCK, uint8 VERSION=2, int64 newestTimestamp,
 *                  int8 compressionLevel, int16 chunkCount, int32 compressedLength,
 *                  int64 reserved(0)
 *   body:          zstd blob (integrity-checked) of [1024 x (int32 size, int32 timestamp)]
 *                  followed by the raw chunk payloads concatenated in slot order
 *   footer (8 B):  int64 SUPERBLOCK
 * </pre>
 * Chunk payloads are opaque bytes: whatever {@link #write} is given is returned
 * verbatim by {@link #getChunkDataInputStream}. No {@code .mcc} oversized sidecar
 * files are ever created; a single payload larger than {@link #MAX_CHUNK_SIZE} is
 * rejected with an {@link IOException}.
 *
 * <h2>Ownership / concurrency contract</h2>
 * <ul>
 *   <li>No thread-per-file, no background flusher. This class never spawns or
 *       parks threads; the single shared flush pool lives in
 *       {@code LinearFlushCoordinator} (server-wide, bounded
 *       by {@code flush-max-threads}) and calls {@link #flush()} from pool
 *       threads, serialised per-file by {@code flushGuard}.</li>
 *   <li>Two locks, always leaf-ordered, never held across file IO or zstd work:
 *       {@code slotLock} guards the slot tables and the dirty flag in short
 *       critical sections; {@code flushGuard} serialises concurrent
 *       {@link #flush()} calls. Lock order is {@code flushGuard} before
 *       {@code slotLock}; no other nesting exists, so no deadlock is possible.</li>
 *   <li>{@link #write} copies + LZ4-compresses <em>outside</em> the lock, then
 *       swaps the slot reference and marks dirty under the lock. {@link #flush}
 *       snapshots the tables and clears dirty under the lock, then does all
 *       decompress/compress/IO unlocked. A write racing a flush either lands in
 *       the snapshot or re-marks dirty, so no acknowledged write is ever lost.</li>
   *   <li>Durability is owned by the caller (the Moonrise-based storage
 *       layer): it polls {@link #isMarkedToSave()} from its per-world IO threads
 *       and calls {@link #flush()}, which persists via tmp-file + fsync +
 *       atomic move. {@link #clearMarkedToSave()} is an explicit reset for
 *       owner-driven lifecycle handling (e.g. discard after a failed flush and
 *       unload); {@link #flush()} itself clears the flag when it snapshots.</li>
 *   <li>Buffer ownership: {@link #write} copies the caller's remaining bytes
 *       immediately (the caller's buffer may be reused after return, but must
 *       not be mutated concurrently with the call). Reads return a fresh
 *       {@link DataInputStream} per call; the caller owns and must close it.</li>
 * </ul>
 *
 * <h2>GC posture (Spottedleaf rule)</h2>
 * Hot paths use direct buffers end to end: per-chunk RAM staging is
 * direct-to-direct LZ4, the flush image is one direct buffer streamed through
 * {@code ZstdOutputStream}, and file IO uses {@link FileChannel} with direct
 * buffers. The only bounded heap scratch is a single 32 KiB pump array per
 * {@link #flush()} call (not per chunk). Region load is a cold path and may
 * allocate transient heap arrays.
 */
public final class LinearRegionFile implements AbstractRegionFile {

    private static final Logger LOGGER = LoggerFactory.getLogger(LinearRegionFile.class);

    /** Magic opening/closing a region file. Also the signed form -4323716122432332390L. */
    private static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;
    /** Version written by this class. */
    private static final byte VERSION_WRITE = 2;
    /** Oldest version accepted on read (LinearPaper wrote 1 with zeroed timestamps). */
    private static final byte VERSION_MIN = 1;
    /** Latest version accepted on read. */
    private static final byte VERSION_MAX = 2;

    private static final int SLOTS = 32 * 32;
    private static final int HEADER_SIZE = 8 + 1 + 8 + 1 + 2 + 4 + 8; // 32
    private static final int FOOTER_SIZE = 8;
    private static final int TABLE_SIZE = SLOTS * 8; // 8192
    /** Absolute file offset of the int32 compressedLength field inside the header. */
    private static final int LENGTH_FIELD_OFFSET = 8 + 1 + 8 + 1 + 2; // 20

    /** Same bound as vanilla RegionFile: oversized external (.mcc) files are unsupported, so fail loudly instead. */
    public static final int MAX_CHUNK_SIZE = 500 * 1024 * 1024;

    private static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();

    // Linear: zstd worker/LDM resolution suppliers. The adapter registers live
    // config readers at startup; defaults are inert (0 = serial, LDM off),
    // matching the old null-config fallback. Test overrides win over suppliers.
    private static volatile Integer COMPRESSION_WORKERS_OVERRIDE_FOR_TESTS = null;
    private static volatile Integer LONG_DISTANCE_MATCHING_OVERRIDE_FOR_TESTS = null;
    private static volatile java.util.function.IntSupplier COMPRESSION_WORKERS_SUPPLIER = () -> 0;
    private static volatile java.util.function.IntSupplier LONG_DISTANCE_MATCHING_SUPPLIER = () -> 0;

    /** Adapter wiring: live {@code compression-workers} reader (registered once at startup). */
    public static void linear$setCompressionWorkersSupplier(java.util.function.IntSupplier supplier) {
        COMPRESSION_WORKERS_SUPPLIER = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    /** Adapter wiring: live {@code long-distance-matching} reader (registered once at startup). */
    public static void linear$setLongDistanceMatchingSupplier(java.util.function.IntSupplier supplier) {
        LONG_DISTANCE_MATCHING_SUPPLIER = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    private final Path file;
    private final int compressionLevel;

    // Linear: lock-free per-file timing groups. Hot updates use
    // LongAdder/LongAccumulator only (no synchronized); add() calls run AFTER
    // unlock/exit in finally blocks and forward to forFolder(parent).
    private final LongAdder readCount = new LongAdder();
    private final LongAdder readTotalMicros = new LongAdder();
    private final LongAccumulator readMaxMicros = new LongAccumulator(Long::max, 0L);
    private final LongAdder writeCount = new LongAdder();
    private final LongAdder writeTotalMicros = new LongAdder();
    private final LongAccumulator writeMaxMicros = new LongAccumulator(Long::max, 0L);
    private final LongAdder flushCount = new LongAdder();
    private final LongAdder flushTotalMicros = new LongAdder();
    private final LongAccumulator flushMaxMicros = new LongAccumulator(Long::max, 0L);
    private final LongAdder loadCount = new LongAdder();
    private final LongAdder loadTotalMicros = new LongAdder();
    private final LongAccumulator loadMaxMicros = new LongAccumulator(Long::max, 0L);

    private final ReentrantLock slotLock = new ReentrantLock();
    private final ReentrantLock flushGuard = new ReentrantLock();

    /** LZ4-compressed payloads, read-only direct slices; null slot == absent chunk. Guarded by slotLock. */
    private final ByteBuffer[] compressed = new ByteBuffer[SLOTS];
    /** Uncompressed payload lengths. Guarded by slotLock. */
    private final int[] uncompressedSize = new int[SLOTS];
    /** Per-chunk epoch seconds, preserved across load/flush (never zeroed). Guarded by slotLock. */
    private final int[] timestamps = new int[SLOTS];

    private volatile boolean dirty;
    private volatile boolean closed;

    /**
     * Opens (or creates empty state for) the region file at {@code file}.
     *
     * @param file region file path ({@code r.<x>.<z>.linear})
     * @param compressionLevel zstd level used by {@link #flush()}; clamped to 1..22
     */
    public LinearRegionFile(final Path file, final int compressionLevel) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        this.compressionLevel = Math.max(1, Math.min(22, compressionLevel));
        createParentDirs(file);
        if (Files.isRegularFile(file) && Files.size(file) > 0L) {
            this.load();
        }
    }

    /**
     * Linear: resolves zstd worker count via the registered supplier
     * (adapter: global {@code region-format.linear.compression-workers},
     * default 0 = serial). Test override wins.
     */
    static int linear$resolveCompressionWorkers() {
        Integer override = COMPRESSION_WORKERS_OVERRIDE_FOR_TESTS;
        if (override != null) {
            return override;
        }
        try {
            return COMPRESSION_WORKERS_SUPPLIER.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Linear: resolves LDM windowLog via the registered supplier (adapter:
     * global {@code region-format.linear.long-distance-matching}, default
     * 0 = off). Valid 10..27; out-of-range yields 0 (off) without calling
     * setLong (JNI would silently disable anyway). Test override wins.
     */
    static int linear$resolveLongDistanceMatching() {
        Integer override = LONG_DISTANCE_MATCHING_OVERRIDE_FOR_TESTS;
        if (override != null) {
            return override;
        }
        try {
            return LONG_DISTANCE_MATCHING_SUPPLIER.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Test-only: forces compression-workers (null clears). */
    static void linear$setCompressionWorkersForTests(Integer workers) {
        COMPRESSION_WORKERS_OVERRIDE_FOR_TESTS = workers;
    }

    /** Test-only: forces long-distance-matching windowLog (null clears). */
    static void linear$setLongDistanceMatchingForTests(Integer windowLog) {
        LONG_DISTANCE_MATCHING_OVERRIDE_FOR_TESTS = windowLog;
    }

    /** Test-only: clears both zstd overrides. */
    static void linear$resetZstdForTests() {
        COMPRESSION_WORKERS_OVERRIDE_FOR_TESTS = null;
        LONG_DISTANCE_MATCHING_OVERRIDE_FOR_TESTS = null;
    }

    /**
     * Returns a fresh {@link DataInputStream} over the chunk's payload, or null
     * if the chunk is absent. The stream reads from a direct buffer with no heap
     * copy of the payload; the caller owns it and must close it.
     */
    @Override
    public DataInputStream getChunkDataInputStream(final long chunk) throws IOException {
        // Linear: read timing, recorded AFTER unlock/exit.
        final long startNanos = System.nanoTime();
        try {
        ensureOpen();
                final int index = slotIndex(chunk);

        final ByteBuffer src;
        final int size;
        this.slotLock.lock();
        try {
            src = this.compressed[index];
            size = this.uncompressedSize[index];
        } finally {
            this.slotLock.unlock();
        }
        if (src == null || size <= 0) {
            return null;
        }

        final ByteBuffer out = ByteBuffer.allocateDirect(size);
        final ByteBuffer frame = src.duplicate();
        // NB: the fast decompressor returns the input frame length,
        // not dst bytes written; an over/under-run throws out of the call itself.
        final int consumed = LZ4.fastDecompressor().decompress(frame, frame.position(), out, 0, size);
        if (consumed != frame.remaining()) {
            throw new IOException("LZ4 frame mismatch for chunk " + ChunkKey.format(chunk) + " in " + this.file
                + ": frame is " + frame.remaining() + " bytes, decompressor consumed " + consumed);
        }
        // Absolute-offset LZ4 calls leave positions untouched, so expose
        // exactly the decompressed range instead of flip()ing (which would empty it).
        out.position(size);
        out.flip();
            return new DataInputStream(LinearDirectStreams.wrap(out));
        } finally {
            final long micros = (System.nanoTime() - startNanos) / 1000L;
            this.readCount.increment();
            this.readTotalMicros.add(micros);
            this.readMaxMicros.accumulate(micros);
            try {
                Path parent = this.file.getParent();
                if (parent == null) {
                    parent = this.file.toAbsolutePath().getParent();
                }
                if (parent != null) {
                    LinearFlushCoordinator.forFolder(parent).recordRead(micros);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Stages a chunk payload in RAM (LZ4-compressed) and marks the region dirty.
     * The payload is opaque and returned verbatim on read. A zero-length buffer
     * deletes the slot. Persisting is the owner's job via {@link #flush()}.
     *
     * @param chunk packed coordinates (must belong to this region's 32x32)
     * @param buf payload bytes (remaining); copied before return
     */
    @Override
    public void write(final long chunk, final ByteBuffer buf) throws IOException {
        // Linear: write timing, recorded AFTER unlock/exit.
        final long startNanos = System.nanoTime();
        try {
        ensureOpen();
                Objects.requireNonNull(buf, "buf");
        final int index = slotIndex(chunk);
        final int size = buf.remaining();

        if (size <= 0) {
            this.slotLock.lock();
            try {
                this.compressed[index] = null;
                this.uncompressedSize[index] = 0;
                this.timestamps[index] = nowSeconds();
                this.dirty = true;
            } finally {
                this.slotLock.unlock();
            }
            return;
        }
        if (size > MAX_CHUNK_SIZE) {
            // Linear: oversize-throw records then rethrows unchanged.
            try {
                Path parent = this.file.getParent();
                if (parent == null) {
                    parent = this.file.toAbsolutePath().getParent();
                }
                if (parent != null) {
                    LinearFlushCoordinator.forFolder(parent).recordOversizeReject();
                }
            } catch (RuntimeException ignored) {
            }
            throw new IOException("Chunk " + ChunkKey.format(chunk) + " is " + size + " bytes, exceeds " + MAX_CHUNK_SIZE
                + "; oversized .mcc external files are unsupported");
        }

        // Copy + compress outside the lock so the critical section stays tiny.
        final ByteBuffer plain = ByteBuffer.allocateDirect(size);
        plain.put(buf.duplicate());
        plain.flip();
        final int maxCompressed = LZ4.fastCompressor().maxCompressedLength(size);
        final ByteBuffer packed = ByteBuffer.allocateDirect(maxCompressed);
        final int packedSize = LZ4.fastCompressor().compress(plain, 0, size, packed, 0, maxCompressed);
        packed.position(0);
        packed.limit(packedSize);
        final ByteBuffer stored = packed.slice().asReadOnlyBuffer();

        this.slotLock.lock();
        try {
            this.compressed[index] = stored;
            this.uncompressedSize[index] = size;
            this.timestamps[index] = nowSeconds();
            this.dirty = true;
        } finally {
            this.slotLock.unlock();
        }
        } finally {
            final long micros = (System.nanoTime() - startNanos) / 1000L;
            this.writeCount.increment();
            this.writeTotalMicros.add(micros);
            this.writeMaxMicros.accumulate(micros);
            try {
                Path parent = this.file.getParent();
                if (parent == null) {
                    parent = this.file.toAbsolutePath().getParent();
                }
                if (parent != null) {
                    LinearFlushCoordinator.forFolder(parent).recordWrite(micros);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Returns true if the chunk slot holds a payload. Fail-fast when closed. */
    @Override
    public boolean hasChunk(final long chunk) {
        if (this.closed) {
            throw new IllegalStateException("LinearRegionFile is closed: " + this.file);
        }
                this.slotLock.lock();
        try {
            return this.uncompressedSize[slotIndex(chunk)] > 0;
        } finally {
            this.slotLock.unlock();
        }
    }

    /**
     * Deletes the payload for {@code chunk} (matching {@code RegionFile.clear}).
     * Equivalent to {@code write(chunk, empty)}: slot becomes absent on re-read
     * ({@link #hasChunk} false, {@link #getChunkDataInputStream} null) and the
     * region is marked dirty. Satisfies {@link AbstractRegionFile#clear}.
     */
    @Override
    public void clear(final long chunk) throws IOException {
        this.write(chunk, ByteBuffer.allocate(0));
    }

    /**
     * Persists dirty state via tmp-file + fsync + atomic move (with a
     * non-atomic fallback). No-op when clean. On failure the dirty flag is
     * re-armed (RAM remains the truth) and the exception propagates.
     */
    @Override
    public void flush() throws IOException {
        ensureOpen();
        this.doFlush();
    }

    /** Dirty check for the owning storage layer; leaves the flag set. */
    @Override
    public boolean isMarkedToSave() {
        return this.dirty;
    }

    /** Owner-driven reset of the dirty flag; does not touch slot data. */
    @Override
    public void clearMarkedToSave() {
        this.slotLock.lock();
        try {
            this.dirty = false;
        } finally {
            this.slotLock.unlock();
        }
    }

    /**
     * Linear linearstats: per-file timing view (pull-only, lock-free sums).
     * Zero new storage fields on the storage side; the command aggregates
     * these via the coordinator snapshots(). No Bukkit imports, no threads,
     * no synchronized on the hot path.
     */
    public LinearRegionTimings.LinearRegionStats linear$stats() {
        return new LinearRegionTimings.LinearRegionStats(
            LinearRegionTimings.LinearTimings.of(this.readCount, this.readTotalMicros, this.readMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.writeCount, this.writeTotalMicros, this.writeMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.flushCount, this.flushTotalMicros, this.flushMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.loadCount, this.loadTotalMicros, this.loadMaxMicros));
    }

    /**
     * Flushes dirty state (if any) and closes. No threads are involved, so
     * close only does file IO when there is unsaved work.
     */
    @Override
    public void close() throws IOException {
        this.slotLock.lock();
        try {
            if (this.closed) {
                return;
            }
            this.closed = true;
        } finally {
            this.slotLock.unlock();
        }
        this.doFlush();
    }

    // internal

    private void doFlush() throws IOException {
        // Linear: flush timing, recorded AFTER guard unlock/exit.
        // Clean-no-op records nothing via didIo (close() needs no
        // special case; the guard covers it). The timer starts AFTER
        // flushGuard acquisition so lock contention is not billed as I/O,
        // and only successful flushes enter the coordinator p50/p99 buckets
        // (failures re-mark dirty and retry; see flushOk).
        boolean didIo = false;
        boolean flushOk = false;
        this.flushGuard.lock();
        final long startNanos = System.nanoTime();
        try {
        try {
            final ByteBuffer[] snapPacked = new ByteBuffer[SLOTS];
            final int[] snapSize = new int[SLOTS];
            final int[] snapTime = new int[SLOTS];
            int chunkCount = 0;
            long newest = 0L;

            this.slotLock.lock();
            try {
                if (!this.dirty) {
                    return;
                }
                for (int i = 0; i < SLOTS; i++) {
                    snapPacked[i] = this.compressed[i];
                    snapSize[i] = this.uncompressedSize[i];
                    snapTime[i] = this.timestamps[i];
                    if (snapSize[i] > 0) {
                        chunkCount++;
                        if ((snapTime[i] & 0xFFFFFFFFL) > newest) {
                            newest = snapTime[i] & 0xFFFFFFFFL;
                        }
                    }
                }
                this.dirty = false;
            } finally {
                this.slotLock.unlock();
            }

            if (chunkCount == 0 && !Files.isRegularFile(this.file)) {
                return; // never persisted and still empty: leave no file behind
            }
            didIo = true; // Linear: past both clean-no-op exits, IO follows.

            // Linear - decompress each LZ4 slot straight into its
            // final offset in image (removes one full region copy; previously
            // held plain[] AND image ~2x). Absolute LZ4 calls leave positions
            // untouched, so the table puts + absolute decompresses compose.
            long totalRaw = 0L;
            for (int i = 0; i < SLOTS; i++) {
                if (snapSize[i] > 0) {
                    totalRaw += snapSize[i];
                    if (totalRaw > (long) Integer.MAX_VALUE - TABLE_SIZE) {
                        throw new IOException("Region image too large for " + this.file);
                    }
                }
            }

            // Assemble the decompressed region image in one direct buffer.
            final ByteBuffer image = ByteBuffer.allocateDirect(TABLE_SIZE + (int) totalRaw);
            for (int i = 0; i < SLOTS; i++) {
                image.putInt(snapSize[i]);
                image.putInt(snapTime[i]);
            }
            long writeOffset = TABLE_SIZE;
            for (int i = 0; i < SLOTS; i++) {
                if (snapSize[i] > 0) {
                    final ByteBuffer frame = snapPacked[i].duplicate();
                    final int consumed = LZ4.fastDecompressor().decompress(
                        frame, frame.position(), image, (int) writeOffset, snapSize[i]);
                    if (consumed != frame.remaining()) {
                        throw new IOException("LZ4 frame mismatch in slot " + i + " of " + this.file);
                    }
                    writeOffset += snapSize[i];
                }
            }
            // Table puts advanced position to TABLE_SIZE; absolute decompresses
            // did not move it, so position explicitly to the end before flip.
            image.position((int) (TABLE_SIZE + totalRaw));
            image.flip();

            // Stream to tmp: header (length patched later) + zstd frame + footer.
            // Length is patched positionally afterwards so the zstd body never
            // needs to be buffered in memory first.
            final Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            createParentDirs(tmp);
            // Linear: hoisted for byte counters below (lock-free).
            long packedForMetrics = 0L;
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                final ByteBuffer header = ByteBuffer.allocateDirect(HEADER_SIZE);
                header.putLong(SUPERBLOCK);
                header.put(VERSION_WRITE);
                header.putLong(newest);
                header.put((byte) this.compressionLevel);
                header.putShort((short) chunkCount);
                header.putInt(0); // patched after the frame completes
                header.putLong(0L); // reserved
                header.flip();
                LinearDirectStreams.writeFully(channel, header);

                final OutputStream fileOut = LinearDirectStreams.uncloseable(Channels.newOutputStream(channel));
                try (ZstdOutputStream zstd = new ZstdOutputStream(fileOut, this.compressionLevel)) {
                    zstd.setChecksum(true);
                    // Linear: writer setWorkers/setLong, both default 0 =
                    // inert (serial, LDM off). MUST precede first write() or
                    // IllegalStateException (fresh stream per flush, so
                    // per-flush setters are safe). JNI caps setLong at
                    // windowLog 27; workers at high levels show zero speedup
                    // for large native cost; LDM at low levels is cheap.
                    // MT at level 22 has zero speedup and ~690MB native cost.
                    // NATIVE-MEMORY CAUTION: setWorkers(n) at high levels
                    // reserves GBs (level 22 + workers=2 is +3.2GB VSZ for zero
                    // gain, single 512MiB job); use workers only at low levels
                    // (level <= ~9 where jobs exist). Level 22 + flush threads
                    // >=4 can exhaust a 6GiB container.
                    final int zstdWorkers = linear$resolveCompressionWorkers();
                    if (zstdWorkers > 0) {
                        zstd.setWorkers(zstdWorkers);
                    }
                    final int ldmWindow = linear$resolveLongDistanceMatching();
                    if (ldmWindow >= 10 && ldmWindow <= 27) {
                        zstd.setLong(ldmWindow);
                    }
                    // 0 or out-of-range = LDM off (do not call; JNI would
                    // silently disable and reset windowLog to 0 anyway).
                    final byte[] scratch = new byte[32 * 1024]; // single bounded pump buffer per flush
                    while (image.hasRemaining()) {
                        final int n = Math.min(scratch.length, image.remaining());
                        image.get(scratch, 0, n);
                        zstd.write(scratch, 0, n);
                    }
                }

                final ByteBuffer footer = ByteBuffer.allocateDirect(FOOTER_SIZE);
                footer.putLong(SUPERBLOCK);
                footer.flip();
                LinearDirectStreams.writeFully(channel, footer);

                final long packedLength = channel.position() - HEADER_SIZE - FOOTER_SIZE;
                if (packedLength < 0L || packedLength > Integer.MAX_VALUE) {
                    throw new IOException("Invalid zstd frame length " + packedLength + " for " + this.file);
                }
                packedForMetrics = packedLength;
                final ByteBuffer patch = ByteBuffer.allocateDirect(4);
                patch.putInt((int) packedLength);
                patch.flip();
                channel.write(patch, LENGTH_FIELD_OFFSET);
                channel.force(true);
            }

            try {
                Files.move(tmp, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException fallback) {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
            // Linear: byte counters (lock-free, no pool re-cut).
            // totalRaw is the uncompressed image (TABLE_SIZE + payloads);
            // packedForMetrics is the zstd frame. Recorded only on success
            // (after move); failures re-mark dirty and record nothing here.
            try {
                Path parent = this.file.getParent();
                if (parent == null) {
                    parent = this.file.toAbsolutePath().getParent();
                }
                if (parent != null) {
                    LinearFlushCoordinator.forFolder(parent).recordFlushBytes(totalRaw, packedForMetrics);
                }
            } catch (RuntimeException ignored) {
            }
            flushOk = true;
        } catch (final IOException e) {
            this.markDirty();
            throw e;
        } catch (final RuntimeException e) {
            this.markDirty();
            throw e;
        } finally {
            this.flushGuard.unlock();
        }
        } finally {
            if (didIo) { // Linear: clean-no-op records nothing.
                final long micros = (System.nanoTime() - startNanos) / 1000L;
                this.flushCount.increment();
                this.flushTotalMicros.add(micros);
                this.flushMaxMicros.accumulate(micros);
                try {
                    Path parent = this.file.getParent();
                    if (parent == null) {
                        parent = this.file.toAbsolutePath().getParent();
                    }
                    if (parent != null) {
                        LinearFlushCoordinator.forFolder(parent).recordFlush(micros, flushOk);
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void load() throws IOException {
        // Linear: load timing, recorded AFTER publish unlock/exit.
        final long startNanos = System.nanoTime();
        try {
        final ByteBuffer[] staged = new ByteBuffer[SLOTS];
        final int[] stagedSize = new int[SLOTS];
        final int[] stagedTime = new int[SLOTS];

        try (FileChannel channel = FileChannel.open(this.file, StandardOpenOption.READ)) {
            final long fileLength = channel.size();
            if (fileLength > Integer.MAX_VALUE) {
                throw new IOException("Region file too large: " + this.file + " (" + fileLength + " bytes)");
            }
            if (fileLength < (long) HEADER_SIZE + FOOTER_SIZE) {
                throw new IOException("Region file truncated: " + this.file + " (" + fileLength + " bytes)");
            }
            final ByteBuffer raw = ByteBuffer.allocateDirect((int) fileLength);
            LinearDirectStreams.readFully(channel, raw);
            raw.flip();

            if (raw.getLong() != SUPERBLOCK) {
                throw new IOException("Invalid superblock in " + this.file);
            }
            final byte version = raw.get();
            if (version < VERSION_MIN || version > VERSION_MAX) {
                throw new IOException("Unsupported linear version " + version + " in " + this.file);
            }
            raw.getLong(); // newestTimestamp: recomputed on flush, informational only
            raw.get(); // compressionLevel: informational, this instance keeps its own
            final int expectedChunks = raw.getShort() & 0xFFFF;
            final int packedLength = raw.getInt();
            raw.getLong(); // reserved
            if (packedLength < 0 || (long) HEADER_SIZE + packedLength + FOOTER_SIZE != fileLength) {
                throw new IOException("Invalid packed length " + packedLength + " in " + this.file
                    + " (file is " + fileLength + " bytes)");
            }
            final ByteBuffer blob = raw.slice();
            blob.limit(packedLength);
            if (raw.getLong(HEADER_SIZE + packedLength) != SUPERBLOCK) {
                throw new IOException("Invalid footer superblock in " + this.file);
            }

            // Linear: reader setLongMax(27) ships WITH the writer
            // (reader-first for any future windowLog>27). Harmless no-op
            // today (decoder default already 27, setLong capped at 27 by
            // JNI; LDM-27 frames decode with stock readers). Set immediately
            // after construction, before first read(); only a
            // setWindowLog(28..31) writer would need >27.
            final ZstdInputStream zstdIn = new ZstdInputStream(LinearDirectStreams.wrap(blob));
            zstdIn.setLongMax(27);
            try (DataInputStream in = new DataInputStream(zstdIn)) {
                final int[] sizes = new int[SLOTS];
                final int[] times = new int[SLOTS];
                long totalRaw = 0L;
                int present = 0;
                for (int i = 0; i < SLOTS; i++) {
                    sizes[i] = in.readInt();
                    times[i] = in.readInt();
                    if (sizes[i] < 0 || sizes[i] > MAX_CHUNK_SIZE) {
                        throw new IOException("Invalid chunk size " + sizes[i] + " in slot " + i + " of " + this.file);
                    }
                    if (sizes[i] > 0) {
                        present++;
                        totalRaw += sizes[i];
                        if (totalRaw > (long) Integer.MAX_VALUE - TABLE_SIZE) {
                            throw new IOException("Region image too large in " + this.file);
                        }
                    }
                }
                if (present != expectedChunks) {
                    // Tolerate (LinearPaper-era and third-party tools disagree on
                    // edge cases); the table itself is authoritative.
                    LOGGER.warn("Chunk count mismatch in {}: header says {}, table has {}", this.file, expectedChunks, present);
                }
                for (int i = 0; i < SLOTS; i++) {
                    if (sizes[i] > 0) {
                        // Cold path (open): exact heap read, then direct LZ4 staging.
                        final byte[] payload = in.readNBytes(sizes[i]);
                        if (payload.length != sizes[i]) {
                            throw new IOException("Truncated payload in slot " + i + " of " + this.file);
                        }
                        staged[i] = lz4Pack(ByteBuffer.wrap(payload));
                        stagedSize[i] = sizes[i];
                        stagedTime[i] = times[i];
                    }
                }
                if (in.read() != -1) {
                    throw new IOException("Trailing bytes after payloads in " + this.file);
                }
            }
        }

        this.slotLock.lock();
        try {
            System.arraycopy(staged, 0, this.compressed, 0, SLOTS);
            System.arraycopy(stagedSize, 0, this.uncompressedSize, 0, SLOTS);
            System.arraycopy(stagedTime, 0, this.timestamps, 0, SLOTS);
            this.dirty = false;
        } finally {
            this.slotLock.unlock();
        }
        } finally {
            final long micros = (System.nanoTime() - startNanos) / 1000L;
            this.loadCount.increment();
            this.loadTotalMicros.add(micros);
            this.loadMaxMicros.accumulate(micros);
            try {
                Path parent = this.file.getParent();
                if (parent == null) {
                    parent = this.file.toAbsolutePath().getParent();
                }
                if (parent != null) {
                    LinearFlushCoordinator.forFolder(parent).recordLoad(micros);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static ByteBuffer lz4Pack(final ByteBuffer plain) {
        final int size = plain.remaining();
        final int maxCompressed = LZ4.fastCompressor().maxCompressedLength(size);
        final ByteBuffer packed = ByteBuffer.allocateDirect(maxCompressed);
        final int packedSize = LZ4.fastCompressor().compress(plain, plain.position(), size, packed, 0, maxCompressed);
        packed.position(0);
        packed.limit(packedSize);
        return packed.slice().asReadOnlyBuffer();
    }

    private void markDirty() {
        this.slotLock.lock();
        try {
            this.dirty = true;
        } finally {
            this.slotLock.unlock();
        }
    }

    private void ensureOpen() throws IOException {
        if (this.closed) {
            throw new IOException("LinearRegionFile is closed: " + this.file);
        }
    }

    private static int slotIndex(final long chunk) {
        return ChunkKey.slotIndex(chunk);
    }

    private static int nowSeconds() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    private static void createParentDirs(final Path path) throws IOException {
        final Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
