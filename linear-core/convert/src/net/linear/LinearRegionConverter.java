package net.linear;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import net.linear.convert.cleanup.ConversionAlerts;
import net.linear.convert.cleanup.ConversionCleanup;
import net.linear.convert.copy.ChunkCopier;
import net.linear.convert.pipeline.ConversionPipeline;
import net.linear.convert.routing.ExplicitConvertRouter;
import net.linear.convert.scan.CandidateScanner;
import net.linear.convert.validate.ConversionValidator;

/**
 * Public facade for startup region conversion: API types, storage seams and
 * thin delegates. Orchestration lives in {@link ConversionPipeline};
 * version-specific work in {@code ChunkCopier}, {@code ExplicitConvertRouter},
 * {@code CandidateScanner}, {@code ConversionValidator},
 * {@code ConversionCleanup} and {@code ConversionAlerts}.
 */
public final class LinearRegionConverter {

    private static final java.util.logging.Logger LOGGER =
        java.util.logging.Logger.getLogger(LinearRegionConverter.class.getName());

    /** Attempts per file before protection mode (first try + 2 retries). */
    public static final int MAX_ATTEMPTS = 3;

    /** Linear on-disk magic opening/closing a region file. */
    public static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;
    /** Oldest linear version accepted on read. */
    public static final byte VERSION_MIN = 1;
    /** Latest linear version accepted on read (V1=2, DivineMC V2=3, V3=4). */
    public static final byte VERSION_MAX = 4;
    /** Header + footer minimum: a valid file is larger than this. */
    public static final long MIN_VALID_SIZE = 32L + 8L;
    /** Minimum size of a valid {@code .mca} file (two 4 KiB sectors). */
    public static final long MIN_ANVIL_SIZE = 8192L;
    /** Minimum size of a valid {@code .b_linear} master. */
    public static final long MIN_BLINEAR_SIZE = 24L;

    /**
     * Explicit conversion format for Phase-3 any-to-any jobs (FR3).
     *
     * <p>Design choice (see FR3/FR4/FR7 + section 4): the legacy
     * {@code ConvertDirection} enum (6 extension-based values) cannot express
     * same-extension pairs ({@code .linear} vs {@code .linear}: V1/V2/V3/PUMPKIN
     * share one extension but are four different layouts). Extending that enum
     * with same-extension members would conflate routing (all map to the same
     * {@code targetFormat()==LINEAR}) and invite silent cross-layout overwrite.
     * Explicit jobs therefore carry {@code from/to} in this layout-level enum
     * and keep the legacy enum untouched for the 6 legacy pairs (byte-identical).
     * {@code AUTO} means detect (default for {@code --from}); {@code --to} must
     * be explicit (never {@code AUTO}). The string {@code "linear"} parses to
     * {@code LINEAR} (family wildcard for {@code --from}: matches any
     * {@code .linear} layout V1/V2/V3/PUMPKIN); for {@code --to} the parser
     * normalizes {@code LINEAR} to {@code V1} (shipped default).
     * {@code "blinear"} and {@code "b_linear"} both parse to {@code B_LINEAR}.
     */
    public enum ConvertFormat {
        MCA,
        V1,
        V2,
        V3,
        B_LINEAR,
        PUMPKIN,
        LINEAR,
        AUTO;

        /** Case-insensitive parse for {@code --from/--to} values. Unknown (or null) yields null. */
        public static ConvertFormat fromCli(String raw) {
            if (raw == null) {
                return null;
            }
            String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
            switch (t) {
                case "mca":
                    return MCA;
                case "v1":
                    return V1;
                case "linear":
                    return LINEAR;
                case "v2":
                    return V2;
                case "v3":
                    return V3;
                case "blinear":
                case "b_linear":
                    return B_LINEAR;
                case "pumpkin":
                    return PUMPKIN;
                case "auto":
                    return AUTO;
                default:
                    return null;
            }
        }

