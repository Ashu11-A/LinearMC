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

        // Linear - stream the decompressed region image straight into the
        // zstd frame slot by slot: table first, then each payload
        // decompressed into a per-slot heap scratch. No whole-image direct
        // buffer is ever assembled (that staging was the largest single
        // transient direct allocation per flush and the first thing to
        // exhaust the direct cap under generation-speed writeback).
        // The frame bytes are identical: zstd output depends only on the
        // input bytes and settings, never on write() chunking.
        long totalRaw = 0L;
        for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
            if (snapSize[i] > 0) {
                totalRaw += snapSize[i];
                if (totalRaw > LinearRegionFile.MAX_IMAGE_BYTES) {
                    throw new IOException("Region image too large");
                }
            }
        }

        // Compress the image: table plus per-slot payloads pumped through
        // one checksummed zstd frame with the caller's worker/LDM tuning;
        // the caller persists the returned header + frame + footer image
        // via tmp + fsync + atomic move.
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
            final ByteBuffer table = ByteBuffer.allocate(TABLE_SIZE);
            for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
                table.putInt(snapSize[i]);
                table.putInt(snapTime[i]);
            }
            zstd.write(table.array());
            for (int i = 0; i < ChunkSlotTable.SLOTS; i++) {
                if (snapSize[i] > 0) {
                    // Heap scratch: collector-reclaimed, never counted
                    // against the direct-memory cap.
                    final byte[] plain = new byte[snapSize[i]];
                    final ByteBuffer frameIn = snapPacked[i].duplicate();
                    final int consumed = Lz4SlotCodec.unpack(
                        frameIn, ByteBuffer.wrap(plain), 0, snapSize[i]);
                    if (consumed != frameIn.remaining()) {
                        throw new IOException("LZ4 frame mismatch in slot " + i);
                    }
                    zstd.write(plain);
                }
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
