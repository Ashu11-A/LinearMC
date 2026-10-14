package net.linear.versions.decoders;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.linear.versions.base.AbstractLinearVersion;
import net.linear.versions.codecs.PumpkinLinear;
import net.linear.versions.encoders.PumpkinEncoder;
import net.linear.versions.ChunkSlotTable;

/**
 * Read side of the Pumpkin codec (wire 2, bucketed {@code .linear} layout).
 *
 * <p>The {@code PumpkinLinear} version class delegates its read hooks here:
 * {@code readHeader} (newest-timestamp, grid, region coords, LSB-first
 * bitmap, features), {@code coerceTimestamp} (u64 clamp to int32 with one
 * warn per file) and {@code bucketIndex} (transposed {@code bz*grid+bx}).
 * Read-path slot decoding additionally honors the write-side hooks owned
 * by {@link PumpkinEncoder} ({@code slotLengthBias} 0 and transposed
 * interiors) via the version class.</p>
 */
public final class PumpkinDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(PumpkinDecoder.class);

    private PumpkinDecoder() {
    }

    /**
     * Reads the Pumpkin header from {@code raw} (positioned just after the
     * version byte) and returns the validated grid size.
     */
    public static int readHeader(ByteBuffer raw) throws IOException {
        if (raw.remaining() < 8 + 1 + 4 + 4 + 128 + 1) {
            throw new IOException("Truncated Pumpkin header");
        }
        raw.getLong(); // newest-timestamp: recomputed on flush, informational only
        int grid = raw.get() & 0xFF;
        AbstractLinearVersion.checkGrid(grid);
        raw.getInt(); // regionX (filename is authoritative; the detector validates equality)
        raw.getInt(); // regionZ
        raw.position(raw.position() + 128); // existence bitmap, LSB-first, informational on read
        AbstractLinearVersion.skipFeatures(raw);
        return grid;
    }

    /**
     * Converts a wire u64 timestamp to the int32 slot table, clamping
     * values past {@code Integer.MAX_VALUE} with one warn per file.
     */
    public static int coerceTimestamp(long wireTimestamp, ChunkSlotTable out, AtomicBoolean clampWarned) {
        if (Long.compareUnsigned(wireTimestamp, (long) Integer.MAX_VALUE) > 0) {
            if (!clampWarned.getAndSet(true)) {
                LOGGER.warn("Pumpkin u64 timestamp {} exceeds int32 range, clamping to {} in {}",
                    Long.toUnsignedString(wireTimestamp), Integer.MAX_VALUE, out.sourceName);
            }
            return Integer.MAX_VALUE;
        }
        return (int) wireTimestamp;
    }

    /**
     * Bucket index for bucket coordinates {@code (bx, bz)}: transposed
     * {@code bz*grid+bx}, matching upstream {@code global_chunk_index}.
     */
    public static int bucketIndex(int bx, int bz, int grid) {
        return bz * grid + bx;
    }
}