        /** On-disk extension for this format ({@code .linear} for V1/V2/V3/PUMPKIN/LINEAR per FR7). */
        public String extension() {
            switch (this) {
                case MCA:
                    return RegionFileFormat.ANVIL_EXTENSION;
                case B_LINEAR:
                    return RegionFileFormat.B_LINEAR_EXTENSION;
                case V1:
                case V2:
                case V3:
                case PUMPKIN:
                case LINEAR:
                default:
                    return RegionFileFormat.LINEAR_EXTENSION;
            }
        }

        /** True for the four layouts sharing {@code .linear} (FR7 same-extension hazard). */
        public static boolean isLinearExtension(ConvertFormat f) {
            return f == V1 || f == V2 || f == V3 || f == PUMPKIN || f == LINEAR;
        }
    }

    /** Per-file stage. Canonical state for the convert pipeline (see {@link ConversionPipeline}). */
    public enum FileState {
        CONVERTING,
        CONVERTED,
        VALIDATING,
        VALIDATED,
        DELETING,
        DELETED,
        FAILED
    }

    /** Terminal per-file outcome reported to {@link Listener}. */
    public enum Status {
        CONVERTED,
        VALIDATED,
        DELETED,
        FAILED,
        SKIPPED
    }

    /** Terminal per-file record. */
    public record FileResult(Path source, Path target, Status status, String detail) {}

    /** Stage/retry observer. Implementations must be thread-safe: callbacks arrive concurrently from pool threads. */
    public interface Listener {
        void onFile(FileResult r);
        void onRetry(Path source, int attempt, String reason);
    }

    /** Folder-level totals. {@code failures} holds one entry per terminally failed file. */
    public record ConversionSummary(
        int converted, int validated, int deleted, int failed, java.util.List<FileResult> failures) {}

    /** Thrown when one or more files exhaust all attempts. The caller (B2) halts for inspection. */
    public static class ConversionProtectionException extends Exception {
        private final ConversionSummary summary;

        public ConversionProtectionException(String message, ConversionSummary summary) {
            super(message);
            this.summary = summary;
        }

        public ConversionSummary getSummary() {
            return this.summary;
        }
    }

    private LinearRegionConverter() {}

    /** Delegates to {@link ConversionPipeline#runForwardConversion} with the LINEAR target. */
    public static ConversionSummary convertRegionFolder(Path regionFolder, int compressionLevel, Listener listener)
        throws ConversionProtectionException {
        return ConversionPipeline.runForwardConversion(regionFolder, compressionLevel, listener, true,
            RegionFileFormat.LINEAR);
    }

    /** Delegates to {@link ConversionPipeline#runForwardConversion} with the LINEAR target. */
    public static ConversionSummary convertRegionFolder(Path regionFolder, int compressionLevel, Listener listener,
        boolean haltsServer) throws ConversionProtectionException {
        return ConversionPipeline.runForwardConversion(regionFolder, compressionLevel, listener, haltsServer,
            RegionFileFormat.LINEAR);
    }

    /** Delegates to {@link ConversionPipeline#runForwardConversion}. */
    public static ConversionSummary convertRegionFolderToFormat(Path regionFolder, int compressionLevel,
        Listener listener, boolean haltsServer, RegionFileFormat targetFormat)
        throws ConversionProtectionException {
        return ConversionPipeline.runForwardConversion(regionFolder, compressionLevel, listener, haltsServer,
            targetFormat);
    }

    /** Delegates to {@link ConversionPipeline#runReverseConversion}. */
    public static ConversionSummary convertRegionFolderReverse(Path regionFolder, int compressionLevel,
        Listener listener) throws ConversionProtectionException {
        return ConversionPipeline.runReverseConversion(regionFolder, compressionLevel, listener, true);
    }

