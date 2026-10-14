package net.linear.convert.routing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.linear.bucket.LinearBase;
import net.linear.convert.copy.ChunkCopier;
import net.linear.convert.scan.CandidateScanner;
import net.linear.convert.validate.ConversionValidator;
import net.linear.io.LinearDirectStreams;
import net.linear.probe.LinearBucketGrid;
import net.linear.versions.codecs.PumpkinLinear;
import net.linear.AbstractRegionFile;
import net.linear.ChunkKey;
import net.linear.LinearImplementation;
import net.linear.LinearRegionConverter;
import net.linear.LinearRegionFile;
import net.linear.RegionFileFormat;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.RegionFormatDetector;
import net.linear.versions.Version;
import net.linear.versions.VersionWriteParams;

/**
 * Additive convert shim: pure explicit-job routing, detection and validation.
 *
 * <p>Facade-adoption note: {@code LinearRegionConverter} still owns the live
 * explicit pipeline (per-file convert bodies, folder orchestration, candidate
 * collection). When wiring delegates, the facade adopts these helpers
 * wholesale (logic copied byte-identically from the facade's static
 * implementation, with the facade's private {@code RegionName} replaced by
 * {@link CandidateScanner#parseRegionName} and its private counters/openers
 * replaced by {@link ConversionValidator} plus
 * {@link LinearRegionConverter#openProbed} call-throughs). This file calls
 * {@link net.linear.versions.RegionFormatDetector},
 * {@link net.linear.bucket.LinearBase} and {@link net.linear.versions.codecs.PumpkinLinear}
 * directly and duplicates the two grid-default constants locally.</p>
 *
 * <p>NMS-dependent per-file convert bodies ({@code convertSingleFileExplicit},
 * {@code convertSingleFileToPumpkin}, {@code convertSingleFileToLinearSamePath},
 * {@code restageAsImpl}) live here as public statics; the facade delegates.</p>
 */
public final class ExplicitConvertRouter {

    /**
     * Pumpkin default grid (upstream {@code DEFAULT_GRID_SIZE}) when the job
     * omits grid. Duplicated locally from the facade
     * ({@code LinearRegionConverter.PUMPKIN_DEFAULT_GRID}); keep in sync.
     */
    public static final int PUMPKIN_DEFAULT_GRID = 2;

    /**
     * V2/V3 default grid when the job omits grid. Duplicated locally from the
     * facade ({@code LinearRegionConverter.BUCKETED_DEFAULT_GRID}); keep in sync.
     */
    public static final int BUCKETED_DEFAULT_GRID = 8;

    private ExplicitConvertRouter() {
    }

