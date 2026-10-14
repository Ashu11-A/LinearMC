package net.linear.convert.validate;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.linear.convert.scan.CandidateScanner;
import net.linear.AbstractRegionFile;
import net.linear.BufferedLinearRegionFile;
import net.linear.ChunkKey;
import net.linear.LinearImplementation;
import net.linear.LinearRegionConverter;
import net.linear.RegionFileFormat;

/**
 * Additive convert shim: pure validation checks and header size constants.
 *
 * <p>Facade-adoption note: {@code LinearRegionConverter} still owns the
 * validation predicates. When wiring delegates, the facade adopts
 * {@link #countsMatch} and these minimums wholesale (values copied
 * byte-identically from the facade's constants).</p>
 */
public final class ConversionValidator {

    /** Linear on-disk magic opening/closing a region file. */
    public static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;
    /** Header + footer minimum: a valid {@code .linear} file is larger than this. */
    public static final long MIN_VALID_SIZE = 32L + 8L;
    /**
     * Minimum size of a valid {@code .mca} file: two 4 KiB sectors (offset
     * table + timestamp table). Vanilla pads every region file to at least
     * this on open, so a smaller file was never written by vanilla.
     */
    public static final long MIN_ANVIL_SIZE = 8192L;
    /** Minimum size of a valid {@code .b_linear} master: 18 B header + a small frame. */
    public static final long MIN_BLINEAR_SIZE = 24L;

    private ConversionValidator() {
    }

    /** Parity rule: equal counts; 0 == 0 is explicitly VALID (trivially empty file). */
    public static boolean countsMatch(int sourceCount, int targetCount) {
        return sourceCount == targetCount;
    }

    /** Retry rule: attempts are 1-based; attempts 1..2 may retry, attempt 3 is terminal. */
    public static boolean shouldRetry(int completedAttempts) {
        return completedAttempts < LinearRegionConverter.MAX_ATTEMPTS;
    }

    /** State-machine step. Pure logic for unit tests (mirrors the facade's FileState). */
    public static LinearRegionConverter.FileState transition(LinearRegionConverter.FileState current,
            boolean stageSuccess) {
        if (!stageSuccess) {
            return LinearRegionConverter.FileState.FAILED;
        }
        return switch (current) {
            case CONVERTING -> LinearRegionConverter.FileState.CONVERTED;
            case CONVERTED -> LinearRegionConverter.FileState.VALIDATING;
            case VALIDATING -> LinearRegionConverter.FileState.VALIDATED;
            case VALIDATED -> LinearRegionConverter.FileState.DELETING;
            case DELETING -> LinearRegionConverter.FileState.DELETED;
            default -> current;
        };
    }

    /**
     * Staging-validity gate for a freshly rendered image: valid iff
     * {@code size > MIN_VALID_SIZE}. Distinct from the header-sanity floor
     * ({@code size < MIN_VALID_SIZE} rejects): a file exactly at header+footer
     * size (40 B) can physically hold a header, so it passes sanity, but it
     * carries zero payload — the same-path staging check rejects
     * {@code <= MIN_VALID_SIZE} because a staging result that small is never
     * a complete render.
     */
    public static boolean isStagingValid(long size) {
        return size > MIN_VALID_SIZE;
    }