    /** Delegates to {@link ConversionPipeline#runReverseConversion}. */
    public static ConversionSummary convertRegionFolderReverse(Path regionFolder, int compressionLevel,
        Listener listener, boolean haltsServer) throws ConversionProtectionException {
        return ConversionPipeline.runReverseConversion(regionFolder, compressionLevel, listener, haltsServer);
    }

    /** Delegates to {@link ConversionPipeline#runExplicitConversion}. */
    public static ConversionSummary convertRegionFolderExplicit(Path regionFolder, int compressionLevel,
            int requestedGrid, Listener listener, boolean haltsServer, ConvertFormat from, ConvertFormat to)
            throws ConversionProtectionException {
        return ConversionPipeline.runExplicitConversion(regionFolder, compressionLevel, requestedGrid, listener,
            haltsServer, from, to);
    }

    /** Delegates to {@link ConversionPipeline#safeOnFile} (never reclassifies a file). */
    public static void safeOnFile(Listener events, FileResult r) {
        ConversionPipeline.safeOnFile(events, r);
    }

    /** Delegates to {@link ConversionPipeline#preDeleteSkip}. */
    public static String preDeleteSkip(Path source, java.util.function.Predicate<Path> skip) {
        return ConversionPipeline.preDeleteSkip(source, skip);
    }

    /** Compat alias for {@link ConversionPipeline.DeferredBeforeSwapException}. */
    @Deprecated
    public static final class DeferredBeforeSwapException extends ConversionPipeline.DeferredBeforeSwapException {
        public DeferredBeforeSwapException(String detail) {
            super(detail);
        }
    }

    /** Storage identity seam (see {@link ConversionPipeline} for orchestration). */
    public interface RegionOpener {
        AbstractRegionFile open(Path file, Path folder, boolean sync, RegionFileFormat format, int level)
            throws IOException;
    }

