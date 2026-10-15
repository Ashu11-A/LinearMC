package net.linear;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.linear.buffered.BufferedMaster;
import net.linear.buffered.BufferedStagingSweep;
import net.linear.buffered.BufferedSwapFile;
import net.linear.io.LinearDirectStreams;

/**
 * Buffered-linear region file ({@code .b_linear} + {@code .b_linear.swp}).
 *
 * <p>Wire-compatible with DivineMC's {@code B_LINEAR} layout: a swap file of
 * per-chunk LZ4 sectors fronting a master file holding one zstd frame of all
 * 1024 inner envelopes. Swap layout (big-endian): 8 B magic, 1 B version, 4 B
 * xxhash seed, 8 B allocator high-water, then 1024 x 17 B sector entries
 * (8 B offset, 8 B length, 1 B live flag). Each sector payload is
 * {@code [int32 decompressedLen][LZ4 bytes]} decompressing to the inner
 * envelope {@code [int32 dataLen][int64 timestampMs][int32 xxhash32][payload]}.
 * Master layout: 8 B magic, 1 B version, 8 B newest timestamp, 1 B level,
 * then one zstd frame of {@code 1024 x (int32 len, [inner envelope])}.</p>
 *
 * <p>WAL-at-rest reliability model: the swap file is a write-ahead log
 * that exists while the region is open and/or dirty. A clean
 * {@link #close()} prunes the swap (plus any {@code .tmp} staging
 * siblings) so steady state on disk is the master alone; the next open
 * rebuilds swap state from the master. A crash loses at most one flush
 * interval instead of all un-synced data; open verifies every swap
 * sector and repairs torn slots from the master; a failed master replace
 * never deletes the previous master; per-write sync never blocks the caller
 * (durability lands at coordinator flush, like {@link LinearRegionFile}).</p>
 *
 * <p>Concurrency: one {@code slotLock} guards the sector table and all swap
 * channel IO; {@code flushGuard} serialises flushes (leaf order
 * {@code flushGuard} before {@code slotLock}, same as
 * {@link LinearRegionFile}). Swap payload IO is small (single chunks, one
 * sequential flush pass), so holding the lock across positioned channel IO
 * is bounded and documented. zstd/master IO and LZ4 codec work happen
 * unlocked wherever the bytes are already staged.</p>
 */
public final class BufferedLinearRegionFile implements AbstractRegionFile {

    private static final Logger LOGGER = LoggerFactory.getLogger(BufferedLinearRegionFile.class);

    /** Swap magic (DivineMC-compatible). */
    static final long SWAP_SUPERBLOCK = BufferedSwapFile.SWAP_SUPERBLOCK;
    static final byte SWAP_VERSION = BufferedSwapFile.SWAP_VERSION;
    static final int DEFAULT_SEED = BufferedSwapFile.DEFAULT_SEED;
    static final int SECTOR_ENTRY_SIZE = BufferedSwapFile.SECTOR_ENTRY_SIZE;
    static final int SECTOR_TABLE_SIZE = BufferedSwapFile.SECTOR_TABLE_SIZE;
    static final int SWAP_HEADER_SIZE = BufferedSwapFile.SWAP_HEADER_SIZE;
    /** Master magic (DivineMC-compatible). */
    public static final long MASTER_SUPERBLOCK = BufferedMaster.MASTER_SUPERBLOCK;
    public static final byte MASTER_VERSION = BufferedMaster.MASTER_VERSION;
    static final int MASTER_HEADER_SIZE = BufferedMaster.MASTER_HEADER_SIZE;
    /** Auto-compact when spare exceeds 1 MiB and 60% of live bytes. */
    static final long COMPACT_SPARE_BYTES = BufferedMaster.COMPACT_SPARE_BYTES;
    /** Cap guarding the single master-image allocation against corrupt lengths. */
    static final long MAX_MASTER_BYTES = BufferedMaster.MAX_MASTER_BYTES;

    private final Path file;
    private final Path swapPath;
    private final int compressionLevel;
    private final Path parentDir;

    private final ReentrantLock slotLock = new ReentrantLock();
    private final ReentrantLock flushGuard = new ReentrantLock();

