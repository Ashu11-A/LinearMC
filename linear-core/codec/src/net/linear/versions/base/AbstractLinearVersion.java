package net.linear.versions.base;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jpountz.lz4.LZ4Factory;
import net.linear.probe.LinearBucketGrid;
import net.linear.RegionFileFormat;
import net.linear.versions.codecs.PumpkinLinear;
import net.linear.versions.decoders.V1Decoder;
import net.linear.versions.encoders.V1Encoder;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.Version;
import net.linear.versions.VersionWriteParams;

/**
 * Shared header/guard contract for all linear region codecs.
 *
 * <p>Owns the superblock constant, the shared LZ4 factory, the small
 * buffer guards and the default flush/parse hooks. Bucketed
 * parse/flush flow lives in {@link AbstractBucketedVersion}; V1 uses
 * {@code V1Decoder}/{@code V1Encoder} instead.</p>
 *
 * <p>Stateless and thread-safe: only static constants plus the shared LZ4
 * factory. Subclasses supply the wire byte, header shape and table shape.</p>
 */
public abstract class AbstractLinearVersion implements Version {

    protected static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;
    protected static final int FOOTER_SIZE = 8;

    protected static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();

    /** Wire version byte handled by this codec. */
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

    /** Consumes the V2 NBT-features table (terminator 0x00); shared by V2. */
    public static void skipFeatures(ByteBuffer raw) throws IOException {
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

    public static void checkGrid(int grid) throws IOException {
        if (!LinearBucketGrid.isValid(grid)) {
            throw new IOException("Invalid grid size: " + grid);
        }
    }

    public static void requireFooter(ByteBuffer raw) throws IOException {
        require(raw, FOOTER_SIZE, "footer");
        if (raw.getLong() != SUPERBLOCK) {
            throw new IOException("Invalid footer superblock");
        }
        if (raw.hasRemaining()) {
            throw new IOException("Trailing bytes after footer");
        }
    }

    protected static void require(ByteBuffer buf, int bytes, String what) throws IOException {
        require(buf, (long) bytes, what);
    }

    protected static void require(ByteBuffer buf, long bytes, String what) throws IOException {
        if (bytes < 0 || buf.remaining() < bytes) {
            throw new IOException("Truncated " + what);
        }
    }
}
