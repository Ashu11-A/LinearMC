package net.linear.versions;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jpountz.lz4.LZ4Factory;
import net.linear.LinearBucketGrid;
import net.linear.LinearRegionFile;
import net.linear.LinearXxHash64;
import net.linear.RegionFileFormat;

/**
 * Abstract shared bucketed layout (DivineMC V2/V3, wires 3/4) behind one-file-per-version
 * subclasses ({@code V2Linear}, {@code V3Linear}).
 *
 * <p>Owns all shared bucketed logic moved verbatim from
 * {@code LinearBucketReader}/{@code LinearBucketWriter}: header
 * bitmap/features parse+write hooks, 13B/12B table read/write via abstract
 * methods, bucket inflate/deflate with len+8 slot semantics, absent-slot 12B
 * consumption, XXH64-seed0 verify, MAX_IMAGE_BYTES guard.</p>
 *
 * <p>Stateless and thread-safe: only static constants plus the shared LZ4
 * factory. Subclasses supply the wire byte, header shape and table shape.</p>
 */
public abstract class V2LinearBase implements Version {

    static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;
    private static final int FOOTER_SIZE = 8;

    private static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();

    /** Wire version byte handled by this codec (3 for V2, 4 for V3). */
    protected abstract int wireVersion();

    /** Per-bucket table entry size in bytes (13 for V2, 12 for V3). */
    protected abstract int tableEntrySize();

    /** Whether table entries carry the per-bucket compression level byte (V2 only). */
    protected abstract boolean hasLevelByte();

    /**
     * Reads the version-specific header from {@code raw} (positioned just
     * after the version byte) and returns the validated grid size.
     */
    protected abstract int readHeader(ByteBuffer raw) throws IOException;

    /**
     * Renders the version-specific header (V2: file timestamp, grid, region
     * coords, MSB-first bitmap, feature terminator; V3: grid only).
     */
    protected abstract ByteBuffer writeHeader(ChunkSlotView slots, VersionWriteParams params);

    /**
     * Stored-length bias between the on-disk slot length and the raw payload
     * length. DivineMC stores {@code len+8}; Phase 2 PumpkinLinear reuses this
     * base with 0.
     */
    protected int slotLengthBias() {
        return 8;
    }

    /**
     * Bucket index for bucket coordinates {@code (bx, bz)}.
     * DivineMC-identical default {@code bx*grid+bz} where {@code bx} is the
     * x-bucket and {@code bz} the z-bucket. Pumpkin overrides to
     * {@code bz*grid+bx} (upstream transposes x/z in bucket numbering).
     */
    protected int bucketIndex(int bx, int bz, int grid) {
        return bx * grid + bz;
    }

    /**
     * Whether empty buckets are still compressed+stored with a real xxhash
     * (Pumpkin dense, upstream-faithful) instead of omitted as size-0/hash-0
     * (DivineMC sparse). V2/V3 return false.
     */
    protected boolean writeEmptyBucketsDense() {
        return false;
    }

    /**
     * Whether bucket interiors iterate z-outer/x-inner (Pumpkin upstream:
     * {@code local = zOff*stride + xOff}) instead of DivineMC
     * x-outer/z-inner ({@code ordinal = xOff*cell + zOff}).
     */
    protected boolean transposeSlotOrder() {
        return false;
    }

    /**
     * Validated bucket grid for {@code flush}. DivineMC rejects off-whitelist
     * grids; Phase 2 PumpkinLinear overrides to default 2 instead.
     */
    protected int resolveGridSize(VersionWriteParams params) throws IOException {
        int grid = params.gridSize();
        if (!LinearBucketGrid.isValid(grid)) {
            throw new IOException("Invalid grid size: " + grid);
        }
        return grid;
    }

    /**
     * zstd level for the per-bucket frames. DivineMC clamps the configured
     * level; Phase 2 PumpkinLinear overrides to a fixed 1 (upstream-faithful).
     */
    protected int resolveCompressionLevel(VersionWriteParams params) {
        return RegionFileFormat.clampCompressionLevel(params.compressionLevel());
    }

    /**
     * Level byte stored in each 13 B table entry. DivineMC stores the
     * configured level; Phase 2 PumpkinLinear overrides to always 1
     * (ignored on read by both).
     */
    protected int tableLevelByte(int level) {
        return level;
    }