    private final long[] sectorOffset = new long[1024];
    private final long[] sectorLength = new long[1024];
    private final boolean[] sectorLive = new boolean[1024];
    private long acquiredIndex = SWAP_HEADER_SIZE;
    private int hashSeed = DEFAULT_SEED;

    private FileChannel swapChannel;
    private volatile boolean dirty;
    private volatile boolean closed;
    /**
     * Set when close() starts draining: the swap will be pruned right after,
     * so doFlush() skips the compact pass (rewriting a swap that is about to
     * be deleted is pure waste). Sticky — close() is terminal. Volatile so a
     * concurrent coordinator flush racing close also skips harmlessly.
     */
    private volatile boolean closing;
    /**
     * Compact executions (for tests): incremented when maybeCompactLocked
     * actually rewrites. Lock-free; fresh instances start at zero.
     */
    private final LongAdder compactCount = new LongAdder();
    /**
     * Open found no master and no live swap sectors: the swap (if any) is an
     * empty husk. Set once in load(); lets close() delete the husk even
     * though no master exists (there is no data to lose).
     */
    private volatile boolean swapHusk;

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

    /**
     * Opens (or creates) the buffered region at {@code file}.
     *
     * @param file master path ({@code r.<x>.<z>.b_linear})
     * @param compressionLevel zstd level for the master frame; clamped to 1..22
     */
    public BufferedLinearRegionFile(final Path file, final int compressionLevel) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        this.compressionLevel = RegionFileFormat.clampCompressionLevel(compressionLevel);
        this.parentDir = file.toAbsolutePath().getParent();
        this.swapPath = this.file.resolveSibling(this.file.getFileName() + ".swp");
        createParentDirs(file);
        this.swapChannel = FileChannel.open(swapPath, StandardOpenOption.CREATE,
            StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.load();
    }

    /** Per-file timing view (same shape as {@link LinearRegionFile#linear$stats}). */
    public LinearRegionTimings.LinearRegionStats linear$stats() {
        return new LinearRegionTimings.LinearRegionStats(
            LinearRegionTimings.LinearTimings.of(this.readCount, this.readTotalMicros, this.readMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.writeCount, this.writeTotalMicros, this.writeMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.flushCount, this.flushTotalMicros, this.flushMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.loadCount, this.loadTotalMicros, this.loadMaxMicros));
    }