    /** Counts present chunks for the region at (regionX, regionZ). */
    public static int countChunks(AbstractRegionFile file, int regionX, int regionZ) {
        int n = 0;
        for (int lx = 0; lx < 32; lx++) {
            for (int lz = 0; lz < 32; lz++) {
                if (file.hasChunk(ChunkKey.of(regionX * 32 + lx, regionZ * 32 + lz))) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Copy-exact source count: a slot counts only when {@code hasChunk} is
     * true AND its payload stream opens non-null with a non-zero read —
     * exactly the slots the copy loop writes. IO errors propagate (a genuinely
     * corrupt chunk fails loudly into the FAILED path instead of converging
     * silently).
     */
    public static int countCopyableChunks(AbstractRegionFile src, int regionX, int regionZ) throws IOException {
        int n = 0;
        for (int lx = 0; lx < 32; lx++) {
            for (int lz = 0; lz < 32; lz++) {
                long pos = ChunkKey.of(regionX * 32 + lx, regionZ * 32 + lz);
                if (!src.hasChunk(pos)) {
                    continue;
                }
                byte[] payload;
                try (DataInputStream in = src.getChunkDataInputStream(pos)) {
                    if (in == null) {
                        continue;
                    }
                    payload = in.readAllBytes();
                }
                if (payload.length == 0) {
                    continue;
                }
                n++;
            }
        }
        return n;
    }

    /**
     * Decompress-verifies every present target payload for the region at
     * (regionX, regionZ): each counted chunk's stream must open non-null,
     * drain fully and yield non-zero bytes. Null streams, empty reads and
     * decompression errors (torn middle with valid framing) fail validation.
     */
    public static void verifyTargetPayloads(AbstractRegionFile dst, int regionX, int regionZ) throws IOException {
        for (int lx = 0; lx < 32; lx++) {
            for (int lz = 0; lz < 32; lz++) {
                long pos = ChunkKey.of(regionX * 32 + lx, regionZ * 32 + lz);
                if (!dst.hasChunk(pos)) {
                    continue;
                }
                DataInputStream opened = dst.getChunkDataInputStream(pos);
                if (opened == null) {
                    throw new IOException("Target chunk " + pos + " has no readable payload (torn write?)");
                }
                byte[] payload;
                try (DataInputStream in = opened) {
                    payload = in.readAllBytes();
                } catch (IOException e) {
                    throw new IOException(
                        "Target chunk " + pos + " payload unreadable (torn write?): " + e.getMessage(), e);
                }
                if (payload.length == 0) {
                    throw new IOException("Target chunk " + pos + " has empty payload (torn write?)");
                }
            }
        }
    }

    /** Header/footer sanity: magic + version range + footer magic. Pure byte logic. */
    public static void checkHeaderSanity(Path target) throws IOException {
        if (target.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
            checkBufferedHeaderSanity(target);
            return;
        }
        long size = Files.size(target);
        try (FileChannel ch = FileChannel.open(target, StandardOpenOption.READ)) {
            if (size < MIN_VALID_SIZE) {
                throw new IOException("File smaller than header+footer: " + target);
            }
            ByteBuffer head = ByteBuffer.allocate(32);
            head.order(ByteOrder.BIG_ENDIAN);
            int read = 0;
            while (head.hasRemaining()) {
                int n = ch.read(head, read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < 32) {
                throw new IOException("Truncated header in " + target);
            }
            head.flip();
            long magic = head.getLong();
            byte version = head.get();
            if (magic != SUPERBLOCK) {
                throw new IOException("Bad magic in " + target);
            }
            if (!LinearImplementation.isSupportedWire(version & 0xFF)) {
                throw new IOException("Unsupported linear version " + (version & 0xFF) + " in " + target);
            }
            ByteBuffer foot = ByteBuffer.allocate(8);
            foot.order(ByteOrder.BIG_ENDIAN);
            int got = 0;
            while (foot.hasRemaining()) {
                int n = ch.read(foot, size - 8 + got);
                if (n < 0) {
                    break;
                }
                got += n;
            }
            if (got < 8) {
                throw new IOException("Truncated footer in " + target);
            }
            foot.flip();
            if (foot.getLong() != SUPERBLOCK) {
                throw new IOException("Bad footer magic in " + target);
            }
        }
    }

    /**
     * Buffered-master sanity: 18 B header magic + version. Pure byte logic
     * (the master carries no footer; the zstd frame is self-checking and the
     * full payload drain in validation covers the middle).
     */
    public static void checkBufferedHeaderSanity(Path target) throws IOException {
        long size = Files.size(target);
        if (size < MIN_BLINEAR_SIZE) {
            throw new IOException("File smaller than buffered header: " + target);
        }
        try (FileChannel ch = FileChannel.open(target, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate(18);
            head.order(ByteOrder.BIG_ENDIAN);
            int read = 0;
            while (head.hasRemaining()) {
                int n = ch.read(head, read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < 18) {
                throw new IOException("Truncated buffered header in " + target);
            }
            head.flip();
            if (head.getLong() != BufferedLinearRegionFile.MASTER_SUPERBLOCK) {
                throw new IOException("Bad buffered magic in " + target);
            }
            if ((head.get() & 0xFF) != (BufferedLinearRegionFile.MASTER_VERSION & 0xFF)) {
                throw new IOException("Unsupported buffered version in " + target);
            }
        }
    }

    /**
     * Validation predicate (existence + size + header + count parity + full
     * payload verification).
     *
     * <p>Checks, in order: (a) target exists and is larger than the 40-byte
     * header+footer minimum — required even for 0-chunk sources, which must
     * still yield a materialized valid empty target (never "trivially valid"
     * with nothing preserved); (b) chunk-count parity between the
     * copy-exact source count and the target, with {@code 0 == 0} VALID;
     * (c) target header sanity (magic, version range) plus footer magic;
     * (d) decompress-verification of every counted target payload.</p>
     *
     * @return human-readable validation detail for logging
     */
    public static String validatePair(Path source, Path target) throws IOException {
        CandidateScanner.RegionName coords =
            CandidateScanner.parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable region name: " + source);
        }
        int sourceCount;
        int targetCount;
        try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, source.getParent(),
            RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
            sourceCount = countCopyableChunks(src, coords.x(), coords.z());
        } catch (Exception e) {
            throw new IOException("Cannot open/count source " + source + ": " + e.getMessage(), e);
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("Target missing: " + target);
        }
        long size = Files.size(target);
        long minTarget = target.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)
            ? MIN_BLINEAR_SIZE
            : MIN_VALID_SIZE;
        if (size <= minTarget) {
            throw new IOException("Target too small (" + size + " bytes): " + target);
        }
        checkHeaderSanity(target);
        try (AbstractRegionFile dst = LinearRegionConverter.openProbed(target, target.getParent(),
            RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
            targetCount = countChunks(dst, coords.x(), coords.z());
            if (!countsMatch(sourceCount, targetCount)) {
                throw new IOException("Chunk-count mismatch source=" + sourceCount + " target=" + targetCount
                    + " for " + source.getFileName());
            }
            verifyTargetPayloads(dst, coords.x(), coords.z());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot open/count target " + target + ": " + e.getMessage(), e);
        }
        return "chunks=" + sourceCount + " size=" + size + "B header ok, payloads verified";
    }

    /**
     * Reverse validation predicate (existence + anvil size + count parity +
     * full payload verification).
     *
     * @return human-readable validation detail for logging
     */
    public static String validateReversePair(Path source, Path target) throws IOException {
        CandidateScanner.RegionName coords =
            CandidateScanner.parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable reverse region name: " + source);
        }
        int sourceCount;
        int targetCount;
        try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, source.getParent(),
            RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
            sourceCount = countCopyableChunks(src, coords.x(), coords.z());
        } catch (Exception e) {
            throw new IOException("Cannot open/count reverse source " + source + ": " + e.getMessage(), e);
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("Reverse target missing: " + target);
        }
        long size = Files.size(target);
        if (size < MIN_ANVIL_SIZE) {
            throw new IOException("Reverse target too small (" + size + " bytes): " + target);
        }
        try (AbstractRegionFile dst = LinearRegionConverter.openProbed(target, target.getParent(),
            RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
            targetCount = countChunks(dst, coords.x(), coords.z());
            if (!countsMatch(sourceCount, targetCount)) {
                throw new IOException("Reverse chunk-count mismatch source=" + sourceCount + " target=" + targetCount
                    + " for " + source.getFileName());
            }
            verifyTargetPayloads(dst, coords.x(), coords.z());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot open/count reverse target " + target + ": " + e.getMessage(), e);
        }
        return "chunks=" + sourceCount + " size=" + size + "B anvil ok, payloads verified";
    }
}