    /**
     * Converts a wire timestamp to the int32 slot table. DivineMC truncates;
     * Phase 2 PumpkinLinear overrides to clamp u64 values past
     * {@code Integer.MAX_VALUE} with one warn per file ({@code clampWarned}
     * is fresh per {@code parse} call; {@code out.sourceName} names the file).
     */
    protected int coerceTimestamp(long wireTimestamp, ChunkSlotTable out, AtomicBoolean clampWarned) {
        return (int) wireTimestamp;
    }

    @Override
    public final void parse(ByteBuffer fileImage, ChunkSlotTable out) throws IOException {
        ByteBuffer in = fileImage.duplicate();
        if (in.remaining() < 8 + 1) {
            throw new IOException("Truncated header");
        }
        if (in.getLong() != SUPERBLOCK) {
            throw new IOException("Invalid superblock");
        }
        int wire = in.get() & 0xFF;
        if (wire != wireVersion()) {
            throw new IOException("Unsupported bucketed version " + wire);
        }
        parseAfterVersion(in, out);
    }

    /**
     * Compat seam for {@code LinearBucketReader.inflate}: inflates buckets
     * from {@code raw} (positioned just after the version byte) into the V1
     * slot tables. Production {@code LinearRegionFile.load} uses
     * {@link #parse} on the whole image instead.
     */
    public void parseAfterVersion(ByteBuffer raw, ChunkSlotTable out) throws IOException {
        int grid = readHeader(raw);
        readBuckets(raw, grid, out, new AtomicBoolean(false));
        requireFooter(raw);
    }

    @Override
    public final ByteBuffer flush(ChunkSlotView slots, VersionWriteParams params) throws IOException {
        int[] snapSize = slots.uncompressedSize();
        int[] snapTime = slots.timestamps();
        ByteBuffer[] snapPacked = slots.lz4Payloads();
        int grid = resolveGridSize(params);
        int level = resolveCompressionLevel(params);
        int cell = LinearBucketGrid.cellCount(grid);
        int bucketCount = LinearBucketGrid.bucketCount(grid);

        // Decompress slots to heap once (bucket interiors are heap-built).
        byte[][] plain = new byte[ChunkSlotTable.SLOTS][];
        long totalRaw = 0L;
        for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
            if (snapSize[i] > 0) {
                if (snapSize[i] > LinearRegionFile.MAX_CHUNK_SIZE) {
                    throw new IOException("Invalid chunk size " + snapSize[i] + " in slot " + i);
                }
                totalRaw += snapSize[i];
                if (totalRaw > LinearRegionFile.MAX_IMAGE_BYTES) {
                    throw new IOException("Region image too large");
                }
                ByteBuffer frame = snapPacked[i].duplicate();
                byte[] frameBytes = new byte[frame.remaining()];
                frame.get(frameBytes);
                byte[] out = new byte[snapSize[i]];
                int consumed;
                try {
                    consumed = LZ4.fastDecompressor().decompress(frameBytes, 0, out, 0, snapSize[i]);
                } catch (RuntimeException e) {
                    throw new IOException("LZ4 frame mismatch in slot " + i + ": " + e.getMessage(), e);
                }
                if (consumed != frameBytes.length) {
                    throw new IOException("LZ4 frame mismatch in slot " + i);
                }
                plain[i] = out;
            }
        }

        // One zstd frame per bucket.
        // Phase1: preserved — the bucketed writer never honored workers/LDM
        // (serial checksum frames), unlike the V1 path.
        byte[][] buckets = new byte[bucketCount][];
        boolean transpose = transposeSlotOrder();
        boolean dense = writeEmptyBucketsDense();
        for (int bx = 0; bx < grid; bx++) {
            for (int bz = 0; bz < grid; bz++) {
                int bucketIdx = bucketIndex(bx, bz, grid);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                boolean hasData = false;
                try (ZstdOutputStream zstd = new ZstdOutputStream(baos, level)) {
                    zstd.setChecksum(true);
                    DataOutputStream out = new DataOutputStream(zstd);
                    for (int a = 0; a < cell; a++) {
                        for (int b = 0; b < cell; b++) {
                            int cx = transpose ? b : a;
                            int cz = transpose ? a : b;
                            int slot = LinearBucketGrid.slotIndex(bx, bz, cx, cz, cell);
                            if (plain[slot] != null) {
                                hasData = true;
                                out.writeInt(plain[slot].length + slotLengthBias());
                                out.writeLong(snapTime[slot] & 0xFFFFFFFFL);
                                out.write(plain[slot]);
                            } else {
                                out.writeInt(0);
                                out.writeLong(snapTime[slot] & 0xFFFFFFFFL);
                            }
                        }
                    }
                    out.flush();
                }
                if (hasData || dense) {
                    buckets[bucketIdx] = baos.toByteArray();
                }
            }
        }

