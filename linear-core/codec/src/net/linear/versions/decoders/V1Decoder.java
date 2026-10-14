package net.linear.versions.decoders;

import com.github.luben.zstd.ZstdInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import net.linear.LinearRegionFile;
import net.linear.compression.Lz4SlotCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.linear.io.LinearDirectStreams;
import net.linear.versions.codecs.V1Linear;
import net.linear.versions.ChunkSlotTable;

/**
 * Read side of the V1 codec (wires 1 and 2, single zstd blob).
 *
 * <p>The {@code V1Linear} version class delegates its {@code parse} here:
 * 32 B header, 8 B table entries, single zstd frame, footer validation,
 * timestamp preservation, chunkCount/fileLength checks.</p>
 */
public final class V1Decoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(V1Decoder.class);

    private static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;
    private static final int HEADER_SIZE = 8 + 1 + 8 + 1 + 2 + 4 + 8; // 32
    private static final int FOOTER_SIZE = 8;

    private V1Decoder() {
    }

    /** Header byte length (fixed 32 B). */
    public static int headerLength() {
        return HEADER_SIZE;
    }

    /**
     * Inflates {@code fileImage} (whole file, position 0) into {@code out}.
     *
     * @param fileImage entire file bytes, positioned at 0; not mutated
     * @param out slot tables to fill
     */
    public static void parse(ByteBuffer fileImage, ChunkSlotTable out) throws IOException {
        ByteBuffer raw = fileImage.duplicate();
        long fileLength = fileImage.remaining();
        if (raw.getLong() != SUPERBLOCK) {
            throw new IOException("Invalid superblock");
        }
        int wire = raw.get() & 0xFF;
        if (wire != 1 && wire != 2) {
            throw new IOException("Unsupported linear version " + wire);
        }
        raw.getLong(); // newestTimestamp: recomputed on flush, informational only
        raw.get(); // compressionLevel: informational, the caller keeps its own
        final int expectedChunks = raw.getShort() & 0xFFFF;
        final int packedLength = raw.getInt();
        raw.getLong(); // reserved
        if (packedLength < 0 || (long) HEADER_SIZE + packedLength + FOOTER_SIZE != fileLength) {
            throw new IOException("Invalid packed length " + packedLength);
        }
        final ByteBuffer blob = raw.slice();
        blob.limit(packedLength);
        if (raw.getLong(HEADER_SIZE + packedLength) != SUPERBLOCK) {
            throw new IOException("Invalid footer superblock");
        }

        // Linear: reader setLongMax(27) ships WITH the writer
        // (reader-first for any future windowLog>27). Harmless no-op
        // today (decoder default already 27, setLong capped at 27 by
        // JNI; LDM-27 frames decode with stock readers). Set immediately
        // after construction, before first read(); only a
        // setWindowLog(28..31) writer would need >27.
        final ZstdInputStream zstdIn = new ZstdInputStream(wrap(blob));
        zstdIn.setLongMax(27);
        try (DataInputStream in = new DataInputStream(zstdIn)) {
            final int[] sizes = new int[ChunkSlotTable.SLOTS];
            final int[] times = new int[ChunkSlotTable.SLOTS];
            long totalRaw = 0L;
            int present = 0;
            for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
                sizes[i] = in.readInt();
                times[i] = in.readInt();
                if (sizes[i] < 0 || sizes[i] > LinearRegionFile.MAX_CHUNK_SIZE) {
                    throw new IOException("Invalid chunk size " + sizes[i] + " in slot " + i);
                }
                if (sizes[i] > 0) {
                    present++;
                    totalRaw += sizes[i];
                    if (totalRaw > LinearRegionFile.MAX_IMAGE_BYTES) {
                        throw new IOException("Region image too large");
                    }
                }
            }
            if (present != expectedChunks) {
                // Tolerate (LinearPaper-era and third-party tools disagree on
                // edge cases); the table itself is authoritative.
                LOGGER.warn("Chunk count mismatch: header says {}, table has {} in {}",
                    expectedChunks, present, out.sourceName);
            }
            for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
                // Timestamps are preserved for absent slots too (same invariant
                // as the bucketed versions), so a flush->load round-trip keeps
                // them; absent payloads stay null with size 0.
                out.timestamps[i] = times[i];
                if (sizes[i] > 0) {
                    // Cold path (open): exact heap read, then direct LZ4 staging.
                    final byte[] payload = in.readNBytes(sizes[i]);
                    if (payload.length != sizes[i]) {
                        throw new IOException("Truncated payload in slot " + i);
                    }
                    out.lz4Payloads[i] = Lz4SlotCodec.pack(ByteBuffer.wrap(payload));
                    out.uncompressedSize[i] = sizes[i];
                }
            }
            if (in.read() != -1) {
                throw new IOException("Trailing bytes after payloads");
            }
        }
    }

    /**
     * Minimal zero-copy bridge from a direct {@link ByteBuffer} to a blocking
     * stream (local copy of the {@code LinearDirectStreams} view; that helper
     * is package-private to {@code net.linear}).
     */
    private static InputStream wrap(final ByteBuffer buf) {
        Objects.requireNonNull(buf, "buf");
        return new InputStream() {
            @Override
            public int read() {
                return buf.hasRemaining() ? buf.get() & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                Objects.checkFromIndexSize(off, len, b.length);
                if (!buf.hasRemaining()) {
                    return -1;
                }
                int n = Math.min(len, buf.remaining());
                buf.get(b, off, n);
                return n;
            }

            @Override
            public int available() {
                return buf.remaining();
            }
        };
    }
}
