package net.linear.buffered;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import net.linear.BufferedLinearRegionFile;
import net.linear.LinearRegionFile;

/**
 * Swap-file layout and sector-table IO for the buffered-linear write-ahead log.
 *
 * <p>Big-endian layout: 8 B magic, 1 B version, 4 B xxhash seed, 8 B allocator
 * high-water, then 1024 x 17 B sector entries (8 B offset, 8 B length, 1 B live
 * flag). Owns the sector-table codecs verbatim from
 * {@code BufferedLinearRegionFile}; the owner delegates with its live table
 * state. Single source of truth for swap bounds.</p>
 */
public final class BufferedSwapFile {

    /** Swap magic (DivineMC-compatible). */
    public static final long SWAP_SUPERBLOCK = 0x1145141919810L;

    /** Swap layout version. */
    public static final byte SWAP_VERSION = 2;

    /** Default xxhash seed. */
    public static final int DEFAULT_SEED = 0x0721;

    /** Bytes per sector-table entry (8 B offset + 8 B length + 1 B live). */
    public static final int SECTOR_ENTRY_SIZE = 17;

    /** Bytes for the full 1024-entry sector table. */
    public static final int SECTOR_TABLE_SIZE = 1024 * SECTOR_ENTRY_SIZE;

    /** Magic + version + seed + high-water + sector table. */
    public static final int SWAP_HEADER_SIZE = 8 + 1 + 4 + 8 + SECTOR_TABLE_SIZE;

    private BufferedSwapFile() {
    }

    /**
     * Owner prune predicate (single source of truth for the upcoming envelope
     * extraction): true when a non-empty master snapshot exists beside the
     * swap. Byte-identical to the owner inline check — the owner and the
     * {@code B_LINEAR} sink adopt this when staging moves over, so the prune
     * rule ({@code master present OR husk}) lives in exactly one place.
     */
    public static boolean isMasterPresent(Path master) {
        try {
            return master != null && Files.isRegularFile(master) && Files.size(master) > 0L;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Best-effort delete; never throws. */
    public static void deleteQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /**
     * Reads the swap header into the given table; returns the allocator
     * high-water and writes the seed to {@code hashSeedOut[0]}.
     */
    public static long readSwapHeaders(FileChannel swapChannel, Path swapPath, long[] sectorOffset,
            long[] sectorLength, boolean[] sectorLive, int[] hashSeedOut) throws IOException {
        long size = swapChannel.size();
        if (size < SWAP_HEADER_SIZE) {
            throw new IOException("Truncated swap file: " + swapPath + " (" + size + " bytes)");
        }
        ByteBuffer header = ByteBuffer.allocate(SWAP_HEADER_SIZE);
        readFully(swapChannel, 0L, header);
        header.flip();
        if (header.getLong() != SWAP_SUPERBLOCK) {
            throw new IOException("Invalid swap superblock in " + swapPath);
        }
        if ((header.get() & 0xFF) != (SWAP_VERSION & 0xFF)) {
            throw new IOException("Unsupported swap version in " + swapPath);
        }
        hashSeedOut[0] = header.getInt();
        long acquired = header.getLong();
        long maxEnd = SWAP_HEADER_SIZE;
        for (int i = 0; i < 1024; i++) {
            long offset = header.getLong();
            long length = header.getLong();
            boolean live = header.get() == 1;
            if (offset < 0L || length < 0L || (live && (offset < SWAP_HEADER_SIZE || offset + length > size))) {
                live = false;
                length = 0L;
                offset = 0L;
            }
            sectorOffset[i] = offset;
            sectorLength[i] = length;
            sectorLive[i] = live && length > 0;
            if (offset + length > maxEnd && offset + length <= size) {
                maxEnd = offset + length;
            }
        }
        return Math.max(acquired, maxEnd);
    }

    public static ByteBuffer encodeSwapHeader(long acquired, int hashSeed, long[] sectorOffset,
            long[] sectorLength, boolean[] sectorLive) {
        ByteBuffer header = ByteBuffer.allocate(SWAP_HEADER_SIZE);
        header.putLong(SWAP_SUPERBLOCK);
        header.put(SWAP_VERSION);
        header.putInt(hashSeed);
        header.putLong(acquired);
        for (int i = 0; i < 1024; i++) {
            header.putLong(sectorOffset[i]);
            header.putLong(sectorLength[i]);
            header.put((byte) (sectorLive[i] ? 1 : 0));
        }
        header.flip();
        return header;
    }

    public static void writeSwapHeadersLocked(FileChannel swapChannel, long acquiredIndex, int hashSeed,
            long[] sectorOffset, long[] sectorLength, boolean[] sectorLive, boolean force) throws IOException {
        writeFully(swapChannel, 0L, encodeSwapHeader(acquiredIndex, hashSeed, sectorOffset, sectorLength, sectorLive));
        if (force) {
            swapChannel.force(true);
        }
    }

    /** True iff the live sector's envelope reads and its hash verifies. */
    public static boolean verifySector(FileChannel swapChannel, long[] sectorOffset, long[] sectorLength,
            int index, Path file) {
        try {
            if (sectorLength[index] <= 0 || sectorLength[index] > LinearRegionFile.MAX_CHUNK_SIZE + 1024L) {
                return false;
            }
            if (sectorOffset[index] < SWAP_HEADER_SIZE
                    || sectorOffset[index] + sectorLength[index] > swapChannel.size()) {
                return false;
            }
            byte[] outer = new byte[(int) sectorLength[index]];
            readFully(swapChannel, sectorOffset[index], outer);
            BufferedMaster.decodeOuterToEnvelope(outer, index, file);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

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

    private static void writeFully(FileChannel channel, long position, ByteBuffer src) throws IOException {
        int start = src.position();
        while (src.hasRemaining()) {
            int n = channel.write(src, position + (src.position() - start));
            if (n < 0) {
                throw new IOException("Truncated write at offset " + position);
            }
        }
    }
}
