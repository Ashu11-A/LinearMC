package net.linear.versions.encoders;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.LinearRegionFile;
import net.linear.compression.Lz4SlotCodec;
import net.linear.RegionFileFormat;
import net.linear.versions.codecs.V1Linear;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.VersionWriteParams;

/**
 * Write side of the V1 codec (wires 1 and 2, single zstd blob).
 *
 * <p>The {@code V1Linear} version class delegates its {@code flush} here:
 * decompressed region image assembly, single checksummed zstd frame with
 * the caller's worker/LDM tuning, 32 B header plus 8 B footer.</p>
 */
public final class V1Encoder {

    private static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;
    private static final byte VERSION_WRITE = 2;
    private static final int HEADER_SIZE = 8 + 1 + 8 + 1 + 2 + 4 + 8; // 32
    private static final int FOOTER_SIZE = 8;
    private static final int TABLE_SIZE = ChunkSlotTable.SLOTS * 8; // 8192

    private V1Encoder() {
    }

    /**
     * Renders {@code slots} into a whole file image (position 0).
     *
     * @param slots snapshot tables to read (not mutated)
     * @param params write inputs (level, grid, coords, timestamps, zstd tuning)
     * @return complete file bytes to persist verbatim
     */
    public static ByteBuffer flush(ChunkSlotView slots, VersionWriteParams params) throws IOException {
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
                final int consumed = Lz4SlotCodec.unpack(frame, image, (int) writeOffset, snapSize[i]);
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
}