    @Override
    public DataInputStream getChunkDataInputStream(final long chunk) throws IOException {
        final long startNanos = System.nanoTime();
        try {
            ensureOpen();
            final int index = slotIndex(chunk);
            final byte[] outer;
            this.slotLock.lock();
            try {
                if (!this.sectorLive[index] || this.sectorLength[index] <= 0) {
                    return null;
                }
                if (this.sectorLength[index] > LinearRegionFile.MAX_CHUNK_SIZE + 1024L) {
                    throw new IOException("Invalid sector length " + this.sectorLength[index]
                        + " in slot " + index + " of " + this.file);
                }
                outer = new byte[(int) this.sectorLength[index]];
                readFully(this.swapChannel, this.sectorOffset[index], outer);
            } finally {
                this.slotLock.unlock();
            }
            byte[] payload = decodeOuter(outer, index);
            ByteBuffer out = ByteBuffer.wrap(payload);
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

    @Override
    public void write(final long chunk, final ByteBuffer buf) throws IOException {
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
                    this.sectorLive[index] = false;
                    this.dirty = true;
                } finally {
                    this.slotLock.unlock();
                }
                reportDirty();
                return;
            }
            if (size > LinearRegionFile.MAX_CHUNK_SIZE) {
                try {
                    if (this.parentDir != null) {
                        LinearFlushCoordinator.forFolder(this.parentDir).recordOversizeReject();
                    }
                } catch (RuntimeException ignored) {
                }
                throw new IOException("Chunk " + ChunkKey.format(chunk) + " is " + size + " bytes, exceeds "
                    + LinearRegionFile.MAX_CHUNK_SIZE + " in " + this.file
                    + "; oversized .mcc external files are unsupported");
            }
            byte[] plain = new byte[size];
            buf.duplicate().get(plain);
            byte[] outer = encodeOuter(plain);
            this.slotLock.lock();
            try {
                ensureOpen();
                storeLocked(index, outer);
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

    @Override
    public boolean hasChunk(final long chunk) {
        if (this.closed) {
            throw new IllegalStateException("BufferedLinearRegionFile is closed: " + this.file);
        }
        this.slotLock.lock();
        try {
            return this.sectorLive[slotIndex(chunk)];
        } finally {
            this.slotLock.unlock();
        }
    }

    @Override
    public void clear(final long chunk) throws IOException {
        this.write(chunk, ByteBuffer.allocate(0));
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        this.doFlush();
    }

    @Override
    public boolean isMarkedToSave() {
        return this.dirty;
    }

    @Override
    public void clearMarkedToSave() {
        this.slotLock.lock();
        try {
            this.dirty = false;
        } finally {
            this.slotLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        this.slotLock.lock();
        try {
            if (this.closed) {
                if (!this.dirty) {
                    return;
                }
            } else {
                this.closed = true;
            }
            // Terminal drain: the swap is pruned right after, so the flush
            // below skips its compact pass (see doFlush).
            this.closing = true;
        } finally {
            this.slotLock.unlock();
        }
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
                break;
            }
        }
        this.slotLock.lock();
        boolean stillDirty;
        try {
            stillDirty = this.dirty;
        } finally {
            this.slotLock.unlock();
        }
        if (stillDirty) {
            // Channel stays open so a retrying close() can still drain.
            if (firstRuntimeFailure != null) {
                throw firstRuntimeFailure;
            }
            throw firstIoFailure != null ? firstIoFailure
                : new IOException("Unflushed writes remain on close of " + this.file);
        }
        // Clean drain: earlier failures (if any) were transient — the data
        // is safe, so only a fresh channel-close failure propagates. Prune
        // is attempted even when the channel close throws (POSIX deletes
        // open paths fine; a retrying close() would otherwise early-return
        // on clean state and strand the sidecar forever).
        IOException closeFailure = null;
        try {
            this.swapChannel.close();
        } catch (IOException e) {
            closeFailure = e;
        }
        // WAL-at-rest prune: when a master snapshot exists it holds the full
        // state, so the swap and any staging siblings are scratch. When NO
        // master exists the swap may hold the only copy (killed before the
        // first flush) — pruning it would destroy data, so keep it; the next
        // open re-marks dirty (see load()) and materializes the master.
        // Exception: an empty husk (no master, never any live data) is safe
        // to drop — see swapHusk in load().
        if (isMasterPresent() || this.swapHusk) {
            pruneSwapSidecar();
        }
        // Best-effort (Windows may hold locks briefly); a leftover is
        // re-swept on the next open, never read as truth ahead of the master
        // repair path.
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    /** True when a non-empty master snapshot exists beside the swap. */
    private boolean isMasterPresent() {
        try {
            return Files.isRegularFile(this.file) && Files.size(this.file) > 0L;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Deletes the swap sidecar plus master/compact staging tmps beside it.
     * Safe only when clean (no unflushed writes): callers must hold no
     * uncommitted state. Best-effort; failures are ignored so close/convert
     * never fail on a hygienic delete.
     */
    void linear$pruneSwapSidecar() {
        pruneSwapSidecar();
    }

    private void pruneSwapSidecar() { BufferedStagingSweep.pruneSwapSidecar(this.file, this.swapPath); }

    // internal

    private void doFlush() throws IOException {
        boolean didIo = false;
        boolean flushOk = false;
        Path tmp = null;
        this.flushGuard.lock();
        final long startNanos = System.nanoTime();
        try {
        try {
            final byte[][] envelopes = new byte[1024][];
            long newest = 0L;
            this.slotLock.lock();
            try {
                if (!this.dirty) {
                    return;
                }
                if (!this.closing) {
                    maybeCompactLocked();
                }
                long totalRaw = 0L;
                for (int i = 0; i < 1024; i++) {
                    if (this.sectorLive[i] && this.sectorLength[i] > 0) {
                        byte[] outer = new byte[(int) this.sectorLength[i]];
                        readFully(this.swapChannel, this.sectorOffset[i], outer);
                        byte[] envelope = decodeOuterToEnvelope(outer, i);
                        verifyInner(envelope, i);
                        totalRaw += envelope.length;
                        if (totalRaw > LinearRegionFile.MAX_IMAGE_BYTES) {
                            throw new IOException("Region image too large in " + this.file);
                        }
                        envelopes[i] = envelope;
                        long ts = envelopeTimestamp(envelope);
                        if (Long.compareUnsigned(ts, newest) > 0) {
                            newest = ts;
                        }
                    }
                }
                // Persist the sector table + allocator BEFORE the data force:
                // readers (and crash recovery) must see table and payloads
                // atomically with respect to the dirty flag below.
                writeSwapHeadersLocked(false);
                this.swapChannel.force(true);
                this.dirty = false;
            } finally {
                this.slotLock.unlock();
            }
            didIo = true;
            tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            createParentDirs(tmp);
            long packedBytes;
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer header = ByteBuffer.allocate(MASTER_HEADER_SIZE);
                header.putLong(MASTER_SUPERBLOCK);
                header.put(MASTER_VERSION);
                header.putLong(newest == 0L ? System.currentTimeMillis() : newest);
                header.put((byte) this.compressionLevel);
                header.flip();
                LinearDirectStreams.writeFully(channel, header);
                java.io.OutputStream fileOut = LinearDirectStreams.uncloseable(Channels.newOutputStream(channel));
                try (ZstdOutputStream zstd = new ZstdOutputStream(fileOut, this.compressionLevel)) {
                    zstd.setChecksum(true);
                    DataOutputStream out = new DataOutputStream(zstd);
                    for (int i = 0; i < 1024; i++) {
                        if (envelopes[i] != null) {
                            out.writeInt(envelopes[i].length);
                            out.write(envelopes[i]);
                        } else {
                            out.writeInt(0);
                        }
                    }
                    out.flush();
                }
                channel.force(true);
                packedBytes = channel.position() - MASTER_HEADER_SIZE;
                if (packedBytes < 0L || packedBytes > MAX_MASTER_BYTES) {
                    throw new IOException("Invalid master frame length " + packedBytes + " for " + this.file);
                }
            }
            try {
                Files.move(tmp, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException fallback) {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                if (this.parentDir != null) {
                    LinearFlushCoordinator.forFolder(this.parentDir).recordFlushBytes(0L, packedBytes);
                }
            } catch (RuntimeException ignored) {
            }
            flushOk = true;
        } catch (final IOException e) {
            this.markDirty();
            BufferedSwapFile.deleteQuietly(tmp);
            throw e;
        } catch (final RuntimeException e) {
            this.markDirty();
            BufferedSwapFile.deleteQuietly(tmp);
            throw e;
        } catch (final Error e) {
            this.markDirty();
            BufferedSwapFile.deleteQuietly(tmp);
            throw e;
        } finally {
            this.flushGuard.unlock();
        }
        } finally {
            if (didIo) {
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

    /** Stores an outer envelope, in place when it fits, else appended. Caller holds {@code slotLock}. */
    private void storeLocked(int index, byte[] outer) throws IOException {
        long needed = outer.length;
        if (needed > LinearRegionFile.MAX_CHUNK_SIZE + 1024L) {
            throw new IOException("Invalid sector length " + needed + " in slot " + index + " of " + this.file);
        }
        if (this.sectorLive[index] && needed <= this.sectorLength[index]) {
            writeFully(this.swapChannel, this.sectorOffset[index], outer);
            this.sectorLength[index] = needed;
        } else {
            long offset = this.acquiredIndex;
            if (offset < SWAP_HEADER_SIZE || offset > MAX_MASTER_BYTES) {
                throw new IOException("Swap allocator out of range in " + this.file);
            }
            writeFully(this.swapChannel, offset, outer);
            this.sectorOffset[index] = offset;
            this.sectorLength[index] = needed;
            this.acquiredIndex = offset + needed;
        }
        this.sectorLive[index] = true;
    }

    /**
     * Auto-compacts the swap when spare exceeds 1 MiB and 60% of live bytes:
     * rewrites live sectors sequentially from the header end into a tmp file
     * and moves it over the swap. Caller holds {@code slotLock}.
     */
    private void maybeCompactLocked() throws IOException {
        long liveBytes = 0L;
        for (int i = 0; i < 1024; i++) {
            if (this.sectorLive[i]) {
                liveBytes += this.sectorLength[i];
            }
        }
        long fileSize = this.swapChannel.size();
        long spare = fileSize - SWAP_HEADER_SIZE - liveBytes;
        if (spare <= COMPACT_SPARE_BYTES || spare * 5L <= liveBytes * 3L) {
            return;
        }
        this.compactCount.increment();
        Path compactTmp = this.swapPath.resolveSibling(this.swapPath.getFileName() + ".tmp");
        byte[][] live = new byte[1024][];        for (int i = 0; i < 1024; i++) {
            if (this.sectorLive[i] && this.sectorLength[i] > 0) {
                live[i] = new byte[(int) this.sectorLength[i]];
                readFully(this.swapChannel, this.sectorOffset[i], live[i]);
            }
        }
        long[] newOffset = new long[1024];
        long cursor = SWAP_HEADER_SIZE;
        try (FileChannel out = FileChannel.open(compactTmp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer header = encodeSwapHeader(cursor + totalLive(live));
            LinearDirectStreams.writeFully(out, header);
            for (int i = 0; i < 1024; i++) {
                if (live[i] != null) {
                    newOffset[i] = cursor;
                    LinearDirectStreams.writeFully(out, ByteBuffer.wrap(live[i]));
                    cursor += live[i].length;
                }
            }
            out.force(true);
        } catch (IOException | RuntimeException e) {
            BufferedSwapFile.deleteQuietly(compactTmp);
            throw e instanceof IOException io ? io : new IOException("Swap compact failed for " + this.file, e);
        } catch (Error e) {
            BufferedSwapFile.deleteQuietly(compactTmp);
            throw e;
        }
        // Move first while the old channel is still open: on move failure
        // the table and channel are untouched, so the flush fails cleanly
        // with nothing stranded. (POSIX allows replacing the open path;
        // the old channel keeps serving the replaced inode until closed.)
        try {
            Files.move(compactTmp, this.swapPath, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException fallback) {
            Files.move(compactTmp, this.swapPath, StandardCopyOption.REPLACE_EXISTING);
        }
        FileChannel fresh = FileChannel.open(this.swapPath, StandardOpenOption.CREATE,
            StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileChannel retired = this.swapChannel;
        this.swapChannel = fresh;
        try {
            retired.close();
        } catch (IOException ignored) {
            // The old inode is fully replaced on disk; a close error on the
            // discarded channel loses nothing (payloads forced before move).
        }
        for (int i = 0; i < 1024; i++) {
            if (live[i] != null) {
                this.sectorOffset[i] = newOffset[i];
            } else {
                this.sectorOffset[i] = 0L;
                this.sectorLength[i] = 0L;
            }
        }
        this.acquiredIndex = cursor;
        writeSwapHeadersLocked(true);
    }

    /** Compact executions (test-visible; see {@code closing}). */
    long linear$compactCount() {
        return this.compactCount.sum();
    }

    private static long totalLive(byte[][] live) {
        long total = 0L;
        for (byte[] b : live) {
            if (b != null) {
                total += b.length;
            }
        }
        return total;
    }

    private void load() throws IOException {
        final long startNanos = System.nanoTime();
        try {
            // Crash orphans from a killed flush/compact (master .tmp forced
            // but unmoved, compact staging): the master is old truth and the
            // swap table decides, so neither tmp may survive to confuse a
            // later open or inflate disk.
            BufferedStagingSweep.sweepTmpSiblings(this.file);
            long swapSize = Files.isRegularFile(this.swapPath) ? this.swapChannel.size() : 0L;
            byte[][] masterEnvelopes = null;
            boolean swapUsable = swapSize >= SWAP_HEADER_SIZE;
            if (swapUsable) {
                try {
                    readSwapHeaders();
                } catch (IOException badHeader) {
                    // Bad magic/version (bit-rot or foreign file): the table
                    // is untrustworthy, so no sector is verifiable — rebuild
                    // from the master exactly like the truncated case.
                    LOGGER.warn("Ignoring unreadable swap file: {} ({})", this.swapPath,
                        String.valueOf(badHeader.getMessage()));
                    swapUsable = false;
                }
            }
            if (!swapUsable) {
                if (swapSize > 0L) {
                    // Torn header (crash mid-init): the sector table is
                    // unreadable, so no sector is verifiable — start fresh
                    // and let the master repair below restore coverage.
                    LOGGER.warn("Ignoring truncated swap file: {} ({} bytes)", this.swapPath, swapSize);
                }
                this.swapChannel.close();
                Files.deleteIfExists(this.swapPath);
                this.swapChannel = FileChannel.open(swapPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
                writeSwapHeadersLocked(false);
            }
            boolean masterExisted = Files.isRegularFile(this.file) && Files.size(this.file) > 0L;
            boolean masterCorrupt = false;
            if (masterExisted) {
                try {
                    masterEnvelopes = parseMaster();
                } catch (IOException badMaster) {
                    // A corrupt master must not brick verified swap data:
                    // serve the swap (verified per-sector below) and heal
                    // the master on the next flush.
                    LOGGER.warn("Ignoring unreadable master file: {} ({})", this.file,
                        String.valueOf(badMaster.getMessage()));
                    masterEnvelopes = null;
                    masterCorrupt = true;
                }
            }
            // Repair-on-open: verify every live swap sector; torn slots fall
            // back to the master envelope, and master-only slots are absorbed
            // into the swap. Afterwards the swap is fully verified.
            boolean repaired = false;
            for (int i = 0; i < 1024; i++) {
                byte[] master = masterEnvelopes == null ? null : masterEnvelopes[i];
                if (this.sectorLive[i] && this.sectorLength[i] > 0) {
                    if (!verifySector(i)) {
                        if (master != null) {
                            storeVerifiedEnvelope(i, master);
                            repaired = true;
                        } else {
                            this.sectorLive[i] = false;
                            this.sectorLength[i] = 0L;
                            repaired = true;
                            LOGGER.warn("Dropping unverifiable sector {} of {} (no master copy)", i, this.file);
                        }
                    }
                } else if (master != null) {
                    storeVerifiedEnvelope(i, master);
                    repaired = true;
                }
            }
            if (repaired || !swapUsable) {
                writeSwapHeadersLocked(true);
            }
            if (!masterExisted && !hasLiveSectors()) {
                // Empty husk (no master, no live data — e.g. a region whose
                // chunks were all cleared before the first flush, or a stale
                // header): drop it so no 17 KB husk lingers. Reopen fresh so
                // later writes have a channel; swapHusk lets close() remove
                // it again when nothing was ever written. Reset the allocator
                // too: stale offsets would punch a sparse hole on next append.
                this.swapChannel.close();
                Files.deleteIfExists(this.swapPath);
                this.swapChannel = FileChannel.open(swapPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
                java.util.Arrays.fill(this.sectorOffset, 0L);
                java.util.Arrays.fill(this.sectorLength, 0L);
                java.util.Arrays.fill(this.sectorLive, false);
                this.acquiredIndex = SWAP_HEADER_SIZE;
                writeSwapHeadersLocked(false);
                this.swapHusk = true;
            }
            if (masterCorrupt) {
                int liveCount = 0;
                for (int i = 0; i < 1024; i++) {
                    if (this.sectorLive[i] && this.sectorLength[i] > 0) {
                        liveCount++;
                    }
                }
                if (liveCount == 0) {
                    // Fail-closed: a corrupt master with no verifiable swap
                    // sector would open as an empty region and let the game
                    // regenerate (destroy) the world. Refuse instead.
                    throw new IOException("Master unreadable and no verifiable swap sectors in " + this.file);
                }
                LOGGER.warn("Opened {} with {} swap sector(s) despite unreadable master;"
                    + " master heals on next flush", this.file, liveCount);
            }
            this.slotLock.lock();
            boolean swapOnly = false;
            try {
                // Swap-only state (killed before the first master flush, or
                // master unreadable with live swap sectors): the swap holds
                // the only verifiable copy, so force a master
                // materialization on the next flush instead of reporting
                // clean. Without this, a clean close would prune the sole
                // copy (see close()).
                swapOnly = hasLiveSectors() && (!masterExisted || masterCorrupt);
                this.dirty = swapOnly;
            } finally {
                this.slotLock.unlock();
            }
            if (swapOnly) {
                reportDirty();
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

    /** Stores a master envelope into the swap (already verified by construction). Caller: open only. */
    private void storeVerifiedEnvelope(int index, byte[] inner) throws IOException {
        byte[] outer = lz4Outer(inner);
        this.sectorOffset[index] = this.acquiredIndex;
        this.sectorLength[index] = outer.length;
        this.acquiredIndex += outer.length;
        writeFully(this.swapChannel, this.sectorOffset[index], outer);
        this.sectorLive[index] = true;
    }

    /** True when any sector is live. Caller: open only (table just repaired). */
    private boolean hasLiveSectors() {
        for (int i = 0; i < 1024; i++) {
            if (this.sectorLive[i] && this.sectorLength[i] > 0) {
                return true;
            }
        }
        return false;
    }

    /** True iff the live sector's envelope reads and its hash verifies. */
    private boolean verifySector(int index) { return BufferedSwapFile.verifySector(this.swapChannel, this.sectorOffset, this.sectorLength, index, this.file); }

    private void readSwapHeaders() throws IOException {
        int[] seed = new int[1];
        long acquired = BufferedSwapFile.readSwapHeaders(this.swapChannel, this.swapPath, this.sectorOffset,
            this.sectorLength, this.sectorLive, seed);
        this.hashSeed = seed[0];
        this.acquiredIndex = acquired;
    }

    private ByteBuffer encodeSwapHeader(long acquired) { return BufferedSwapFile.encodeSwapHeader(acquired, this.hashSeed, this.sectorOffset, this.sectorLength, this.sectorLive); }

    private void writeSwapHeadersLocked(boolean force) throws IOException {
        BufferedSwapFile.writeSwapHeadersLocked(this.swapChannel, this.acquiredIndex, this.hashSeed,
            this.sectorOffset, this.sectorLength, this.sectorLive, force);
    }

    /**
     * Parses the master frame into per-slot inner envelopes ({@code null} when
     * absent). Accepts the buffered master only; classic-linear adoption goes
     * through the converter, not the open path.
     */
    private byte[][] parseMaster() throws IOException {
        long size = Files.size(this.file);
        if (size < MASTER_HEADER_SIZE + 8L) {
            throw new IOException("Truncated master file: " + this.file + " (" + size + " bytes)");
        }
        if (size > MAX_MASTER_BYTES) {
            throw new IOException("Master file too large: " + this.file + " (" + size + " bytes)");
        }
        byte[][] envelopes = new byte[1024][];
        try (FileChannel channel = FileChannel.open(this.file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(MASTER_HEADER_SIZE);
            readFully(channel, 0L, header);
            header.flip();
            if (header.getLong() != MASTER_SUPERBLOCK) {
                throw new IOException("Invalid master superblock in " + this.file);
            }
            if ((header.get() & 0xFF) != (MASTER_VERSION & 0xFF)) {
                throw new IOException("Unsupported master version in " + this.file);
            }
            header.getLong();
            header.get();
            long bodySize = size - MASTER_HEADER_SIZE;
            ByteBuffer body = ByteBuffer.allocateDirect((int) bodySize);
            net.linear.io.LinearDirect.recordAlloc(bodySize);
            readFully(channel, MASTER_HEADER_SIZE, body);
            body.flip();
            try {
            final ZstdInputStream zstdIn;
            try {
                zstdIn = new ZstdInputStream(LinearDirectStreams.wrap(body));
                zstdIn.setLongMax(27);
            } catch (RuntimeException e) {
                throw new IOException("Failed to open master frame of " + this.file + ": " + e.getMessage(), e);
            }
            try (DataInputStream in = new DataInputStream(zstdIn)) {
                for (int i = 0; i < 1024; i++) {
                    int len = in.readInt();
                    if (len < 0 || len > LinearRegionFile.MAX_CHUNK_SIZE + 16) {
                        throw new IOException("Invalid master entry " + len + " in slot " + i + " of " + this.file);
                    }
                    if (len > 0) {
                        byte[] envelope = in.readNBytes(len);
                        if (envelope.length != len) {
                            throw new IOException("Truncated master entry in slot " + i + " of " + this.file);
                        }
                        verifyInner(envelope, i);
                        envelopes[i] = envelope;
                    }
                }
                if (in.read() != -1) {
                    throw new IOException("Trailing bytes in master of " + this.file);
                }
            }
            } finally {
                // Envelopes are heap copies; the compressed body is garbage
                // past this point and is freed deterministically.
                net.linear.io.LinearDirect.free(body);
            }
        }
        return envelopes;
    }

    // envelope helpers (owned by BufferedMaster; one-line delegates)

    /** Builds the inner envelope {@code [len, timestampMs, xxhash32, payload]}. */
    private byte[] encodeInner(byte[] payload, long timestampMs) { return BufferedMaster.encodeInner(payload, timestampMs, this.hashSeed); }

    /** Verifies an inner envelope and returns its payload. */
    private byte[] decodeInner(byte[] envelope, int slot) throws IOException { return BufferedMaster.decodeInner(envelope, slot, this.file, this.hashSeed); }

    private void verifyInner(byte[] envelope, int slot) throws IOException { BufferedMaster.verifyInner(envelope, slot, this.file, this.hashSeed); }

    private static long envelopeTimestamp(byte[] envelope) { return BufferedMaster.envelopeTimestamp(envelope); }

    /** Outer layer: {@code [int32 decompressedLen][LZ4 bytes]} of a fresh inner envelope. */
    private byte[] encodeOuter(byte[] payload) throws IOException { return BufferedMaster.encodeOuter(payload, this.file, this.hashSeed); }

    /** Decodes an outer envelope all the way to the raw payload. */
    private byte[] decodeOuter(byte[] outer, int slot) throws IOException { return BufferedMaster.decodeOuter(outer, slot, this.file, this.hashSeed); }

    private byte[] decodeOuterToEnvelope(byte[] outer, int slot) throws IOException { return BufferedMaster.decodeOuterToEnvelope(outer, slot, this.file); }

    private byte[] lz4Outer(byte[] inner) { return BufferedMaster.lz4Outer(inner); }

    // small io

    private static void readFully(FileChannel channel, long position, byte[] dst) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(dst);
        int total = 0;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, position + total);
            if (n < 0) {
                throw new IOException("Truncated read at offset " + position);
            }
            total += n;
        }
    }

    private static void readFully(FileChannel channel, long position, ByteBuffer dst) throws IOException {
        int start = dst.position();
        while (dst.hasRemaining()) {
            int n = channel.read(dst, position + (dst.position() - start));
            if (n < 0) {
                throw new IOException("Truncated read at offset " + position);
            }
        }
    }

    private static void writeFully(FileChannel channel, long position, byte[] src) throws IOException {
        writeFully(channel, position, ByteBuffer.wrap(src));
    }

    private static void writeFully(FileChannel channel, long position, ByteBuffer src) throws IOException {
        int start = src.position();
        while (src.hasRemaining()) {
            int n = channel.write(src, position + (src.position() - start));
            if (n < 0) {
                throw new IOException("Truncated write at offset " + position);
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

    private void ensureOpen() throws IOException {
        if (this.closed) {
            throw new IOException("BufferedLinearRegionFile is closed: " + this.file);
        }
    }

    private static int slotIndex(final long chunk) {
        return ChunkKey.slotIndex(chunk);
    }

    private static void createParentDirs(final Path path) throws IOException {
        final Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