        long[] hashes = new long[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            hashes[i] = buckets[i] != null
                ? LinearXxHash64.hashBuffer(ByteBuffer.wrap(buckets[i])) : 0L;
        }

        ByteBuffer header = writeHeader(slots, params);
        ByteBuffer table = ByteBuffer.allocate(bucketCount * tableEntrySize());
        for (int i = 0; i < bucketCount; i++) {
            table.putInt(buckets[i] != null ? buckets[i].length : 0);
            if (hasLevelByte()) {
                table.put((byte) tableLevelByte(level));
            }
            table.putLong(hashes[i]);
        }
        table.flip();

        int bodyBytes = 0;
        for (int i = 0; i < bucketCount; i++) {
            if (buckets[i] != null) {
                bodyBytes += buckets[i].length;
            }
        }
        ByteBuffer image = ByteBuffer.allocate(
            header.remaining() + table.remaining() + bodyBytes + FOOTER_SIZE);
        image.put(header);
        image.put(table);
        for (int i = 0; i < bucketCount; i++) {
            if (buckets[i] != null) {
                image.put(buckets[i]);
            }
        }
        image.putLong(SUPERBLOCK);
        image.flip();
        return image;
    }

    private void readBuckets(ByteBuffer raw, int grid, ChunkSlotTable out, AtomicBoolean clampWarned)
            throws IOException {
        int bucketCount = grid * grid;
        int tableBytes = bucketCount * tableEntrySize();
        require(raw, tableBytes, "bucket table");
        int[] sizes = new int[bucketCount];
        long[] hashes = new long[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            sizes[i] = raw.getInt();
            if (hasLevelByte()) {
                raw.get(); // per-bucket compression level (written, never negotiated)
            }
            hashes[i] = raw.getLong();
            // Bucket cap is independent of the per-chunk cap: a grid=1 bucket
            // holds all 1024 chunks and may legitimately exceed MAX_CHUNK_SIZE.
            // Per-chunk sizes are still enforced in inflateBucket.
            if (sizes[i] < 0 || (long) sizes[i] > (long) Integer.MAX_VALUE - 8L) {
                throw new IOException("Invalid bucket size " + sizes[i] + " in bucket " + i);
            }
            if (sizes[i] == 0 && hashes[i] != 0L) {
                throw new IOException("Region file hash incorrect in bucket " + i);
            }
        }
        long totalBuckets = 0L;
        for (int size : sizes) {
            totalBuckets += size;
            if (totalBuckets > (long) Integer.MAX_VALUE - 8L) {
                throw new IOException("Bucket payload too large");
            }
        }
        require(raw, totalBuckets + 8L, "buckets+footer");
        byte[][] buckets = new byte[bucketCount][];
        for (int i = 0; i < bucketCount; i++) {
            if (sizes[i] > 0) {
                ByteBuffer slice = raw.duplicate();
                slice.limit(slice.position() + sizes[i]);
                long actual = LinearXxHash64.hashBuffer(slice);
                if (actual != hashes[i]) {
                    throw new IOException("Region file hash incorrect in bucket " + i);
                }
                byte[] data = new byte[sizes[i]];
                raw.get(data);
                buckets[i] = data;
            } else {
                // sizes[i]==0 with hash 0 already validated above; no body bytes.
            }
        }
        int cellCount = 32 / grid;
        // Mirror the V1 totalRaw guard: cap summed decompressed payloads so a
        // small crafted file cannot OOM via zstd expansion (aborts past
        // MAX_IMAGE_BYTES, the same bound every writer enforces).
        long totalRaw = 0L;
        for (int bx = 0; bx < grid; bx++) {
            for (int bz = 0; bz < grid; bz++) {
                int bucketIdx = bucketIndex(bx, bz, grid);
                byte[] bucket = buckets[bucketIdx];
                if (bucket == null) {
                    continue;
                }
                totalRaw = inflateBucket(bucket, bucketIdx, bx, bz, cellCount, grid,
                    out, totalRaw, clampWarned);
                if (totalRaw > LinearRegionFile.MAX_IMAGE_BYTES) {
                    throw new IOException("Region image too large");
                }
            }
        }
    }

    private long inflateBucket(byte[] bucket, int bucketIdx,
            int bx, int bz, int cellCount, int grid,
            ChunkSlotTable out, long totalRaw, AtomicBoolean clampWarned) throws IOException {
        try {
            ZstdInputStream zstdIn = new ZstdInputStream(
                new ByteArrayInputStream(bucket));
            zstdIn.setLongMax(27);
            try (DataInputStream in = new DataInputStream(zstdIn)) {
            boolean transpose = transposeSlotOrder();
            for (int a = 0; a < cellCount; a++) {
                for (int b = 0; b < cellCount; b++) {
                    int cx = transpose ? b : a;
                    int cz = transpose ? a : b;
                    int chunkIndex = (bx * cellCount + cx) + (bz * cellCount + cz) * 32;
                    int dataSize;
                    long timestamp;
                    try {
                        dataSize = in.readInt();
                        timestamp = in.readLong();
                    } catch (java.io.EOFException e) {
                        throw new IOException("Truncated bucket " + bucketIdx);
                    }
                    out.timestamps[chunkIndex] = coerceTimestamp(timestamp, out, clampWarned);
                    if (dataSize == 0) {
                        continue;
                    }
                    int chunkSize = dataSize - slotLengthBias();
                    if (chunkSize <= 0 || chunkSize > LinearRegionFile.MAX_CHUNK_SIZE) {
                        throw new IOException("Invalid chunk size " + chunkSize
                            + " in bucket " + bucketIdx);
                    }
                    totalRaw += chunkSize;
                    if (totalRaw > LinearRegionFile.MAX_IMAGE_BYTES) {
                        throw new IOException("Region image too large");
                    }
                    byte[] payload = new byte[chunkSize];
                    try {
                        in.readFully(payload);
                    } catch (java.io.EOFException e) {
                        throw new IOException("Truncated payload in bucket " + bucketIdx);
                    }
                    out.lz4Payloads[chunkIndex] = lz4Pack(ByteBuffer.wrap(payload));
                    out.uncompressedSize[chunkIndex] = chunkSize;
                }
            }
            if (in.read() != -1) {
                throw new IOException("Trailing bytes in bucket " + bucketIdx);
            }
            }
            return totalRaw;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Failed to inflate bucket " + bucketIdx + ": " + e.getMessage(), e);
        }
    }

    /** Consumes the V2 NBT-features table (terminator 0x00); shared by V2. */
    static void skipFeatures(ByteBuffer raw) throws IOException {
        // Bound pathological tables: features are a DivineMC extension point,
        // real files carry only the terminator. Cap at 64 entries / 4 KiB.
        int entries = 0;
        int consumed = 0;
        while (true) {
            require(raw, 1, "feature table");
            int len = raw.get() & 0xFF;
            if (len == 0) {
                return;
            }
            require(raw, len + 4, "feature entry");
            raw.position(raw.position() + len + 4);
            consumed += len + 4;
            if (++entries > 64 || consumed > 4096) {
                throw new IOException("Oversized feature table");
            }
            if (raw.position() > raw.limit()) {
                throw new IOException("Truncated feature table");
            }
        }
    }

    static void checkGrid(int grid) throws IOException {
        if (!LinearBucketGrid.isValid(grid)) {
            throw new IOException("Invalid grid size: " + grid);
        }
    }

    static void requireFooter(ByteBuffer raw) throws IOException {
        require(raw, FOOTER_SIZE, "footer");
        if (raw.getLong() != SUPERBLOCK) {
            throw new IOException("Invalid footer superblock");
        }
        if (raw.hasRemaining()) {
            throw new IOException("Trailing bytes after footer");
        }
    }

    private static void require(ByteBuffer buf, int bytes, String what) throws IOException {
        require(buf, (long) bytes, what);
    }

    private static void require(ByteBuffer buf, long bytes, String what) throws IOException {
        if (bytes < 0 || buf.remaining() < bytes) {
            throw new IOException("Truncated " + what);
        }
    }

    private static ByteBuffer lz4Pack(ByteBuffer plain) {
        int size = plain.remaining();
        int maxCompressed = LZ4.fastCompressor().maxCompressedLength(size);
        ByteBuffer packed = ByteBuffer.allocateDirect(maxCompressed);
        int packedSize = LZ4.fastCompressor().compress(plain, plain.position(), size, packed, 0, maxCompressed);
        packed.position(0);
        packed.limit(packedSize);
        return packed.slice().asReadOnlyBuffer();
    }
}