    private static volatile RegionOpener OPENER = (file, folder, sync, format, level) -> {
        RegionFileFormat effective = format;
        if (effective == null) {
            String name = file.getFileName().toString();
            if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                effective = RegionFileFormat.B_LINEAR;
            } else if (name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                effective = RegionFileFormat.LINEAR;
            } else {
                throw new IOException("No RegionOpener registered for non-linear open: " + file);
            }
        }
        if (effective == RegionFileFormat.B_LINEAR) {
            return new BufferedLinearRegionFile(file, level);
        }
        if (effective == RegionFileFormat.LINEAR) {
            return new LinearRegionFile(file, level);
        }
        throw new IOException("No RegionOpener registered for non-linear open: " + file);
    };

    /** Adapter wiring: NMS-backed storage opens (registered once at startup). */
    public static void linear$setRegionOpener(RegionOpener opener) {
        OPENER = java.util.Objects.requireNonNull(opener, "opener");
    }

    public static AbstractRegionFile openProbed(Path file, Path folder, int level) throws IOException {
        return OPENER.open(file, folder, false, null, level);
    }

    /** Write-side seam for the reverse ({@code .linear} -&gt; {@code .mca}) pipeline. */
    public interface ChunkSink extends AutoCloseable {
        void write(long chunk, ByteBuffer data) throws IOException;
        void flush() throws IOException;
        @Override
        void close() throws IOException;
    }

    /** Opens a {@link ChunkSink} for {@code file} (usually a staging target). */
    @FunctionalInterface
    public interface ChunkSinkOpener {
        ChunkSink open(Path file, Path folder, int level) throws IOException;
    }

    private static volatile ChunkSinkOpener SINK_OPENER = (file, folder, level) -> {
        throw new IOException("no ChunkSinkOpener registered for " + file);
    };

    /** Adapter wiring: NMS-backed anvil writer (registered once at startup). */
    public static void linear$setChunkSinkOpener(ChunkSinkOpener opener) {
        SINK_OPENER = java.util.Objects.requireNonNull(opener, "opener");
    }

    /** Write-side open routed through the volatile sink seam (stays in the facade). */
    public static ChunkSink openSink(Path file, Path folder, int level) throws IOException {
        return SINK_OPENER.open(file, folder, level);
    }

    public static volatile java.util.function.Predicate<Path> REVERSE_SKIP = p -> false;

    /** Reverse skip seam: files testing {@code true} are reported SKIPPED and never queued. */
    public static void linear$setReverseSkipPredicate(java.util.function.Predicate<Path> predicate) {
        REVERSE_SKIP = java.util.Objects.requireNonNull(predicate, "predicate");
    }

    public static volatile java.util.function.Predicate<Path> FORWARD_SKIP = p -> false;

    /** Forward skip seam (parity with the reverse seam): {@code .mca} files testing {@code true} defer. */
    public static void linear$setForwardSkipPredicate(java.util.function.Predicate<Path> predicate) {
        FORWARD_SKIP = java.util.Objects.requireNonNull(predicate, "predicate");
    }

    /** Delegates to {@link ChunkCopier#convertSingleFile}. */
    public static void convertSingleFile(Path source, Path finalTarget, int compressionLevel) throws IOException {
        ChunkCopier.convertSingleFile(source, finalTarget, compressionLevel);
    }

    /** Delegates to {@link ChunkCopier#convertSingleFileToFormat}. */
    public static void convertSingleFileToFormat(Path source, Path finalTarget, int compressionLevel,
            RegionFileFormat targetFormat) throws IOException {
        ChunkCopier.convertSingleFileToFormat(source, finalTarget, compressionLevel, targetFormat);
    }

    /** Delegates to {@link ChunkCopier#convertSingleFileReverse}. */
    public static void convertSingleFileReverse(Path source, Path finalTarget, int compressionLevel) throws IOException {
        ChunkCopier.convertSingleFileReverse(source, finalTarget, compressionLevel);
    }

    /** Delegates to {@link ChunkCopier#materializeEmptyMcaTarget}. */
    public static void materializeEmptyMcaTarget(Path stagingTarget, int compressionLevel) throws IOException {
        ChunkCopier.materializeEmptyMcaTarget(stagingTarget, compressionLevel);
    }

    /** Delegates to {@link ChunkCopier#materializeEmptyTarget}. */
    public static void materializeEmptyTarget(Path stagingTarget, int compressionLevel, long dummyKey) throws IOException {
        ChunkCopier.materializeEmptyTarget(stagingTarget, compressionLevel, dummyKey);
    }

    /** Delegates to {@link ChunkCopier#materializeEmptyBufferedTarget}. */
    public static void materializeEmptyBufferedTarget(Path stagingTarget, int compressionLevel, long dummyKey)
            throws IOException {
        ChunkCopier.materializeEmptyBufferedTarget(stagingTarget, compressionLevel, dummyKey);
    }

    /** Delegates to {@link ChunkCopier#copyChunkRange}. */
    public static int copyChunkRange(AbstractRegionFile src, AbstractRegionFile dst, RegionName coords) throws IOException {
        return ChunkCopier.copyChunkRange(src, dst,
            new CandidateScanner.RegionName(coords.x(), coords.z()));
    }

    /** Delegates to {@link ConversionValidator#validatePair}. */
    public static String validatePair(Path source, Path target) throws IOException {
        return ConversionValidator.validatePair(source, target);
    }

    /** Delegates to {@link ConversionValidator#validateReversePair}. */
    public static String validateReversePair(Path source, Path target) throws IOException {
        return ConversionValidator.validateReversePair(source, target);
    }

    /** Delegates to {@link ConversionValidator#checkHeaderSanity}. */
    public static void checkHeaderSanity(Path target) throws IOException {
        ConversionValidator.checkHeaderSanity(target);
    }

    /** Delegates to {@link ConversionValidator#checkBufferedHeaderSanity}. */
    public static void checkBufferedHeaderSanity(Path target) throws IOException {
        ConversionValidator.checkBufferedHeaderSanity(target);
    }

    /** Delegates to {@link ConversionValidator#countChunks}. */
    public static int countChunks(AbstractRegionFile file, int regionX, int regionZ) {
        return ConversionValidator.countChunks(file, regionX, regionZ);
    }

    /** Delegates to {@link ConversionValidator#countCopyableChunks}. */
    public static int countCopyableChunks(AbstractRegionFile src, int regionX, int regionZ) throws IOException {
        return ConversionValidator.countCopyableChunks(src, regionX, regionZ);
    }

    /** Delegates to {@link ConversionValidator#verifyTargetPayloads}. */
    public static void verifyTargetPayloads(AbstractRegionFile dst, int regionX, int regionZ) throws IOException {
        ConversionValidator.verifyTargetPayloads(dst, regionX, regionZ);
    }

    /** Delegates to {@link ConversionValidator#countsMatch}. */
    public static boolean countsMatch(int sourceCount, int targetCount) {
        return ConversionValidator.countsMatch(sourceCount, targetCount);
    }

    /** Delegates to {@link ConversionPipeline#shouldRetry}. */
    public static boolean shouldRetry(int completedAttempts) {
        return ConversionPipeline.shouldRetry(completedAttempts);
    }

    /** Delegates to {@link ConversionPipeline#transition}. */
    public static FileState transition(FileState current, boolean stageSuccess) {
        return ConversionPipeline.transition(current, stageSuccess);
    }

    /** Delegates to {@link ConversionCleanup#cleanAttemptArtifacts}. */
    public static void cleanAttemptArtifacts(Path source, Path target) {
        ConversionCleanup.cleanAttemptArtifacts(source, target);
    }

    /** Delegates to {@link ConversionCleanup#pruneFinishedBufferedTarget}. */
    public static void pruneFinishedBufferedTarget(Path target) {
        ConversionCleanup.pruneFinishedBufferedTarget(target);
    }

    /** Delegates to {@link ConversionCleanup#deleteBufferedSidecars}. */
    public static void deleteBufferedSidecars(Path master) {
        ConversionCleanup.deleteBufferedSidecars(master);
    }

    /** Delegates to {@link ConversionCleanup#cleanRecreateDirectory}. */
    public static int cleanRecreateDirectory(Path regionFolder) {
        return ConversionCleanup.cleanRecreateDirectory(regionFolder);
    }

    /** Delegates to {@link CandidateScanner#collectCandidates}. */
    public static List<Path> collectCandidates(Path regionFolder, Listener events, List<FileResult> preFailures) {
        return CandidateScanner.collectCandidates(regionFolder, events, preFailures);
    }

    /** Delegates to {@link CandidateScanner#collectForwardCandidates}. */
    public static List<Path> collectForwardCandidates(Path regionFolder, Listener events, List<FileResult> preFailures,
            RegionFileFormat targetFormat) {
        return CandidateScanner.collectForwardCandidates(regionFolder, events, preFailures, targetFormat);
    }

    /** Delegates to {@link CandidateScanner#collectReverseCandidates}. */
    public static List<Path> collectReverseCandidates(Path regionFolder, Listener events, List<FileResult> preDone) {
        return CandidateScanner.collectReverseCandidates(regionFolder, events, preDone);
    }

    /** Delegates to {@link CandidateScanner#collectExplicitCandidates}. */
    public static List<CandidateScanner.ExplicitCandidate> collectExplicitCandidates(Path regionFolder, Listener events,
            List<FileResult> preFailures, ConvertFormat from, ConvertFormat to) {
        return CandidateScanner.collectExplicitCandidates(regionFolder, events, preFailures, from, to);
    }

    /** Delegates to {@link CandidateScanner#targetForSource}. */
    public static Path targetForSource(Path source) {
        return CandidateScanner.targetForSource(source);
    }

    /** Delegates to {@link CandidateScanner#targetForSource}. */
    public static Path targetForSource(Path source, RegionFileFormat targetFormat) {
        return CandidateScanner.targetForSource(source, targetFormat);
    }

    /** Delegates to {@link CandidateScanner#targetForSourceReverse}. */
    public static Path targetForSourceReverse(Path source) {
        return CandidateScanner.targetForSourceReverse(source);
    }

    /** Delegates to {@link CandidateScanner#targetForSourceExplicit}. */
    public static Path targetForSourceExplicit(Path source, ConvertFormat to) {
        return CandidateScanner.targetForSourceExplicit(source, to);
    }

    /** Delegates to {@link CandidateScanner#resolveRecreateDirectory}. */
    public static Path resolveRecreateDirectory(Path directoryPath) {
        return CandidateScanner.resolveRecreateDirectory(directoryPath);
    }

    /** Delegates to {@link ConversionAlerts#describePairExtensions}. */
    public static String describePairExtensions(java.util.List<FileResult> failures) {
        return ConversionAlerts.describePairExtensions(failures);
    }

    /** Delegates to {@link ConversionAlerts#extensionOf}. */
    private static String extensionOf(Path file) {
        return ConversionAlerts.extensionOf(file);
    }

    private record RegionName(int x, int z) {}

    /** Delegates to {@link CandidateScanner#parseRegionName}. */
    public static RegionName parseRegionName(String fileName) {
        CandidateScanner.RegionName r = CandidateScanner.parseRegionName(fileName);
        return r == null ? null : new RegionName(r.x(), r.z());
    }

    /** Delegates to {@link ConversionAlerts#logProtectionAlert} (no explicit remedy). */
    public static void logProtectionAlert(Path regionFolder, ConversionSummary summary, boolean haltsServer) {
        logProtectionAlert(regionFolder, summary, haltsServer, null);
    }

    /** Delegates to {@link ConversionAlerts#logProtectionAlert}. */
    public static void logProtectionAlert(Path regionFolder, ConversionSummary summary, boolean haltsServer,
            String explicitRemedy) {
        ConversionAlerts.logProtectionAlert(regionFolder, summary, haltsServer, explicitRemedy, LOGGER);
    }

    /** Delegates to {@link ConversionAlerts#logReverseProtectionAlert}. */
    public static void logReverseProtectionAlert(Path regionFolder, ConversionSummary summary, boolean haltsServer) {
        ConversionAlerts.logReverseProtectionAlert(regionFolder, summary, haltsServer, LOGGER);
    }

    /** Pumpkin default grid (upstream DEFAULT_GRID_SIZE) when the job omits grid. */
    public static final int PUMPKIN_DEFAULT_GRID = 2;
    /** V2/V3 default grid when the job omits grid. */
    public static final int BUCKETED_DEFAULT_GRID = 8;

    /** Delegates to {@link ExplicitConvertRouter#detectLinearFormat}. */
    public static net.linear.versions.RegionFormatDetector.DetectedFormat detectLinearFormat(Path file)
            throws IOException {
        return ExplicitConvertRouter.detectLinearFormat(file);
    }

    /** Delegates to {@link ExplicitConvertRouter#isPumpkinLinearFile}. */
    public static boolean isPumpkinLinearFile(Path file) {
        return ExplicitConvertRouter.isPumpkinLinearFile(file);
    }

    /** Delegates to {@link ExplicitConvertRouter#parseLinearToSlots(Path)}. */
    public static net.linear.versions.ChunkSlotTable parseLinearToSlots(Path file) throws IOException {
        return ExplicitConvertRouter.parseLinearToSlots(file);
    }

    /** Delegates to {@link ExplicitConvertRouter#parseLinearToSlots(Path, int, int)}. */
    public static net.linear.versions.ChunkSlotTable parseLinearToSlots(Path file, int regionX, int regionZ)
            throws IOException {
        return ExplicitConvertRouter.parseLinearToSlots(file, regionX, regionZ);
    }

    /** Delegates to {@link ExplicitConvertRouter#actualFormatOf}. */
    public static ConvertFormat actualFormatOf(Path file) throws IOException {
        return ExplicitConvertRouter.actualFormatOf(file);
    }

    /** Delegates to {@link ExplicitConvertRouter#matchesFrom}. */
    public static boolean matchesFrom(ConvertFormat actual, ConvertFormat from) {
        return ExplicitConvertRouter.matchesFrom(actual, from);
    }

    /** Delegates to {@link ExplicitConvertRouter#resolveExplicitGrid}. */
    public static int resolveExplicitGrid(ConvertFormat to, int requestedGrid) {
        return ExplicitConvertRouter.resolveExplicitGrid(to, requestedGrid);
    }

    /** Delegates to {@link ExplicitConvertRouter#implForExplicit}. */
    public static net.linear.LinearImplementation implForExplicit(ConvertFormat to) {
        return ExplicitConvertRouter.implForExplicit(to);
    }

    /** Delegates to {@link ExplicitConvertRouter#explicitParamsDetail}. */
    public static String explicitParamsDetail(ConvertFormat to, int level, int requestedGrid) {
        return ExplicitConvertRouter.explicitParamsDetail(to, level, requestedGrid);
    }

    /** Delegates to {@link ExplicitConvertRouter#convertSingleFileExplicit}. */
    public static void convertSingleFileExplicit(Path source, Path finalTarget, int compressionLevel,
            int requestedGrid, ConvertFormat from, ConvertFormat to) throws IOException {
        ExplicitConvertRouter.convertSingleFileExplicit(source, finalTarget, compressionLevel,
            requestedGrid, from, to);
    }

    /** Delegates to {@link ExplicitConvertRouter#restageAsImpl}. */
    public static void restageAsImpl(Path stagingTarget, int level, net.linear.LinearImplementation impl, int grid)
            throws IOException {
        ExplicitConvertRouter.restageAsImpl(stagingTarget, level, impl, grid);
    }

    /** Delegates to {@link ExplicitConvertRouter#convertSingleFileToLinearSamePath}. */
    public static void convertSingleFileToLinearSamePath(Path source, int level,
            net.linear.LinearImplementation impl, int grid) throws IOException {
        ExplicitConvertRouter.convertSingleFileToLinearSamePath(source, level, impl, grid);
    }

    /** Delegates to {@link ExplicitConvertRouter#validateStagingAgainstSource}. */
    public static String validateStagingAgainstSource(Path source, Path staging) throws IOException {
        return ExplicitConvertRouter.validateStagingAgainstSource(source, staging);
    }

    /** Delegates to {@link ExplicitConvertRouter#convertSingleFileToPumpkin}. */
    public static void convertSingleFileToPumpkin(Path source, Path finalTarget, int level, int requestedGrid,
            boolean samePath) throws IOException {
        ExplicitConvertRouter.convertSingleFileToPumpkin(source, finalTarget, level, requestedGrid, samePath);
    }

    /** Delegates to {@link ExplicitConvertRouter#validatePumpkinStagingAgainstSource}. */
    public static void validatePumpkinStagingAgainstSource(Path source, Path staging) throws IOException {
        ExplicitConvertRouter.validatePumpkinStagingAgainstSource(source, staging);
    }

    /** Delegates to {@link ExplicitConvertRouter#assertPumpkinImage}. */
    public static void assertPumpkinImage(Path file, int regionX, int regionZ) throws IOException {
        ExplicitConvertRouter.assertPumpkinImage(file, regionX, regionZ);
    }

    /** Delegates to {@link ExplicitConvertRouter#validateExplicitPair}. */
    public static String validateExplicitPair(Path source, Path target, ConvertFormat from, ConvertFormat to)
            throws IOException {
        return ExplicitConvertRouter.validateExplicitPair(source, target, from, to);
    }
}