    /**
     * Detects the layout of a {@code .linear} file image (never wire-byte alone:
     * byte 17 alone cannot discriminate V1 level 1..22 from Pumpkin/V2 grid).
     *
     * @return single validating format (V1/V2/V3/PUMPKIN)
     * @throws IOException fail-closed with diagnosis (corrupt/unknown or ambiguous,
     *         candidates named; never falls through into another layout's reader)
     */
    public static net.linear.versions.RegionFormatDetector.DetectedFormat detectLinearFormat(Path file)
            throws IOException {
        CandidateScanner.RegionName coords = CandidateScanner.parseRegionName(file.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable region name: " + file);
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IOException("Cannot read for detection " + file + ": " + e.getMessage(), e);
        }
        ByteBuffer image = ByteBuffer.wrap(raw);
        try {
            return net.linear.versions.RegionFormatDetector.shared().detect(image, coords.x(), coords.z());
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " in " + file, e);
        }
    }

    /**
     * True when a {@code .linear} file is Pumpkin-layout (definite or ambiguous
     * naming PUMPKIN). Non-{@code .linear} files never count. Corrupt/unknown
     * layouts (zero candidates, e.g. torn header) return false so the legacy
     * orphan path still applies; ambiguous layouts naming PUMPKIN return true
     * (genuine Pumpkin bytes could verify under both V1 and Pumpkin rules).
     */
    public static boolean isPumpkinLinearFile(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            return false;
        }
        try {
            return detectLinearFormat(file)
                == net.linear.versions.RegionFormatDetector.DetectedFormat.PUMPKIN;
        } catch (IOException detectFail) {
            String msg = String.valueOf(detectFail.getMessage());
            return msg.contains("Ambiguous") && msg.contains("PUMPKIN");
        } catch (RuntimeException gateFail) {
            // Fail-closed: a throwing gate counts as Pumpkin (never auto-convert blindly).
            return true;
        }
    }

    /**
     * Canonical parse of a {@code .linear} source into slot tables.
     * Routing: detector first, then V1/V2/V3 via {@code LinearBase.getShared},
     * PUMPKIN via {@code PumpkinLinear} (never wire-byte alone; wire 2 alone
     * would route Pumpkin bucketed bodies into the V1 single-blob reader and
     * fail loudly without diagnosis — replaced here by detect-then-parse,
     * still fail-closed, now with detector diagnosis).
     */
    public static net.linear.versions.ChunkSlotTable parseLinearToSlots(Path file) throws IOException {
        CandidateScanner.RegionName coords = CandidateScanner.parseRegionName(file.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable region name: " + file);
        }
        return parseLinearToSlots(file, coords.x(), coords.z());
    }

    /**
     * Same as {@link #parseLinearToSlots(Path)} with explicit region coords
     * (for temp images like {@code *.pumpkin-tmpV1.linear} whose filenames do
     * not match {@code REGION_NAME} but whose coords are known from the source).
     */
    public static net.linear.versions.ChunkSlotTable parseLinearToSlots(Path file, int regionX, int regionZ)
            throws IOException {
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IOException("Cannot read source " + file + ": " + e.getMessage(), e);
        }
        ByteBuffer image = ByteBuffer.wrap(raw);
        net.linear.versions.RegionFormatDetector.DetectedFormat detected;
        try {
            detected = net.linear.versions.RegionFormatDetector.shared().detect(image, regionX, regionZ);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " in " + file, e);
        }
        net.linear.versions.Version version;
        switch (detected) {
            case V1:
                version = net.linear.bucket.LinearBase.getShared((byte) 2);
                break;
            case V2:
                version = net.linear.bucket.LinearBase.getShared((byte) 3);
                break;
            case V3:
                version = net.linear.bucket.LinearBase.getShared((byte) 4);
                break;
            case PUMPKIN:
                version = net.linear.versions.codecs.PumpkinLinear.shared();
                break;
            default:
                throw new IOException("Unsupported linear layout " + detected + " in " + file);
        }
        net.linear.versions.ChunkSlotTable out = new net.linear.versions.ChunkSlotTable();
        out.sourceName = file.toString();
        try {
            version.parse(image, out);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " in " + file, e);
        }
        return out;
    }

    /** Actual layout of a convertible file (extension + detector for {@code .linear}). */
    public static LinearRegionConverter.ConvertFormat actualFormatOf(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
            return LinearRegionConverter.ConvertFormat.B_LINEAR;
        }
        if (name.endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
            return LinearRegionConverter.ConvertFormat.MCA;
        }
        if (name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            net.linear.versions.RegionFormatDetector.DetectedFormat d = detectLinearFormat(file);
            switch (d) {
                case V1:
                    return LinearRegionConverter.ConvertFormat.V1;
                case V2:
                    return LinearRegionConverter.ConvertFormat.V2;
                case V3:
                    return LinearRegionConverter.ConvertFormat.V3;
                case PUMPKIN:
                    return LinearRegionConverter.ConvertFormat.PUMPKIN;
                default:
                    throw new IOException("Unsupported layout " + d + " in " + file);
            }
        }
        throw new IOException("Not a convertible region file: " + file);
    }

    /**
     * True when {@code actual} satisfies the {@code --from} filter
     * ({@code AUTO} accepts any; {@code LINEAR} accepts any linear-family
     * layout V1/V2/V3/PUMPKIN, so {@code --from linear} never silently
     * narrows to V1-only).
     */
    public static boolean matchesFrom(LinearRegionConverter.ConvertFormat actual,
            LinearRegionConverter.ConvertFormat from) {
        if (from == null || from == LinearRegionConverter.ConvertFormat.AUTO) {
            return true;
        }
        if (from == LinearRegionConverter.ConvertFormat.LINEAR) {
            return actual == LinearRegionConverter.ConvertFormat.V1
                || actual == LinearRegionConverter.ConvertFormat.V2
                || actual == LinearRegionConverter.ConvertFormat.V3
                || actual == LinearRegionConverter.ConvertFormat.PUMPKIN;
        }
        return actual == from;
    }

    /** Grid for explicit targets (0/whitelist-miss => format default; Pumpkin 2, V2/V3 8). */
    public static int resolveExplicitGrid(LinearRegionConverter.ConvertFormat to, int requestedGrid) {
        if (to == LinearRegionConverter.ConvertFormat.PUMPKIN) {
            return net.linear.probe.LinearBucketGrid.isValid(requestedGrid) ? requestedGrid : PUMPKIN_DEFAULT_GRID;
        }
        if (to == LinearRegionConverter.ConvertFormat.V2
                || to == LinearRegionConverter.ConvertFormat.V3) {
            return net.linear.probe.LinearBucketGrid.isValid(requestedGrid) ? requestedGrid : BUCKETED_DEFAULT_GRID;
        }
        return requestedGrid;
    }

    /** Maps explicit {@code to} to a staging implementation (V1/V2/V3 via LinearRegionFile). */
    public static net.linear.LinearImplementation implForExplicit(LinearRegionConverter.ConvertFormat to) {
        if (to == LinearRegionConverter.ConvertFormat.V2) {
            return net.linear.LinearImplementation.V2;
        }
        if (to == LinearRegionConverter.ConvertFormat.V3) {
            return net.linear.LinearImplementation.V3;
        }
        return net.linear.LinearImplementation.V1;
    }

    /**
     * Operator-facing actual conversion parameters per target (messaging only).
     * Pumpkin ignores {@code --level} (fixed 1, upstream ruzstd Fastest
     * equivalent) and defaults grid to 2; V2/V3 default grid to 8; other
     * targets use the clamped level and ignore grid.
     */
    public static String explicitParamsDetail(LinearRegionConverter.ConvertFormat to, int level,
            int requestedGrid) {
        int clamped = RegionFileFormat.clampCompressionLevel(level);
        if (to == LinearRegionConverter.ConvertFormat.PUMPKIN) {
            return "level ignored for pumpkin (fixed 1), grid " + resolveExplicitGrid(to, requestedGrid);
        }
        if (to == LinearRegionConverter.ConvertFormat.V2
                || to == LinearRegionConverter.ConvertFormat.V3) {
            return "level " + clamped + ", grid " + resolveExplicitGrid(to, requestedGrid);
        }
        return "level " + clamped;
    }

    /** Present-slot count of an already-parsed slot table (slots with {@code uncompressedSize > 0}). */
    public static int countPresentSlots(net.linear.versions.ChunkSlotTable slots) {
        int n = 0;
        for (int i = 0; i < net.linear.versions.ChunkSlotTable.SLOTS; i++) {
            if (slots.uncompressedSize[i] > 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * Asserts a file image is Pumpkin (detector==PUMPKIN) and fully parses
     * (decompress every bucket, hash chain verifies, footer validates).
     * Fail-closed with diagnosis; never falls through to another layout.
     */
    public static void assertPumpkinImage(Path file, int regionX, int regionZ) throws IOException {
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IOException("Cannot read pumpkin candidate " + file + ": " + e.getMessage(), e);
        }
        ByteBuffer image = ByteBuffer.wrap(raw);
        net.linear.versions.RegionFormatDetector.DetectedFormat detected;
        try {
            detected = net.linear.versions.RegionFormatDetector.shared().detect(image, regionX, regionZ);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " in " + file, e);
        }
        if (detected != net.linear.versions.RegionFormatDetector.DetectedFormat.PUMPKIN) {
            throw new IOException("Expected PUMPKIN layout, detected " + detected + " in " + file);
        }
        net.linear.versions.ChunkSlotTable probe = new net.linear.versions.ChunkSlotTable();
        probe.sourceName = file.toString();
        try {
            net.linear.versions.codecs.PumpkinLinear.shared().parse(image, probe);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " in " + file, e);
        }
        // Count parity is checked by the caller; full-bucket decompression happened in parse
        // (every bucket's zstd frame inflated, every xxhash verified fail-high).
    }

    /**
     * Validates a same-path staging file against its live source (count parity +
     * header sanity + full payload drain of the staging file). Returns detail.
     * Operates on already-written files (no staging writes here); opens go
     * through the facade's probed opener.
     */
    public static String validateStagingAgainstSource(Path source, Path staging) throws IOException {
        CandidateScanner.RegionName coords =
            CandidateScanner.parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable region name: " + source);
        }
        int sourceCount;
        try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, source.getParent(),
            RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
            sourceCount = ConversionValidator.countCopyableChunks(src, coords.x(), coords.z());
        } catch (Exception e) {
            throw new IOException("Cannot open/count source " + source + ": " + e.getMessage(), e);
        }
        if (!Files.isRegularFile(staging)) {
            throw new IOException("Staging missing: " + staging);
        }
        long size = Files.size(staging);
        if (size <= ConversionValidator.MIN_VALID_SIZE) {
            throw new IOException("Staging too small (" + size + " bytes): " + staging);
        }
        ConversionValidator.checkHeaderSanity(staging);
        try (AbstractRegionFile dst = LinearRegionConverter.openProbed(staging, staging.getParent(),
            RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
            int targetCount = ConversionValidator.countChunks(dst, coords.x(), coords.z());
            if (!ConversionValidator.countsMatch(sourceCount, targetCount)) {
                throw new IOException("Chunk-count mismatch source=" + sourceCount + " target=" + targetCount
                    + " for " + source.getFileName());
            }
            ConversionValidator.verifyTargetPayloads(dst, coords.x(), coords.z());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot open/count staging " + staging + ": " + e.getMessage(), e);
        }
        return "chunks=" + sourceCount + " size=" + size + "B header ok, payloads verified (pre-swap)";
    }

    /** Validates a Pumpkin staging/tmp file against its live source (pre-swap). */
    public static void validatePumpkinStagingAgainstSource(Path source, Path staging) throws IOException {
        CandidateScanner.RegionName coords =
            CandidateScanner.parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable region name: " + source);
        }
        // Pumpkin sources validate by successful canonical parse; other sources by copy-exact count.
        int sourceCount;
        String sourceName = source.getFileName().toString();
        if (sourceName.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            net.linear.versions.ChunkSlotTable probe = parseLinearToSlots(source);
            int n = 0;
            for (int i = 0; i < net.linear.versions.ChunkSlotTable.SLOTS; i++) {
                if (probe.uncompressedSize[i] > 0) {
                    n++;
                }
            }
            sourceCount = n;
        } else {
            try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, source.getParent(),
                RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                sourceCount = ConversionValidator.countCopyableChunks(src, coords.x(), coords.z());
            } catch (Exception e) {
                throw new IOException("Cannot open/count source " + source + ": " + e.getMessage(), e);
            }
        }
        // Pumpkin targets validate via detector==PUMPKIN + full payload parse
        // (decompress every bucket, count parity).
        assertPumpkinImage(staging, coords.x(), coords.z());
        try (AbstractRegionFile dst = LinearRegionConverter.openProbed(staging, staging.getParent(),
            RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
            int targetCount = ConversionValidator.countChunks(dst, coords.x(), coords.z());
            if (!ConversionValidator.countsMatch(sourceCount, targetCount)) {
                throw new IOException("Chunk-count mismatch source=" + sourceCount + " target=" + targetCount
                    + " for " + source.getFileName());
            }
            ConversionValidator.verifyTargetPayloads(dst, coords.x(), coords.z());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot open/count pumpkin staging " + staging + ": " + e.getMessage(), e);
        }
    }

    /**
     * Explicit validation (validate-then-delete preserved).
     * Pumpkin targets: detector==PUMPKIN + full payload parse (decompress every
     * bucket, count parity). Pumpkin sources: successful canonical parse.
     * All other pairs delegate to the legacy pair validators byte-identically.
     */
    public static String validateExplicitPair(Path source, Path target,
            LinearRegionConverter.ConvertFormat from, LinearRegionConverter.ConvertFormat to)
            throws IOException {
        CandidateScanner.RegionName coords =
            CandidateScanner.parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable region name: " + source);
        }
        if (to == LinearRegionConverter.ConvertFormat.PUMPKIN) {
            int sourceCount;
            String sourceName = source.getFileName().toString();
            boolean targetIsSource = source.equals(target);
            if (targetIsSource) {
                // Same-file swap already validated pre-swap; re-assert the swapped image.
                assertPumpkinImage(target, coords.x(), coords.z());
                try (AbstractRegionFile dst = LinearRegionConverter.openProbed(target, target.getParent(),
                    RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                    int n = ConversionValidator.countChunks(dst, coords.x(), coords.z());
                    ConversionValidator.verifyTargetPayloads(dst, coords.x(), coords.z());
                    long size = Files.size(target);
                    return "chunks=" + n + " size=" + size + "B pumpkin ok, payloads verified (swapped)";
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("Cannot open/count pumpkin target " + target + ": " + e.getMessage(), e);
                }
            }
            if (sourceName.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                net.linear.versions.ChunkSlotTable probe = parseLinearToSlots(source);
                int n = 0;
                for (int i = 0; i < net.linear.versions.ChunkSlotTable.SLOTS; i++) {
                    if (probe.uncompressedSize[i] > 0) {
                        n++;
                    }
                }
                sourceCount = n;
            } else {
                try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, source.getParent(),
                    RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                    sourceCount = ConversionValidator.countCopyableChunks(src, coords.x(), coords.z());
                } catch (Exception e) {
                    throw new IOException("Cannot open/count source " + source + ": " + e.getMessage(), e);
                }
            }
            if (!Files.isRegularFile(target)) {
                throw new IOException("Target missing: " + target);
            }
            long size = Files.size(target);
            if (size <= ConversionValidator.MIN_VALID_SIZE) {
                throw new IOException("Target too small (" + size + " bytes): " + target);
            }
            assertPumpkinImage(target, coords.x(), coords.z());
            try (AbstractRegionFile dst = LinearRegionConverter.openProbed(target, target.getParent(),
                RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                int targetCount = ConversionValidator.countChunks(dst, coords.x(), coords.z());
                if (!ConversionValidator.countsMatch(sourceCount, targetCount)) {
                    throw new IOException("Chunk-count mismatch source=" + sourceCount + " target=" + targetCount
                        + " for " + source.getFileName());
                }
                ConversionValidator.verifyTargetPayloads(dst, coords.x(), coords.z());
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Cannot open/count target " + target + ": " + e.getMessage(), e);
            }
            return "chunks=" + sourceCount + " size=" + size + "B pumpkin ok, payloads verified";
        }
        if (to == LinearRegionConverter.ConvertFormat.MCA) {
            // Explicit toward anvil uses the reverse validation (anvil size + parity +
            // full payload drain); Pumpkin sources validate by successful canonical parse
            // inside the reverse open (fail-closed with detector diagnosis on corrupt).
            return ConversionValidator.validateReversePair(source, target);
        }
        return ConversionValidator.validatePair(source, target);
    }

    /**
     * Per-file explicit conversion (any-to-any via slot tables for Pumpkin,
     * staging for V1/V2/V3/B_LINEAR, sink for MCA).
     *
     * <p>Same-path rule (FR7, one inode, never two files): when
     * {@code source.equals(target)} (both {@code .linear}, same coords, same
     * folder) this method stages to a tmp file, validates the tmp BEFORE any
     * swap, then atomic-swaps (never in-place overwrite of a live source, never
     * delete-after-swap). Different-path conversions (cross-extension shadows,
     * e.g. MCA source with a V1 file at the Pumpkin target path) stage + move,
     * then the caller validates + deletes the source (validate-then-delete
     * preserved) or escalates when the target path is occupied.</p>
     */
    public static void convertSingleFileExplicit(Path source, Path finalTarget, int compressionLevel,
            int requestedGrid, LinearRegionConverter.ConvertFormat from,
            LinearRegionConverter.ConvertFormat to) throws IOException {
        if (to == null || to == LinearRegionConverter.ConvertFormat.AUTO
                || to == LinearRegionConverter.ConvertFormat.LINEAR) {
            throw new IOException("Explicit target must be a concrete format, got " + to + " for " + source
                + " (--to linear means V1, normalized at parse time)");
        }
        CandidateScanner.RegionName coords =
            CandidateScanner.parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Not a convertible region file: " + source);
        }
        boolean samePath = source.equals(finalTarget);
        String sourceName = source.getFileName().toString();
        boolean sourceAnvil = sourceName.endsWith(RegionFileFormat.ANVIL_EXTENSION);
        boolean sourceLinear = sourceName.endsWith(RegionFileFormat.LINEAR_EXTENSION);
        boolean sourceBuffered = sourceName.endsWith(RegionFileFormat.B_LINEAR_EXTENSION);
        if (!sourceAnvil && !sourceLinear && !sourceBuffered) {
            throw new IOException("Not a convertible region file: " + source);
        }
        int level = RegionFileFormat.clampCompressionLevel(compressionLevel);

        // Truncated-source guards (identical minimums to the legacy path).
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
                // Detect-then-parse pre-check: fail-closed with diagnosis (never wire-byte alone).
                // Validates full structure (features terminate, table bounds, per-bucket
                // xxhash, footer, Pumpkin coords) without decompressing payloads.
                detectLinearFormat(source);
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
        // Explicit --from filter (fail-closed, never silently convert the wrong layout).
        // LINEAR matches any linear-family layout (V1/V2/V3/PUMPKIN); AUTO accepts any.
        // 0-byte files are trivially empty (materialize-empty path below) and skip the filter
        // (operator detail surfaced in the CONVERTED line by the caller).
        if (from != null && from != LinearRegionConverter.ConvertFormat.AUTO && sourceSize != 0L) {
            LinearRegionConverter.ConvertFormat actual;
            try {
                if (sourceLinear) {
                    actual = actualFormatOf(source);
                } else if (sourceAnvil) {
                    actual = LinearRegionConverter.ConvertFormat.MCA;
                } else {
                    actual = LinearRegionConverter.ConvertFormat.B_LINEAR;
                }
            } catch (IOException corrupt) {
                throw new IOException("Source layout does not satisfy --from " + from + ": "
                    + corrupt.getMessage() + ": " + source, corrupt);
            }
            boolean empty = false;
            try (AbstractRegionFile probe =
                    LinearRegionConverter.openProbed(source, source.getParent(), level)) {
                empty = ConversionValidator.countCopyableChunks(probe, coords.x(), coords.z()) == 0;
            } catch (Exception probeFail) {
                throw new IOException("Source layout does not satisfy --from " + from + ": "
                    + probeFail.getMessage() + ": " + source, probeFail);
            }
            if (!empty && !matchesFrom(actual, from)) {
                throw new IOException("Source is " + actual + ", job requires --from " + from + ": " + source);
            }
        }

        if (to == LinearRegionConverter.ConvertFormat.MCA) {
            // Explicit toward anvil reuses the reverse chunk-copy (legs envelop via sink).
            // Same-path cannot happen (different extensions); delegate to the legacy single-file
            // reverse (staging + plain move, validate-before-delete by the caller).
            if (samePath) {
                throw new IOException("Refusing same-path explicit convert (mixed-pair escalation, both kept): "
                    + source);
            }
            ChunkCopier.convertSingleFileReverse(source, finalTarget, level);
            return;
        }
        if (to == LinearRegionConverter.ConvertFormat.B_LINEAR) {
            if (samePath) {
                throw new IOException("Refusing same-path explicit convert (mixed-pair escalation, both kept): "
                    + source);
            }
            ChunkCopier.convertSingleFileToFormat(source, finalTarget, level, RegionFileFormat.B_LINEAR);
            return;
        }
        if (to == LinearRegionConverter.ConvertFormat.PUMPKIN) {
            convertSingleFileToPumpkin(source, finalTarget, level, requestedGrid, samePath);
            return;
        }
        // V1/V2/V3 targets via existing LinearRegionFile staging (impl + grid from job params).
        net.linear.LinearImplementation impl = implForExplicit(to);
        int grid = resolveExplicitGrid(to, requestedGrid);
        if (samePath) {
            convertSingleFileToLinearSamePath(source, level, impl, grid);
            return;
        }
        Path folder = source.getParent();
        Path stagingDir = CandidateScanner.resolveRecreateDirectory(folder);
        Files.createDirectories(stagingDir);
        Path stagingTarget = stagingDir.resolve(finalTarget.getFileName().toString());
        int sourceCount;
        try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, folder, level)) {
            sourceCount = ConversionValidator.countCopyableChunks(src, coords.x(), coords.z());
            if (sourceCount == 0) {
                ChunkCopier.materializeEmptyTarget(stagingTarget, level,
                    ChunkKey.of(coords.x() * 32, coords.z() * 32));
                // Re-stage with the requested impl/grid when it is not the default V1:
                // materializeEmptyTarget always writes V1, so rewrite buckets when needed.
                if (impl != net.linear.LinearImplementation.V1) {
                    restageAsImpl(stagingTarget, level, impl, grid);
                }
            } else {
                try (LinearRegionFile dst = new LinearRegionFile(stagingTarget, level, impl, grid)) {
                    ChunkCopier.copyChunkRange(src, dst, coords);
                    dst.flush();
                }
            }
        }
        if (!Files.isRegularFile(stagingTarget)) {
            throw new IOException("Staging file missing after explicit conversion: " + stagingTarget);
        }
        Files.createDirectories(finalTarget.getParent());
        Files.move(stagingTarget, finalTarget, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Restages a just-materialized empty V1 file as V2/V3 (empty targets have no chunks to copy). */
    public static void restageAsImpl(Path stagingTarget, int level, net.linear.LinearImplementation impl, int grid)
            throws IOException {
        Path tmp = stagingTarget.resolveSibling(stagingTarget.getFileName() + ".restage-tmp");
        Files.deleteIfExists(tmp);
        try (LinearRegionFile src = new LinearRegionFile(stagingTarget, level)) {
            CandidateScanner.RegionName coords =
                CandidateScanner.parseRegionName(stagingTarget.getFileName().toString());
            // Empty V1 has 0 chunks; create the empty target directly with the requested impl/grid.
            try (LinearRegionFile dst = new LinearRegionFile(tmp, level, impl, grid)) {
                // Seed/clear dance is unnecessary for an impl switch on empty: open + flush
                // with zero chunks would be a clean-no-op leaving no file, so seed one dummy.
                long dummy = coords == null ? ChunkKey.of(0, 0)
                    : ChunkKey.of(coords.x() * 32, coords.z() * 32);
                dst.write(dummy, ByteBuffer.wrap(new byte[]{0}));
                dst.flush();
                dst.clear(dummy);
                dst.flush();
            }
        }
        Files.move(tmp, stagingTarget, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Same-path {@code .linear} -&gt; {@code .linear} (V1/V2/V3) via staging +
     * validate-before-swap + atomic-swap (never in-place overwrite, never
     * delete-after-swap). The staging file is validated against the live source
     * BEFORE the swap; on failure the staging is dropped and the source stands.
     */
    public static void convertSingleFileToLinearSamePath(Path source, int level,
            net.linear.LinearImplementation impl, int grid) throws IOException {
        CandidateScanner.RegionName coords =
            CandidateScanner.parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Not a convertible region file: " + source);
        }
        Path folder = source.getParent();
        Path stagingDir = CandidateScanner.resolveRecreateDirectory(folder);
        Files.createDirectories(stagingDir);
        Path staging = stagingDir.resolve(source.getFileName().toString());
        int sourceCount;
        try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, folder, level)) {
            sourceCount = ConversionValidator.countCopyableChunks(src, coords.x(), coords.z());
            if (sourceCount == 0) {
                ChunkCopier.materializeEmptyTarget(staging, level,
                    ChunkKey.of(coords.x() * 32, coords.z() * 32));
                if (impl != net.linear.LinearImplementation.V1) {
                    restageAsImpl(staging, level, impl, grid);
                }
            } else {
                try (LinearRegionFile dst = new LinearRegionFile(staging, level, impl, grid)) {
                    ChunkCopier.copyChunkRange(src, dst, coords);
                    dst.flush();
                }
            }
        }
        // Validate the STAGING file against the live source BEFORE the swap.
        String detail = validateStagingAgainstSource(source, staging);
        // Layered dirty guards (collection-time skip is primary; this pre-swap
        // recheck is secondary): abort here when the folder dirtied during
        // conversion, so a live file is never swapped underneath its writer.
        // Abort =&gt; DEFERRED (source untouched, staging cleaned), never FAILED.
        String preSwapDeferred =
            LinearRegionConverter.preDeleteSkip(source, LinearRegionConverter.FORWARD_SKIP);
        if (preSwapDeferred != null) {
            try {
                Files.deleteIfExists(staging);
            } catch (Exception ignored) {
            }
            throw new LinearRegionConverter.DeferredBeforeSwapException(
                "deferred before swap: " + preSwapDeferred);
        }
        // Atomic swap (fallback plain move where the FS forbids atomics).
        try {
            Files.move(staging, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(staging, source, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Source -&gt; Pumpkin ({@code .linear} per FR7).
     *
     * <p>{@code .linear} sources use the canonical slot tables
     * (detect-then-parse via their Version, preserving stored timestamps with
     * Pumpkin u64-&gt;i32 clamping); {@code .mca}/{@code .b_linear} sources have
     * no Version, so they copy chunk-by-chunk into a temp V1 image first (the
     * existing byte-identical forward path) and that temp image supplies the
     * slots — no new envelope code, no payload reinterpretation.
     * Same-path ({@code source.equals(finalTarget)}) stages to tmp, validates
     * the tmp BEFORE any swap, then atomic-swaps (never in-place overwrite).</p>
     */
    public static void convertSingleFileToPumpkin(Path source, Path finalTarget, int level, int requestedGrid,
            boolean samePath) throws IOException {
        CandidateScanner.RegionName coords =
            CandidateScanner.parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Not a convertible region file: " + source);
        }
        String sourceName = source.getFileName().toString();
        boolean sourceLinear = sourceName.endsWith(RegionFileFormat.LINEAR_EXTENSION);
        net.linear.versions.ChunkSlotTable slots;
        if (Files.size(source) == 0L) {
            // 0-byte sources are trivially empty (same rule as the other
            // targets): materialize an empty Pumpkin image instead of
            // detector-failing and burning 3 retries on a certain FAILED.
            slots = new net.linear.versions.ChunkSlotTable();
        } else if (sourceLinear) {
            slots = parseLinearToSlots(source);
        } else {
            // .mca/.b_linear have no Version: reuse the byte-identical forward copy
            // into a temp V1 image, then parse that image for slots. The tmp name
            // does not match REGION_NAME, so parse with the source coords explicitly.
            Path folder = source.getParent();
            Path stagingDir = CandidateScanner.resolveRecreateDirectory(folder);
            Files.createDirectories(stagingDir);
            Path tmpV1 = stagingDir.resolve(source.getFileName() + ".pumpkin-tmpV1.linear");
            Files.deleteIfExists(tmpV1);
            try {
                ChunkCopier.convertSingleFileToFormat(source, tmpV1, level, RegionFileFormat.LINEAR);
                slots = parseLinearToSlots(tmpV1, coords.x(), coords.z());
            } finally {
                try {
                    Files.deleteIfExists(tmpV1);
                } catch (Exception ignored) {
                }
            }
        }
        if (samePath) {
            // Same-extension same-file: tmp beside the source, validate tmp vs source, then swap.
            Path tmp = source.resolveSibling(source.getFileName() + ".pumpkin-tmp");
            // Flush slots to the tmp path directly with tmp+fsync discipline.
            int grid = resolveExplicitGrid(LinearRegionConverter.ConvertFormat.PUMPKIN, requestedGrid);
            long newest = 0L;
            int[] snapSize = slots.uncompressedSize();
            int[] snapTime = slots.timestamps();
            for (int i = 0; i < net.linear.versions.ChunkSlotTable.SLOTS; i++) {
                if (snapSize[i] > 0) {
                    long ts = snapTime[i] & 0xFFFFFFFFL;
                    if (ts > newest) {
                        newest = ts;
                    }
                }
            }
            net.linear.versions.VersionWriteParams params = new net.linear.versions.VersionWriteParams(
                1, grid, coords.x(), coords.z(), newest, 0, 0);
            ByteBuffer image;
            try {
                image = net.linear.versions.codecs.PumpkinLinear.shared().flush(slots, params);
            } catch (IOException e) {
                throw new IOException(e.getMessage() + " (pumpkin flush)", e);
            }
            Files.createDirectories(tmp.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                net.linear.io.LinearDirectStreams.writeFully(channel, image);
                channel.force(true);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
                throw e;
            }
            validatePumpkinStagingAgainstSource(source, tmp);
            // Layered dirty guards (collection-time skip is primary; this pre-swap
            // recheck is secondary): abort when the folder dirtied during conversion.
            // Abort =&gt; DEFERRED (source untouched, tmp cleaned), never FAILED.
            String preSwapDeferred =
                LinearRegionConverter.preDeleteSkip(source, LinearRegionConverter.FORWARD_SKIP);
            if (preSwapDeferred != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
                throw new LinearRegionConverter.DeferredBeforeSwapException(
                    "deferred before swap: " + preSwapDeferred);
            }
            try {
                Files.move(tmp, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(tmp, source, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        // Different paths: flush straight to a staging file, move to the final target,
        // leave validation + source delete to the caller (validate-then-delete preserved).
        Path folder = source.getParent();
        Path stagingDir = CandidateScanner.resolveRecreateDirectory(folder);
        Files.createDirectories(stagingDir);
        Path staging = stagingDir.resolve(finalTarget.getFileName().toString());
        // Reuse the tmp+fsync discipline by flushing to the staging path via a temp sibling.
        Path stagingTmp = staging.resolveSibling(staging.getFileName() + ".tmp");
        int grid = resolveExplicitGrid(LinearRegionConverter.ConvertFormat.PUMPKIN, requestedGrid);
        long newest = 0L;
        int[] snapSize2 = slots.uncompressedSize();
        int[] snapTime2 = slots.timestamps();
        for (int i = 0; i < net.linear.versions.ChunkSlotTable.SLOTS; i++) {
            if (snapSize2[i] > 0) {
                long ts = snapTime2[i] & 0xFFFFFFFFL;
                if (ts > newest) {
                    newest = ts;
                }
            }
        }
        net.linear.versions.VersionWriteParams params2 = new net.linear.versions.VersionWriteParams(
            1, grid, coords.x(), coords.z(), newest, 0, 0);
        ByteBuffer image2;
        try {
            image2 = net.linear.versions.codecs.PumpkinLinear.shared().flush(slots, params2);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " (pumpkin flush)", e);
        }
        try (FileChannel channel = FileChannel.open(stagingTmp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            net.linear.io.LinearDirectStreams.writeFully(channel, image2);
            channel.force(true);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(stagingTmp);
            } catch (Exception ignored) {
            }
            throw e;
        }
        try {
            Files.move(stagingTmp, staging, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(stagingTmp, staging, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Files.isRegularFile(staging)) {
            throw new IOException("Pumpkin staging missing after conversion: " + staging);
        }
        Files.createDirectories(finalTarget.getParent());
        // Same durability discipline as the same-path swap: fsync the staging
        // image before the final commit, then atomic move with plain-move fallback.
        // Open READ+WRITE (not READ-only: force() on a read-only channel throws
        // NonWritableChannelException and would fail the commit).
        try (FileChannel commitChannel = FileChannel.open(staging,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            commitChannel.force(true);
        }
        try {
            Files.move(staging, finalTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(staging, finalTarget, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
