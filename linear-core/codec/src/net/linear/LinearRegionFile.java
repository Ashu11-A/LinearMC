package net.linear;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
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
import net.linear.config.LinearFileConfig;
import net.linear.config.LinearPolicy;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.PumpkinLinear;
import net.linear.versions.RegionFormatDetector;
import net.linear.versions.V1Linear;
import net.linear.versions.V2Linear;
import net.linear.versions.V3Linear;
import net.linear.versions.Version;
import net.linear.versions.VersionWriteParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xymb Linear region file (V1/V2/V3 reader, configurable V1/V2/V3 writer) for the Linear Folia fork.
 *
 * <h2>On-disk format (big-endian, identical to the reference {@code linear.py})</h2>
 * <pre>
 *   V1 (wire 1/2) header (32 B): int64 SUPERBLOCK, uint8 VERSION, int64 newestTimestamp,
 *                  int8 compressionLevel, int16 chunkCount, int32 compressedLength,
 *                  int64 reserved(0)
 *   V1 body:       zstd blob (integrity-checked) of [1024 x (int32 size, int32 timestamp)]
 *                  followed by the raw chunk payloads concatenated in slot order
 *   V1 footer (8 B): int64 SUPERBLOCK
 *   V2 (wire 3) / V3 (wire 4): DivineMC bucketed layout, one zstd frame per
 *                  grid bucket with per-bucket xxhash64 (see {@link LinearBucketReader}).
 *                  Read into the same slot tables; flushed in the configured
 *                  write implementation (see {@link #linear$writeImplementation()}).
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
    /** Hard cap guarding the single direct load allocation against corrupt lengths. */
    private static final long MAX_REGION_FILE_BYTES = 1024L * 1024L * 1024L;
    /**
     * Hard cap on summed decompressed payloads per region, enforced identically
     * on every write path (V1 image, V2/V3 buckets, buffered master) and on
     * load: a flush must never emit a file its own reader rejects, and no
     * crafted table may turn a small file into a multi-gigabyte staging
     * allocation (decompression-bomb guard).
     *
     * <p>Public so the {@code net.linear.versions} codecs enforce the same
     * single bound.</p>
     */
    public static final long MAX_IMAGE_BYTES = MAX_REGION_FILE_BYTES - 65536L;

    private static final int SLOTS = 32 * 32;
    private static final int HEADER_SIZE = 8 + 1 + 8 + 1 + 2 + 4 + 8; // 32
    private static final int FOOTER_SIZE = 8;

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
    /** Cached parent folder for coordinator reporting (avoids per-op getParent lookups). */
    private final Path parentDir;

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

    /** On-disk implementation this file writes (read accepts V1/V2/V3 regardless). */
    private final LinearImplementation writeImplementation;
    /** Bucket grid size for V2/V3 writes (ignored for V1). */
    private final int gridSize;
    /**
     * Format detected at open (null when created empty: no file was loaded).
     * Pumpkin-loaded files flush back as Pumpkin (preserve-on-flush); every
     * other format flushes in the configured write implementation.
     */
    private volatile RegionFormatDetector.DetectedFormat detectedFormat;
    /** Bucket grid encoded in a Pumpkin-loaded file (0 unless detected as Pumpkin). */
    private volatile int detectedGrid;

    /**
     * Opens (or creates empty state for) the region file at {@code file}.
     *
     * @param file region file path ({@code r.<x>.<z>.linear})
     * @param compressionLevel zstd level used by {@link #flush()}; clamped to 1..22
     */
    public LinearRegionFile(final Path file, final int compressionLevel) throws IOException {
        this(file, compressionLevel, LinearFileConfig.resolveImplementation(), LinearFileConfig.resolveGridSize());
    }

    /**
     * Opens with an explicit write implementation and grid size.
     *
     * @param file region file path ({@code r.<x>.<z>.linear})
     * @param compressionLevel zstd level used by {@link #flush()}; clamped to 1..22
     * @param writeImplementation V1 (single blob), V2/V3 (bucketed); {@code null} yields V1
     * @param gridSize bucket grid for V2/V3; off-whitelist yields 8
     */
    public LinearRegionFile(final Path file, final int compressionLevel,
            final LinearImplementation writeImplementation, final int gridSize) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        this.compressionLevel = RegionFileFormat.clampCompressionLevel(compressionLevel);
        this.writeImplementation = writeImplementation != null ? writeImplementation : LinearImplementation.V1;
        this.gridSize = LinearBucketGrid.isValid(gridSize) ? gridSize : LinearPolicy.DEFAULT_GRID_SIZE;
        this.parentDir = file.toAbsolutePath().getParent();
        createParentDirs(file);
        if (Files.isRegularFile(file) && Files.size(file) > 0L) {
            this.load();
        }
    }

    /** Write implementation selected at open (V1 unless configured). */
    public LinearImplementation linear$writeImplementation() {
        return this.writeImplementation;
    }

    /** Bucket grid size used for V2/V3 writes. */
    public int linear$gridSize() {
        return this.gridSize;
    }

    /**
     * Format detected at open ({@code null} when created empty and nothing
     * was loaded). Used by tests and the converter's per-file format checks.
     */
    public RegionFormatDetector.DetectedFormat linear$detectedFormat() {
        return this.detectedFormat;
    }

    /** Bucket grid encoded in a Pumpkin-loaded file (0 unless Pumpkin was detected). */
    public int linear$detectedGrid() {
        return this.detectedGrid;
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
        final int consumed;
        try {
            consumed = LZ4.fastDecompressor().decompress(frame, frame.position(), out, 0, size);
        } catch (RuntimeException e) {
            throw new IOException("LZ4 decompress failed for chunk " + ChunkKey.format(chunk)
                + " in " + this.file + ": " + e.getMessage(), e);
        }
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
                if (this.parentDir != null) {
                    LinearFlushCoordinator.forFolder(this.parentDir).recordRead(micros);
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
                ensureOpen();
                this.compressed[index] = null;
                this.uncompressedSize[index] = 0;
                this.timestamps[index] = nowSeconds();
                this.dirty = true;
            } finally {
                this.slotLock.unlock();
            }
            reportDirty();
            return;
        }
        if (size > MAX_CHUNK_SIZE) {
            // Linear: oversize-throw records then rethrows unchanged.
            try {
                if (this.parentDir != null) {
                    LinearFlushCoordinator.forFolder(this.parentDir).recordOversizeReject();
                }
            } catch (RuntimeException ignored) {
            }
            throw new IOException("Chunk " + ChunkKey.format(chunk) + " is " + size + " bytes, exceeds " + MAX_CHUNK_SIZE
                + " in " + this.file + "; oversized .mcc external files are unsupported");
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
            ensureOpen();
            this.compressed[index] = stored;
            this.uncompressedSize[index] = size;
            this.timestamps[index] = nowSeconds();
            this.dirty = true;
        } finally {
            this.slotLock.unlock();
        }
        reportDirty();
        } finally {
            final long micros = (System.nanoTime() - startNanos) / 1000L;
            this.writeCount.increment();
            this.writeTotalMicros.add(micros);
            this.writeMaxMicros.accumulate(micros);
            try {
                if (this.parentDir != null) {
                    LinearFlushCoordinator.forFolder(this.parentDir).recordWrite(micros);
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
                if (!this.dirty) {
                    return;
                }
                // Previous close() failed after setting closed: retry the drain below.
            } else {
                this.closed = true;
            }
        } finally {
            this.slotLock.unlock();
        }
        // A write that passed ensureOpen before closed was set may re-dirty
        // after the first snapshot; drain until clean so close never drops it.
        // A failed flush re-arms dirty (doFlush) and is retried once below;
        // a second failure propagates with dirty intact (caller inspects).
        // doFlush only throws IOException/RuntimeException/Error; unchecked
        // failures are retried the same way so a transient zstd/LZ4 fault
        // does not strand acknowledged writes.
        IOException firstIoFailure = null;
        RuntimeException firstRuntimeFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                this.doFlush();
            } catch (IOException e) {
                if (firstIoFailure == null) {
                    firstIoFailure = e;
                }
            } catch (RuntimeException e) {
                if (firstRuntimeFailure == null) {
                    firstRuntimeFailure = e;
                }
            }
            this.slotLock.lock();
            boolean stillDirty;
            try {
                stillDirty = this.dirty;
            } finally {
                this.slotLock.unlock();
            }
            if (!stillDirty) {
                return;
            }
        }
        this.slotLock.lock();
        try {
            if (this.dirty) {
                if (firstRuntimeFailure != null) {
                    throw firstRuntimeFailure;
                }
                throw firstIoFailure != null ? firstIoFailure
                    : new IOException("Unflushed writes remain on close of " + this.file);
            }
        } finally {
            this.slotLock.unlock();
        }
        if (firstRuntimeFailure != null) {
            throw firstRuntimeFailure;
        }
        if (firstIoFailure != null) {
            throw firstIoFailure;
        }
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
        Path tmp = null;
        this.flushGuard.lock();
        final long startNanos = System.nanoTime();
        try {
        try {
            final ChunkSlotTable snapshot = new ChunkSlotTable();
            int chunkCount = 0;
            long newest = 0L;
            long totalRaw = 0L;

            this.slotLock.lock();
            try {
                if (!this.dirty) {
                    return;
                }
                for (int i = 0; i < SLOTS; i++) {
                    snapshot.lz4Payloads[i] = this.compressed[i];
                    snapshot.uncompressedSize[i] = this.uncompressedSize[i];
                    snapshot.timestamps[i] = this.timestamps[i];
                    if (snapshot.uncompressedSize[i] > 0) {
                        chunkCount++;
                        totalRaw += snapshot.uncompressedSize[i];
                        if ((snapshot.timestamps[i] & 0xFFFFFFFFL) > newest) {
                            newest = snapshot.timestamps[i] & 0xFFFFFFFFL;
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

            final int[] coords =
                LinearBucketWriter.parseRegionCoordinates(this.file.getFileName().toString());
            // Pumpkin preserve-on-flush: no LinearImplementation exists for Pumpkin, so the
            // configured implementation cannot express it; rewriting Rust-generated files
            // unprompted is the dangerous direction, so a Pumpkin-loaded region keeps
            // round-tripping as Pumpkin (grid + coords preserved). Files created empty
            // (detectedFormat null) stay on the configured path.
            final boolean preservePumpkin =
                this.detectedFormat == RegionFormatDetector.DetectedFormat.PUMPKIN;
            final Version version;
            final VersionWriteParams params;
            if (preservePumpkin) {
                version = PumpkinLinear.shared();
                params = new VersionWriteParams(
                    this.compressionLevel, this.detectedGrid, coords[0], coords[1], newest,
                    linear$resolveCompressionWorkers(), linear$resolveLongDistanceMatching());
                LOGGER.debug("Preserving Pumpkin layout on flush of {} (grid {})",
                    this.file, this.detectedGrid);
            } else {
                version = LinearBase.getShared(this.writeImplementation.version());
                params = new VersionWriteParams(
                    this.compressionLevel, this.gridSize, coords[0], coords[1], newest,
                    linear$resolveCompressionWorkers(), linear$resolveLongDistanceMatching());
            }
            // Pure buffer transform: the version codec renders the whole file
            // image; tmp + fsync + atomic move below are unchanged. File
            // context is re-attached here (codecs carry no path).
            final ByteBuffer image;
            try {
                image = version.flush(snapshot, params);
            } catch (IOException e) {
                throw new IOException(e.getMessage() + " in " + this.file, e);
            }
            final int imageSize = image.remaining();

            tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            createParentDirs(tmp);
            didIo = true; // Past validation: IO follows (validation failures bill nothing).
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                LinearDirectStreams.writeFully(channel, image);
                channel.force(true);
            }

            try {
                Files.move(tmp, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException fallback) {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
            // Linear: byte counters (lock-free, no pool re-cut).
            // totalRaw is the summed payload bytes; packed is the compressed
            // body (table + bucket frames for V2/V3, zstd frame for V1),
            // derived from the rendered image. Recorded only on success
            // (after move); failures re-mark dirty and record nothing here.
            final int headerLength;
            if (preservePumpkin) {
                headerLength = PumpkinLinear.headerLength();
            } else if (version.implementation() == LinearImplementation.V2) {
                headerLength = V2Linear.headerLength();
            } else if (version.implementation() == LinearImplementation.V3) {
                headerLength = V3Linear.headerLength();
            } else {
                headerLength = V1Linear.headerLength();
            }
            final long packedForMetrics = (long) imageSize - headerLength - FOOTER_SIZE;
            try {
                if (this.parentDir != null) {
                    LinearFlushCoordinator.forFolder(this.parentDir).recordFlushBytes(totalRaw, packedForMetrics);
                }
            } catch (RuntimeException ignored) {
            }
            flushOk = true;
        } catch (final IOException e) {
            this.markDirty();
            deleteQuietly(tmp);
            throw e;
        } catch (final RuntimeException e) {
            this.markDirty();
            deleteQuietly(tmp);
            throw e;
        } catch (final Error e) {
            // OOM/zstd-native Error must not leave acknowledged writes clean.
            this.markDirty();
            deleteQuietly(tmp);
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
                    if (this.parentDir != null) {
                        LinearFlushCoordinator.forFolder(this.parentDir).recordFlush(micros, flushOk);
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
        // Killed-flush orphan (forced-but-unmoved image): the master is old
        // truth until the move lands, so a sibling .tmp is garbage. The
        // storage layer holds one handle per region file, so no live flush
        // can be authoring this path during open.
        deleteQuietly(this.file.resolveSibling(this.file.getFileName() + ".tmp"));
        final ChunkSlotTable staged = new ChunkSlotTable();
        staged.sourceName = this.file.toString();

        try (FileChannel channel = FileChannel.open(this.file, StandardOpenOption.READ)) {
            final long fileLength = channel.size();
            if (fileLength > MAX_REGION_FILE_BYTES) {
                throw new IOException("Region file too large: " + this.file + " (" + fileLength + " bytes)");
            }
            if (fileLength < (long) HEADER_SIZE + FOOTER_SIZE) {
                throw new IOException("Region file truncated: " + this.file + " (" + fileLength + " bytes)");
            }
            final ByteBuffer raw = ByteBuffer.allocateDirect((int) fileLength);
            LinearDirectStreams.readFully(channel, raw);
            raw.flip();

            if (raw.getLong(0) != SUPERBLOCK) {
                throw new IOException("Invalid superblock in " + this.file);
            }
            final int[] fileCoords =
                LinearBucketWriter.parseRegionCoordinates(this.file.getFileName().toString());
            final RegionFormatDetector.DetectedFormat detected;
            try {
                detected = RegionFormatDetector.shared().detect(raw, fileCoords[0], fileCoords[1]);
            } catch (IOException e) {
                throw new IOException(e.getMessage() + " in " + this.file, e);
            }
            final Version version;
            switch (detected) {
                case V1:
                    version = V1Linear.shared();
                    break;
                case V2:
                    version = V2Linear.shared();
                    break;
                case V3:
                    version = V3Linear.shared();
                    break;
                case PUMPKIN:
                    version = PumpkinLinear.shared();
                    break;
                default:
                    throw new IOException("Unsupported linear layout " + detected + " in " + this.file);
            }
            // Pure buffer transform: the version codec validates its own
            // magic/version/footer and fills the staged tables. File context
            // is re-attached here (codecs carry no path).
            try {
                version.parse(raw, staged);
            } catch (IOException e) {
                throw new IOException(e.getMessage() + " in " + this.file, e);
            }
            try {
                this.detectedFormat = detected;
                this.detectedGrid = RegionFormatDetector.shared().gridOf(raw, detected);
            } catch (IOException e) {
                throw new IOException(e.getMessage() + " in " + this.file, e);
            }
            final byte wire = raw.get(8);
            final LinearImplementation impl = version.implementation();
            if (detected == RegionFormatDetector.DetectedFormat.PUMPKIN) {
                LOGGER.info("Loaded {} as PUMPKIN; preserving Pumpkin layout on flush (grid {})",
                    this.file, this.detectedGrid);
            } else if (impl == LinearImplementation.V2 || impl == LinearImplementation.V3) {
                // DivineMC bucketed layout: inflated into the V1 slot tables.
                // Next flush rewrites in the configured write implementation.
                LOGGER.info("Loaded {} as {} (wire {}); will rewrite as {} on next flush",
                    this.file, impl, wire & 0xFF, this.writeImplementation);
            }
        }

        this.slotLock.lock();
        try {
            System.arraycopy(staged.lz4Payloads, 0, this.compressed, 0, SLOTS);
            System.arraycopy(staged.uncompressedSize, 0, this.uncompressedSize, 0, SLOTS);
            System.arraycopy(staged.timestamps, 0, this.timestamps, 0, SLOTS);
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
                if (this.parentDir != null) {
                    LinearFlushCoordinator.forFolder(this.parentDir).recordLoad(micros);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void markDirty() {
        this.slotLock.lock();
        try {
            this.dirty = true;
        } finally {
            this.slotLock.unlock();
        }
    }

    /**
     * Self-reporting: every mutation path reports to the folder coordinator,
     * so no acknowledged write depends on the caller remembering to report.
     * Best-effort (bytes are already staged); idempotent coalescing in the
     * coordinator makes duplicate reports from leg-level call sites harmless.
     */
    private void reportDirty() {
        try {
            if (this.parentDir != null) {
                LinearFlushCoordinator.forFolder(this.parentDir).markDirty(this);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void deleteQuietly(Path tmp) {
        try {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException ignored) {
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
