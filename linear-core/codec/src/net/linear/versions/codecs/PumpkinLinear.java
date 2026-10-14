package net.linear.versions.codecs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.linear.LinearImplementation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.linear.bucket.LinearBase;
import net.linear.versions.base.AbstractBucketedVersion;
import net.linear.versions.base.V2LinearBase;
import net.linear.versions.decoders.PumpkinDecoder;
import net.linear.versions.encoders.PumpkinEncoder;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.RegionFormatDetector;
import net.linear.versions.VersionWriteParams;

/**
 * Pumpkin codec (wire 2, bucketed {@code .linear} layout from the Pumpkin Rust server).
 *
 * <p>Layout: {@code 8 magic + 1 ver(2) + 8 newest-timestamp + 1 grid + 4 regionX + 4 regionZ}
 * (26 B superblock), 128 B existence bitmap, {@code [u8 len][key][u32 val] 0x00}-terminated
 * features, {@code grid x grid} 13 B table entries
 * ({@code u32 size + i8 level + u64 xxhash64-seed-0} over the <b>compressed</b> bucket bytes),
 * one zstd frame per bucket, 8 B footer. Bucket slots are
 * {@code u32 rawSize + u64 timestamp + size NBT bytes} (absent: {@code u32 0 + u64 ts}) —
 * the stored length is the raw NBT length, <b>not</b> DivineMC's {@code len+8}.</p>
 *
 * <p>Thin version delegate: read hooks forward to {@link PumpkinDecoder} and
 * write hooks to {@link PumpkinEncoder}; the bucketed parse/flush flow lives
 * in {@code AbstractBucketedVersion}. Differences from V2, implemented via
 * those hooks:</p>
 * <ul>
 *   <li>Bitmap is LSB-first ({@code bit = index % 8}); V2 is MSB-first.</li>
 *   <li>{@code slotLengthBias()} is 0 (raw length on the wire).</li>
 *   <li>The table level byte is always written as 1 and ignored on read.</li>
 *   <li>Wire timestamps are u64, clamped to int32 on inflate (values past
 *       {@code Integer.MAX_VALUE} clamp to it with one warn per file).</li>
 *   <li>Compression is a fixed zstd level 1 (upstream uses {@code ruzstd Fastest}).
 *       Byte-identity with {@code ruzstd} output is explicitly NOT guaranteed;
 *       acceptance is that the real Pumpkin server reads our files (see NFR5).
 *       Bucket hashes are computed over whatever bytes we emit, so our own
 *       reader always verifies.</li>
 *   <li>Grid defaults to 2 when unset or off-whitelist.</li>
 *   <li>{@code bucketIndex()} is transposed ({@code bz*grid+bx}) and bucket
 *       interiors iterate z-outer/x-inner, matching upstream
 *       {@code global_chunk_index}; V2/V3 stay DivineMC-identical.</li>
 *   <li>Empty buckets are stored dense (compressed all-absent body with a
 *       real xxhash), matching upstream; V2/V3 stay sparse.</li>
 * </ul>
 *
 * <p>Why {@link #implementation()} returns {@code V1} instead of a new
 * {@code LinearImplementation} member: Pumpkin shares wire byte 2 with V1, and
 * R4 keeps {@code LinearImplementation} (V1(2)/V2(3)/V3(4)) the sole wire truth
 * for config and headers, so no new member may exist. Pumpkin therefore
 * resolves via the section-4 detector ({@code RegionFormatDetector}), never
 * via the version byte (per R3: {@code LinearBase.getShared} maps 1,2 -&gt; V1
 * and must stay untouched). A hash mismatch throws (fail high); upstream
 * skips the bucket instead (documented divergence).</p>
 *
 * <p>Stateless shared instance, thread-safe.</p>
 */
public final class PumpkinLinear extends V2LinearBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(PumpkinLinear.class);

    /** Pumpkin default grid (upstream {@code DEFAULT_GRID_SIZE}). */
    public static final int DEFAULT_GRID_SIZE = 2;

    private static final PumpkinLinear SHARED = new PumpkinLinear();

    private PumpkinLinear() {
    }

    /** Shared stateless instance (Pumpkin resolves via the detector, not {@code LinearBase}). */
    public static PumpkinLinear shared() {
        return SHARED;
    }

    @Override
    public LinearImplementation implementation() {
        // Wire-2 truth per R4; the detector (not this value) selects Pumpkin per R3.
        return LinearImplementation.V1;
    }

    @Override
    protected int wireVersion() {
        return 2;
    }

    @Override
    protected int tableEntrySize() {
        return 13;
    }

    @Override
    protected boolean hasLevelByte() {
        return true;
    }

    @Override
    protected int slotLengthBias() {
        return PumpkinEncoder.slotLengthBias();
    }

    @Override
    protected int resolveGridSize(VersionWriteParams params) {
        return PumpkinEncoder.resolveGridSize(params);
    }

    @Override
    protected int resolveCompressionLevel(VersionWriteParams params) {
        // Upstream-faithful fixed level; byte-identity with ruzstd is not required (NFR5).
        return PumpkinEncoder.resolveCompressionLevel(params);
    }

    @Override
    protected int tableLevelByte(int level) {
        return PumpkinEncoder.tableLevelByte(level);
    }

    @Override
    protected int bucketIndex(int bx, int bz, int grid) {
        return PumpkinDecoder.bucketIndex(bx, bz, grid);
    }

    @Override
    protected boolean writeEmptyBucketsDense() {
        return PumpkinEncoder.writeEmptyBucketsDense();
    }

    @Override
    protected boolean transposeSlotOrder() {
        return PumpkinEncoder.transposeSlotOrder();
    }

    @Override
    protected int coerceTimestamp(long wireTimestamp, ChunkSlotTable out, AtomicBoolean clampWarned) {
        return PumpkinDecoder.coerceTimestamp(wireTimestamp, out, clampWarned);
    }

    @Override
    protected int readHeader(ByteBuffer raw) throws IOException {
        return PumpkinDecoder.readHeader(raw);
    }

    @Override
    protected ByteBuffer writeHeader(ChunkSlotView slots, VersionWriteParams params) {
        return PumpkinEncoder.writeHeader(slots, params);
    }

    /** Header byte length on write (fixed: no features are ever written). */
    public static int headerLength() {
        return 8 + 1 + 8 + 1 + 4 + 4 + 128 + 1;
    }
}
