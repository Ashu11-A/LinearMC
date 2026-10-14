package net.linear.convert.copy;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.linear.convert.scan.CandidateScanner;
import net.linear.convert.validate.ConversionValidator;
import net.linear.AbstractRegionFile;
import net.linear.BufferedLinearRegionFile;
import net.linear.ChunkKey;
import net.linear.LinearRegionConverter;
import net.linear.LinearRegionFile;
import net.linear.RegionFileFormat;

/**
 * Chunk-copy helpers extracted from the {@code LinearRegionConverter} facade.
 *
 * <p>Owns the single copy of the single-file conversion bodies
 * ({@code convertSingleFile} / {@code convertSingleFileToFormat} /
 * {@code convertSingleFileReverse}) plus the chunk-copy and empty-target
 * materialization primitives. Storage seams stay in the facade:
 * source opens go through {@code LinearRegionConverter.openProbed} and
 * reverse target writes through {@code LinearRegionConverter.openSink},
 * so this file never touches the volatile opener state directly. Validators
 * are reached via {@code ConversionValidator} and path helpers via
 * {@code CandidateScanner}; coords use the public
 * {@code CandidateScanner.RegionName} (never the facade's private record).
 */
public final class ChunkCopier {

    private ChunkCopier() {}

    /**
     * Copies all chunks from {@code source (.linear)} to
     * {@code finalTarget (.mca)} via the sibling {@code new_<folder>} staging
     * directory, then moves the staged file to its final name. Plain move (NOT
     * ATOMIC_MOVE) for parity with the forward path. Source reads go through
     * the facade opener; target writes go through the facade sink opener as raw
     * NBT bytes (legs envelop).
     */
    public static void convertSingleFileReverse(Path source, Path finalTarget, int compressionLevel)
            throws IOException {
        CandidateScanner.RegionName coords =
            CandidateScanner.parseRegionName(source.getFileName().toString());
        String sourceName = source.getFileName().toString();
        boolean sourceLinear = sourceName.endsWith(RegionFileFormat.LINEAR_EXTENSION);
        boolean sourceBuffered = sourceName.endsWith(RegionFileFormat.B_LINEAR_EXTENSION);
        if (coords == null || (!sourceLinear && !sourceBuffered)) {
            throw new IOException("Not a convertible linear-family region file: " + source);
        }
        Path folder = source.getParent();
        Path stagingDir = CandidateScanner.resolveRecreateDirectory(folder);
        Files.createDirectories(stagingDir);
        Path stagingTarget = stagingDir.resolve(finalTarget.getFileName().toString());

        int level = RegionFileFormat.clampCompressionLevel(compressionLevel);

        // Truncated-source guard (mirror of the forward MIN_ANVIL_SIZE guard):
        // a valid linear-family file is larger than its header minimum
        // (.linear: 40-byte header+footer; .b_linear: master header). A
        // nonzero file at or below that size was never validly written (torn
        // header) and fails closed (FAILED path, never delete). A 0-byte
        // file is trivially empty and proceeds to the
        // materialize-empty-target path. The header-magic check runs BEFORE
        // opening so a padding constructor cannot mask a torn source that
        // would otherwise count 0 and be deleted.
        long reverseSourceSize = Files.size(source);
        long reverseMin = sourceBuffered
            ? LinearRegionConverter.MIN_BLINEAR_SIZE
            : LinearRegionConverter.MIN_VALID_SIZE;
        if (reverseSourceSize != 0L && reverseSourceSize <= reverseMin) {
            throw new IOException("Reverse source too small to hold a valid linear header ("
                + reverseSourceSize + " bytes): " + source);
        }
        if (reverseSourceSize != 0L) {
            ConversionValidator.checkHeaderSanity(source);
        }

        int copied = 0;
        int sourceCount = 0;
        try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, folder, level)) {
            sourceCount = ConversionValidator.countCopyableChunks(src, coords.x(), coords.z());
            if (sourceCount == 0) {
                materializeEmptyMcaTarget(stagingTarget, level);
            } else {
                try (LinearRegionConverter.ChunkSink dst =
                        LinearRegionConverter.openSink(stagingTarget, folder, level)) {
                    for (int lx = 0; lx < 32; lx++) {
                        for (int lz = 0; lz < 32; lz++) {
                            long pos = ChunkKey.of(coords.x() * 32 + lx, coords.z() * 32 + lz);
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
                            dst.write(pos, ByteBuffer.wrap(payload));
                            copied++;
                        }
                    }
                    dst.flush();
                }
            }
        }

        if (!Files.isRegularFile(stagingTarget)) {
            throw new IOException("Reverse staging file missing after conversion: " + stagingTarget
                + " (copied " + copied + " of " + sourceCount + " chunks)");
        }
        Files.createDirectories(finalTarget.getParent());
        Files.move(stagingTarget, finalTarget, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Materializes a valid empty {@code .mca} at {@code stagingTarget} for a
     * 0-chunk linear source: open + flush + close with zero chunks (vanilla
     * pads the 8 KiB header on open). Any stale staged file is removed first.
     */
    public static void materializeEmptyMcaTarget(Path stagingTarget, int compressionLevel) throws IOException {
        Files.deleteIfExists(stagingTarget);
        int level = RegionFileFormat.clampCompressionLevel(compressionLevel);
        Path folder = stagingTarget.getParent();
        if (folder == null) {
            folder = stagingTarget;
        }
        try (LinearRegionConverter.ChunkSink dst = LinearRegionConverter.openSink(stagingTarget, folder, level)) {
            dst.flush();
        }
        if (!Files.isRegularFile(stagingTarget)) {
            throw new IOException("Empty-target reverse materialization left no file: " + stagingTarget);
        }
    }

    /**
     * Copies all chunks from {@code source (.mca)} to {@code finalTarget (.linear)}
     * via the sibling {@code new_<folder>} staging directory, then moves the
     * staged file to its final name. Plain move (NOT ATOMIC_MOVE) for parity
     * with {@code RegionStorageUpgrader.onFileFinished}: atomic moves are not
     * guaranteed across filesystems and the existing commit path uses plain moves.
     */
    public static void convertSingleFile(Path source, Path finalTarget, int compressionLevel) throws IOException {
        convertSingleFileToFormat(source, finalTarget, compressionLevel, RegionFileFormat.LINEAR);
    }

    /**
     * Copies all chunks from {@code source} (any convertible format) to
     * {@code finalTarget} ({@code .linear} or {@code .b_linear}) via staging,
     * then moves the staged file(s) to the final name. Same validate-before-
     * delete contract as {@link #convertSingleFile}; buffered targets move
     * their {@code .swp} sibling alongside the master.
     */
    public static void convertSingleFileToFormat(Path source, Path finalTarget, int compressionLevel,
            RegionFileFormat targetFormat) throws IOException {
        if (targetFormat != RegionFileFormat.LINEAR && targetFormat != RegionFileFormat.B_LINEAR) {
            throw new IOException("Not a convertible target format " + targetFormat + " for " + source);
        }
        CandidateScanner.RegionName coords =
            CandidateScanner.parseRegionName(source.getFileName().toString());
        String sourceName = source.getFileName().toString();
        boolean sourceAnvil = sourceName.endsWith(RegionFileFormat.ANVIL_EXTENSION);
        boolean sourceLinear = sourceName.endsWith(RegionFileFormat.LINEAR_EXTENSION);
        boolean sourceBuffered = sourceName.endsWith(RegionFileFormat.B_LINEAR_EXTENSION);
        if (coords == null || (!sourceAnvil && !sourceLinear && !sourceBuffered)) {
            throw new IOException("Not a convertible region file: " + source);
        }
        Path folder = source.getParent();
        Path stagingDir = CandidateScanner.resolveRecreateDirectory(folder);
        Files.createDirectories(stagingDir);
        Path stagingTarget = stagingDir.resolve(finalTarget.getFileName().toString());

        int level = RegionFileFormat.clampCompressionLevel(compressionLevel);

        // Truncated-source guard (HIGH-1b): per-format minimums (anvil pads
        // to MIN_ANVIL_SIZE; linear-family files below their header minimum
        // were never validly written). A 0-byte file is trivially empty and
        // proceeds to the materialize-empty-target path. Checked BEFORE
        // opening so a padding constructor cannot mask a torn source that
        // would otherwise count 0 and be deleted: FAILED path, never delete.
        long sourceSize = Files.size(source);
        if (sourceAnvil) {
            if (sourceSize != 0L && sourceSize < LinearRegionConverter.MIN_ANVIL_SIZE) {
                throw new IOException("Source too small to hold a valid .mca header ("
                    + sourceSize + " bytes): " + source);
            }
        } else if (sourceLinear) {
            if (sourceSize != 0L && sourceSize <= LinearRegionConverter.MIN_VALID_SIZE) {
                throw new IOException("Source too small to hold a valid .linear header ("
                    + sourceSize + " bytes): " + source);
            }
            if (sourceSize != 0L) {
                ConversionValidator.checkHeaderSanity(source);
            }
        } else {
            if (sourceSize != 0L && sourceSize < LinearRegionConverter.MIN_BLINEAR_SIZE) {
                throw new IOException("Source too small to hold a valid .b_linear header ("
                    + sourceSize + " bytes): " + source);
            }
            if (sourceSize != 0L) {
                ConversionValidator.checkHeaderSanity(source);
            }
        }

        int copied = 0;
        int sourceCount = 0;
        try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, folder, level)) {
            // Count EXACTLY the way the copy loop below copies (MED-1):
            // degenerate slots (hasChunk true but null/empty payload) are
            // skipped by the copy, so counting them here would manufacture a
            // mismatch, burn 3 retries and halt boot. IO errors propagate.
            sourceCount = ConversionValidator.countCopyableChunks(src, coords.x(), coords.z());
            if (sourceCount == 0) {
                // Empty sources still materialize a valid empty target
                // (HIGH-1a): validation requires the target to exist even for
                // 0-chunk sources, so the source is never deleted with nothing
                // preserved.
                if (targetFormat == RegionFileFormat.B_LINEAR) {
                    materializeEmptyBufferedTarget(stagingTarget, level,
                        ChunkKey.of(coords.x() * 32, coords.z() * 32));
                } else {
                    materializeEmptyTarget(stagingTarget, level,
                        ChunkKey.of(coords.x() * 32, coords.z() * 32));
                }
            } else if (targetFormat == RegionFileFormat.B_LINEAR) {
                try (BufferedLinearRegionFile dst = new BufferedLinearRegionFile(stagingTarget, level)) {
                    copied = copyChunkRange(src, dst, coords);
                    dst.flush();
                }
            } else {
                try (LinearRegionFile dst = new LinearRegionFile(stagingTarget, level)) {
                    copied = copyChunkRange(src, dst, coords);
                    dst.flush();
                }
            }
        }

        if (!Files.isRegularFile(stagingTarget)) {
            throw new IOException("Staging file missing after conversion: " + stagingTarget
                + " (copied " + copied + " of " + sourceCount + " chunks)");
        }
        Files.createDirectories(finalTarget.getParent());
        Files.move(stagingTarget, finalTarget, StandardCopyOption.REPLACE_EXISTING);
        if (targetFormat == RegionFileFormat.B_LINEAR) {
            // The swap sibling travels with the master (same atomicity scope
            // as the master move: plain moves, matching the existing commit
            // path; validation re-verifies both after the move).
            Path stagingSwap = stagingTarget.resolveSibling(stagingTarget.getFileName() + ".swp");
            Path finalSwap = finalTarget.resolveSibling(finalTarget.getFileName() + ".swp");
            if (Files.isRegularFile(stagingSwap)) {
                Files.move(stagingSwap, finalSwap, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Copies every copyable chunk from {@code src} to {@code dst}.
     *
     * @return number of chunks copied
     */
    public static int copyChunkRange(AbstractRegionFile src, AbstractRegionFile dst,
            CandidateScanner.RegionName coords) throws IOException {
        int copied = 0;
        for (int lx = 0; lx < 32; lx++) {
            for (int lz = 0; lz < 32; lz++) {
                long pos = ChunkKey.of(coords.x() * 32 + lx, coords.z() * 32 + lz);
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
                dst.write(pos, ByteBuffer.wrap(payload));
                copied++;
            }
        }
        return copied;
    }

    /**
     * Materializes a valid empty {@code .linear} file (header + empty chunk
     * table + footer) at {@code stagingTarget} for a 0-chunk source.
     *
     * <p>A fresh {@code LinearRegionFile} handle with zero chunks is a
     * clean-no-op flush that leaves no file behind, so there is no "create
     * empty file" primitive: seed one dummy chunk, flush, clear it, flush.
     * The second flush persists while the file exists, yielding a valid empty
     * target. Any stale staged file is removed first so older chunks cannot
     * be resurrected into the "empty" target.</p>
     */
    public static void materializeEmptyTarget(Path stagingTarget, int compressionLevel, long dummyKey)
            throws IOException {
        Files.deleteIfExists(stagingTarget);
        int level = RegionFileFormat.clampCompressionLevel(compressionLevel);
        try (LinearRegionFile dst = new LinearRegionFile(stagingTarget, level)) {
            dst.write(dummyKey, ByteBuffer.wrap(new byte[]{0}));
            dst.flush();
            dst.clear(dummyKey);
            dst.flush();
        }
        if (!Files.isRegularFile(stagingTarget)) {
            throw new IOException("Empty-target materialization left no file: " + stagingTarget);
        }
    }

    /**
     * Materializes a valid empty {@code .b_linear} master (plus its swap) at
     * {@code stagingTarget} for a 0-chunk source: same dummy-write/clear
     * discipline as {@link #materializeEmptyTarget}. Any stale staged master
     * or swap is removed first.
     */
    public static void materializeEmptyBufferedTarget(Path stagingTarget, int compressionLevel, long dummyKey)
            throws IOException {
        Files.deleteIfExists(stagingTarget);
        Files.deleteIfExists(stagingTarget.resolveSibling(stagingTarget.getFileName() + ".swp"));
        int level = RegionFileFormat.clampCompressionLevel(compressionLevel);
        try (BufferedLinearRegionFile dst = new BufferedLinearRegionFile(stagingTarget, level)) {
            dst.write(dummyKey, ByteBuffer.wrap(new byte[]{0}));
            dst.flush();
            dst.clear(dummyKey);
            dst.flush();
        }
        if (!Files.isRegularFile(stagingTarget)) {
            throw new IOException("Empty-target materialization left no file: " + stagingTarget);
        }
    }
}
