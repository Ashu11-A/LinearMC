package net.linear.versions;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import net.jpountz.lz4.LZ4Factory;
import net.linear.LinearImplementation;
import net.linear.LinearRegionFile;
import net.linear.RegionFileFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V1 codec (wires 1 and 2, single zstd blob).
 *
 * <p>Moved verbatim from {@code LinearRegionFile}: 32 B header, 8 B table
 * entries, single zstd frame, footer validation, timestamp preservation,
 * chunkCount/fileLength checks. Stateless shared instance, thread-safe.</p>
 */
public final class V1Linear implements Version {

    private static final Logger LOGGER = LoggerFactory.getLogger(V1Linear.class);

    private static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;
    private static final byte VERSION_WRITE = 2;
    private static final int HEADER_SIZE = 8 + 1 + 8 + 1 + 2 + 4 + 8; // 32
    private static final int FOOTER_SIZE = 8;
    private static final int TABLE_SIZE = ChunkSlotTable.SLOTS * 8; // 8192

    private static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();

    private static final V1Linear SHARED = new V1Linear();

    private V1Linear() {
    }

    /** Shared stateless instance (see {@code LinearBase.getShared}). */
    public static V1Linear shared() {
        return SHARED;
    }

    /** Header byte length (fixed 32 B). */
    public static int headerLength() {
        return HEADER_SIZE;
    }

    @Override
    public LinearImplementation implementation() {
        return LinearImplementation.V1;
    }

    @Override
    public void parse(ByteBuffer fileImage, ChunkSlotTable out) throws IOException {
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
                    out.lz4Payloads[i] = lz4Pack(ByteBuffer.wrap(payload));
                    out.uncompressedSize[i] = sizes[i];
                }
            }
            if (in.read() != -1) {
                throw new IOException("Trailing bytes after payloads");
            }
        }
    }

    @Override
    public ByteBuffer flush(ChunkSlotView slots, VersionWriteParams params) throws IOException {
        int[] snapSize = slots.uncompressedSize();
        int[] snapTime = slots.timestamps();
        ByteBuffer[] snapPacked = slots.lz4Payloads();
        int level = RegionFileFormat.clampCompressionLevel(params.compressionLevel());

        int chunkCount = 0;
        for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
            if (snapSize[i] > 0) {
                chunkCount++;
            }
        }

        // Linear - decompress each LZ4 slot straight into its
        // final offset in image (removes one full region copy; previously
        // held plain[] AND image ~2x). Absolute LZ4 calls leave positions
        // untouched, so the table puts + absolute decompresses compose.
        long totalRaw = 0L;
        for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
            if (snapSize[i] > 0) {
                totalRaw += snapSize[i];
                if (totalRaw > LinearRegionFile.MAX_IMAGE_BYTES) {
                    throw new IOException("Region image too large");
                }
            }
        }

        // Assemble the decompressed region image in one direct buffer.
        final ByteBuffer image = ByteBuffer.allocateDirect(TABLE_SIZE + (int) totalRaw);
        for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
            image.putInt(snapSize[i]);
            image.putInt(snapTime[i]);
        }
        long writeOffset = TABLE_SIZE;
        for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
            if (snapSize[i] > 0) {
                final ByteBuffer frame = snapPacked[i].duplicate();
                final int consumed = LZ4.fastDecompressor().decompress(
                    frame, frame.position(), image, (int) writeOffset, snapSize[i]);
                if (consumed != frame.remaining()) {
                    throw new IOException("LZ4 frame mismatch in slot " + i);
                }
                writeOffset += snapSize[i];
            }
        }
        // Table puts advanced position to TABLE_SIZE; absolute decompresses
        // did not move it, so position explicitly to the end before flip.
        image.position((int) (TABLE_SIZE + totalRaw));
        image.flip();

        // Compress the image: same 32 KiB pump the old streaming flush used,
        // so frame bytes are identical; the caller persists the returned
        // header + frame + footer image via tmp + fsync + atomic move.
        final byte[] frame;
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(baos, level)) {
            zstd.setChecksum(true);
            // Linear: writer setWorkers/setLong, both default 0 =
            // inert (serial, LDM off). MUST precede first write() or
            // IllegalStateException. JNI caps setLong at
            // windowLog 27; workers at high levels show zero speedup
            // for large native cost; LDM at low levels is cheap.
            // MT at level 22 has zero speedup and ~690MB native cost.
            // NATIVE-MEMORY CAUTION: setWorkers(n) at high levels
            // reserves GBs (level 22 + workers=2 is +3.2GB VSZ for zero
            // gain, single 512MiB job); use workers only at low levels
            // (level <= ~9 where jobs exist). Level 22 + flush threads
            // >=4 can exhaust a 6GiB container.
            final int zstdWorkers = params.workers();
            if (zstdWorkers > 0) {
                zstd.setWorkers(zstdWorkers);
            }
            final int ldmWindow = params.ldm();
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
        } // close finalizes the frame (checksum + end mark) before the bytes are read
        frame = baos.toByteArray();
        final long packedLength = frame.length;
        if (packedLength < 0L || packedLength > Integer.MAX_VALUE) {
            throw new IOException("Invalid zstd frame length " + packedLength);
        }

        final long fileLength = (long) HEADER_SIZE + packedLength + FOOTER_SIZE;
        if (fileLength > Integer.MAX_VALUE) {
            throw new IOException("Region image too large");
        }
        ByteBuffer fileImage = ByteBuffer.allocate((int) fileLength);
        fileImage.putLong(SUPERBLOCK);
        fileImage.put(VERSION_WRITE);
        fileImage.putLong(params.newestTimestamp());
        fileImage.put((byte) level);
        fileImage.putShort((short) chunkCount);
        fileImage.putInt((int) packedLength);
        fileImage.putLong(0L); // reserved
        fileImage.put(frame);
        fileImage.putLong(SUPERBLOCK);
        fileImage.flip();
        return fileImage;
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
