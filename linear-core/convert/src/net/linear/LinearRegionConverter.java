package net.linear;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parallel convert-validate-delete pipeline for startup region conversion.
 *
 * <p>Per chunk-file three stages, in strict per-file order:</p>
 * <ol>
 *   <li>CONVERSION: {@code r.X.Z.mca} is copied chunk-by-chunk into a staging
 *       file under the sibling {@code new_<folder>} directory, flushed, then
 *       moved to the final {@code r.X.Z.linear} beside the source.</li>
 *   <li>VALIDATION: the staged-then-moved target is checked (existence, size,
 *       chunk-count parity, header sanity, full payload verification)
 *       BEFORE the source is touched.</li>
 *   <li>DELETION: only after VALIDATED, the source {@code .mca} is deleted.</li>
 * </ol>
 *
 * <p>Parallelism is across files: a bounded pool runs one task per file, so
 * different files are simultaneously in different stages. Per-file order is
 * enforced by an explicit state machine; a file is never deleted unvalidated.</p>
 *
 * <p>B2 convergence point: B2 exposes folder conversion on
 * {@code RegionStorageUpgrader} as
 * {@code convertFolderToLinear(Path, int, Listener)} and that method is a
 * thin hop delegating straight to
 * {@link #convertRegionFolder} (verified against the B2 working tree: level
 * clamped, then direct delegation, no
 * second copy of the file logic). This file therefore owns the single copy of
 * the conversion implementation (inline, using the same primitives as the
 * recreate path: {@code RegionOpener} probed opens for reads, write to
 * the sibling {@code new_} directory, explicit flush, extension-aware plain
 * move). No circular call exists: this file never calls back into B2. If B2's
 * signature drifts (different owner class, extra params, checked exceptions),
 * do NOT adapt silently: keep this path and document the drift in the failure
 * detail so operators see it in the protection alert.</p>
 */
public final class LinearRegionConverter {

    private static final java.util.logging.Logger LOGGER =
        java.util.logging.Logger.getLogger(LinearRegionConverter.class.getName());

    /** Attempts per file before protection mode (first try + 2 retries). */
    public static final int MAX_ATTEMPTS = 3;

    /** Linear on-disk magic opening/closing a region file. */
    static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;
    /** Oldest linear version accepted on read. */
    static final byte VERSION_MIN = 1;
    /** Latest linear version accepted on read (V1=2, DivineMC V2=3, V3=4). */
    static final byte VERSION_MAX = 4;
    /** Header + footer minimum: a valid file is larger than this. */
    static final long MIN_VALID_SIZE = 32L + 8L;
    /**
     * Minimum size of a valid {@code .mca} file: two 4 KiB sectors (offset
     * table + timestamp table). Vanilla pads every region file to at least
     * this on open, so a smaller file was never written by vanilla and is
     * treated as truncated/corrupt (FAILED path, never delete).
     */
    static final long MIN_ANVIL_SIZE = 8192L;

    /** Minimum size of a valid {@code .b_linear} master: 18 B header + a small frame. */
    static final long MIN_BLINEAR_SIZE = 24L;

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

        /**
         * Case-insensitive parse for {@code --from/--to} values
         * ({@code mca|v1|v2|v3|blinear|b_linear|pumpkin|linear|auto}).
         * {@code "linear"} yields {@code LINEAR} (family wildcard for
         * {@code --from}; the command parser normalizes it to {@code V1}
         * for {@code --to}). Unknown (or {@code null}) yields {@code null}
         * so the caller can report usage (never silently default across formats).
         */
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

    private static final Pattern REGION_NAME =
        Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(mca|linear|b_linear)$");
    private static final String NEW_DIRECTORY_PREFIX = "new_";

    /** Per-file stage. Package-visible for unit tests (no bootstrap needed). */
    enum FileState {
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

    /**
     * Converts every {@code r.*.mca} in {@code regionFolder} to
     * {@code r.*.linear} with validate-before-delete, retry-3 and
     * crash-resume. Blocks the calling (boot) thread until every file settles.
     *
     * <p>If {@code regionFolder} cannot be listed (missing or unreadable
     * directory), candidate collection yields zero candidates and this method
     * returns an all-zeros summary. No behavior change, no failure: nothing
     * to convert, nothing fails.</p>
     *
     * @param regionFolder folder holding {@code r.X.Z.mca} files (e.g. {@code region/})
     * @param compressionLevel zstd level for new {@code .linear} files (clamped 1..22)
     * @param listener may be null (no-op); otherwise receives per-stage and retry callbacks
     * @return summary when every file converts, validates and is deleted
     * @throws ConversionProtectionException when any file fails 3 attempts (or a shadow-pair
     *     escalation applies); sources are left untouched for inspection in that case
     * @throws NullPointerException when {@code regionFolder} is null
     */
    public static ConversionSummary convertRegionFolder(Path regionFolder, int compressionLevel, Listener listener)
        throws ConversionProtectionException {
        return convertRegionFolder(regionFolder, compressionLevel, listener, true);
    }

    /**
     * Same as {@link #convertRegionFolder(Path, int, Listener)}, with explicit
     * halt semantics for the protection path: boot callers pass {@code true}
     * (the caller halts the server on the exception); live job runners pass
     * {@code false} (the caller fails the job and keeps running).
     */
    public static ConversionSummary convertRegionFolder(Path regionFolder, int compressionLevel, Listener listener,
        boolean haltsServer) throws ConversionProtectionException {
        return runForwardConversion(regionFolder, compressionLevel, listener, haltsServer,
            RegionFileFormat.LINEAR);
    }

    /**
     * Forward conversion toward {@code targetFormat} ({@code LINEAR} or
     * {@code B_LINEAR}): every convertible non-target file in
     * {@code regionFolder} is copied chunk-by-chunk to the target format
     * (staging + move, validate-before-delete, retry-3, crash-resume).
     * Same contract as {@link #convertRegionFolder(Path, int, Listener, boolean)}.
     */
    public static ConversionSummary convertRegionFolderToFormat(Path regionFolder, int compressionLevel,
        Listener listener, boolean haltsServer, RegionFileFormat targetFormat)
        throws ConversionProtectionException {
        if (targetFormat != RegionFileFormat.LINEAR && targetFormat != RegionFileFormat.B_LINEAR) {
            throw new IllegalArgumentException("forward target must be LINEAR or B_LINEAR, got " + targetFormat);
        }
        return runForwardConversion(regionFolder, compressionLevel, listener, haltsServer, targetFormat);
    }

    private static ConversionSummary runForwardConversion(Path regionFolder, int compressionLevel, Listener listener,
        boolean haltsServer, RegionFileFormat targetFormat) throws ConversionProtectionException {
        Objects.requireNonNull(regionFolder, "regionFolder");
        final Listener events = listener != null ? listener : new Listener() {
            @Override
            public void onFile(FileResult r) {}
            @Override
            public void onRetry(Path source, int attempt, String reason) {}
        };
        final int level = RegionFileFormat.clampCompressionLevel(compressionLevel);

        // Crash-resume preamble, before any pool work.
        int cleaned = cleanRecreateDirectory(regionFolder);
        // Staging writes self-report to the coordinator (like every core
        // write path), so drop any stale staging registration: a ghost
        // dirty entry would trip future quiesce gates ("save-all and retry"
        // forever) long after its files are gone.
        LinearFlushCoordinator.evict(resolveRecreateDirectory(regionFolder));
        LOGGER.info("Linear conversion: cleaned " + cleaned + " orphan file(s) from new_"
            + " staging beside " + regionFolder);

        List<FileResult> preFailures = new ArrayList<>();
        List<Path> candidates = collectForwardCandidates(regionFolder, events, preFailures, targetFormat);

        if (candidates.isEmpty() && preFailures.isEmpty()) {
            return new ConversionSummary(0, 0, 0, 0, List.of());
        }

        // Bounded pool: min(availableProcessors, 4). Boot-time path with no
        // scheduler yet, so a private fixed pool is used instead of the shared
        // flush pool (which may not exist this early and serves a different
        // durability contract). Four threads cap the concurrent open-file and
        // zstd working sets during startup IO; more threads add seeks, not
        // throughput. Daemon threads so a halted server never hangs on them;
        // the boot thread always joins before returning (no fire-and-forget).
        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
        AtomicInteger nameSeq = new AtomicInteger(0);
        ExecutorService pool = new ThreadPoolExecutor(
            threads,
            threads,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r);
                t.setName("linear-convert-" + nameSeq.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        // File tasks never submit nested pool work and never join each other;
        // the only join edge is boot-thread -> futures, so the join cannot deadlock.

        List<Future<TaskOutcome>> futures = new ArrayList<>(candidates.size());
        for (Path source : candidates) {
            Path target = targetForSource(source, targetFormat);
            futures.add(pool.submit(new FileTask(source, target, level, events, targetFormat)));
        }
        pool.shutdown();

        List<FileResult> terminal = new ArrayList<>(preFailures);
        int converted = 0;
        int validated = 0;
        int deleted = 0;
        for (int i = 0; i < futures.size(); i++) {
            Future<TaskOutcome> f = futures.get(i);
            TaskOutcome out;
            try {
                out = f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Path lost = candidates.get(i);
                terminal.add(new FileResult(lost, targetForSource(lost, targetFormat), Status.FAILED,
                    "interrupted while joining worker: " + String.valueOf(e.getMessage())));
                continue;
            } catch (Exception e) {
                // Should not happen (tasks catch everything), but never lose a file silently:
                // record it FAILED so it enters protection instead of vanishing.
                Path lost = candidates.get(i);
                terminal.add(new FileResult(lost, targetForSource(lost, targetFormat), Status.FAILED,
                    "worker future failed: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())));
                continue;
            }
            terminal.add(out.result());
            if (out.result().status() == Status.DELETED) {
                converted++;
                validated++;
                deleted++;
            }
        }

        try {
            if (!pool.awaitTermination(1L, TimeUnit.HOURS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }

        // Drop this run's staging registrations: per-file targets already
        // flushed explicitly, and lingering dirty entries would trip future
        // quiesce gates on a folder whose files are gone or moved.
        LinearFlushCoordinator.evict(resolveRecreateDirectory(regionFolder));

        List<FileResult> failures = new ArrayList<>();
        for (FileResult r : terminal) {
            if (r.status() == Status.FAILED) {
                failures.add(r);
            }
        }

        if (!failures.isEmpty()) {
            ConversionSummary summary =
                new ConversionSummary(converted, validated, deleted, failures.size(), List.copyOf(failures));
            logProtectionAlert(regionFolder, summary, haltsServer);
            throw new ConversionProtectionException(
                "Linear conversion halted with " + failures.size() + " failed file(s) in " + regionFolder, summary);
        }
        return new ConversionSummary(converted, validated, deleted, 0, List.of());
    }

    /**
     * Mirror-image reverse conversion: every {@code r.*.linear} in
     * {@code regionFolder} is copied chunk-by-chunk to {@code r.*.mca}
     * (staging + plain move, validate-before-delete, retry-3, crash-resume).
     * Blocks the calling thread until every file settles.
     *
     * <p>Source reads go through {@link #OPENER} (the LINEAR side); target
     * writes go through {@link #SINK_OPENER} (legs supply the NMS anvil
     * writer; raw NBT bytes pass through, legs envelop). Files testing true
     * on the reverse skip predicate are reported SKIPPED and never queued.</p>
     *
     * @param regionFolder folder holding {@code r.X.Z.linear} files
     * @param compressionLevel clamped 1..22, forwarded to the sink opener
     * @param listener may be null (no-op)
     * @return summary when every file converts, validates and is deleted
     * @throws ConversionProtectionException when any file fails 3 attempts
     *     (or a reverse shadow-pair escalation applies); sources are left
     *     untouched for inspection in that case
     * @throws NullPointerException when {@code regionFolder} is null
     */
    public static ConversionSummary convertRegionFolderReverse(Path regionFolder, int compressionLevel,
        Listener listener) throws ConversionProtectionException {
        return convertRegionFolderReverse(regionFolder, compressionLevel, listener, true);
    }

    /**
     * Same as {@link #convertRegionFolderReverse(Path, int, Listener)}, with explicit
     * halt semantics for the protection path: boot callers pass {@code true};
     * live job runners pass {@code false} (fail the job, keep running).
     */
    public static ConversionSummary convertRegionFolderReverse(Path regionFolder, int compressionLevel,
        Listener listener, boolean haltsServer) throws ConversionProtectionException {
        Objects.requireNonNull(regionFolder, "regionFolder");
        final Listener events = listener != null ? listener : new Listener() {
            @Override
            public void onFile(FileResult r) {}
            @Override
            public void onRetry(Path source, int attempt, String reason) {}
        };
        final int level = RegionFileFormat.clampCompressionLevel(compressionLevel);

        int cleaned = cleanRecreateDirectory(regionFolder);
        // Drop stale staging registrations (see forward preamble): ghost
        // dirty entries would trip future quiesce gates after their files
        // are gone.
        LinearFlushCoordinator.evict(resolveRecreateDirectory(regionFolder));
        LOGGER.info("Linear reverse conversion: cleaned " + cleaned + " orphan file(s) from new_"
            + " staging beside " + regionFolder);

        List<FileResult> preDone = new ArrayList<>();
        List<Path> candidates = collectReverseCandidates(regionFolder, events, preDone);

        if (candidates.isEmpty() && preDone.isEmpty()) {
            return new ConversionSummary(0, 0, 0, 0, List.of());
        }

        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
        AtomicInteger nameSeq = new AtomicInteger(0);
        ExecutorService pool = new ThreadPoolExecutor(
            threads,
            threads,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r);
                t.setName("linear-reverse-" + nameSeq.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

        List<Future<TaskOutcome>> futures = new ArrayList<>(candidates.size());
        for (Path source : candidates) {
            Path target = targetForSourceReverse(source);
            futures.add(pool.submit(new ReverseFileTask(source, target, level, events)));
        }
        pool.shutdown();

        List<FileResult> terminal = new ArrayList<>(preDone);
        int converted = 0;
        int validated = 0;
        int deleted = 0;
        for (FileResult r : preDone) {
            if (r.status() == Status.DELETED) {
                converted++;
                validated++;
                deleted++;
            }
        }
        for (int i = 0; i < futures.size(); i++) {
            Future<TaskOutcome> f = futures.get(i);
            TaskOutcome out;
            try {
                out = f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Path lost = candidates.get(i);
                terminal.add(new FileResult(lost, targetForSourceReverse(lost), Status.FAILED,
                    "interrupted while joining reverse worker: " + String.valueOf(e.getMessage())));
                continue;
            } catch (Exception e) {
                Path lost = candidates.get(i);
                terminal.add(new FileResult(lost, targetForSourceReverse(lost), Status.FAILED,
                    "reverse worker future failed: " + e.getClass().getSimpleName() + ": "
                        + String.valueOf(e.getMessage())));
                continue;
            }
            terminal.add(out.result());
            if (out.result().status() == Status.DELETED) {
                converted++;
                validated++;
                deleted++;
            }
        }

        try {
            if (!pool.awaitTermination(1L, TimeUnit.HOURS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }

        // Drop this run's staging registrations (see forward preamble):
        // lingering dirty entries would trip future quiesce gates.
        LinearFlushCoordinator.evict(resolveRecreateDirectory(regionFolder));

        List<FileResult> failures = new ArrayList<>();
        for (FileResult r : terminal) {
            if (r.status() == Status.FAILED) {
                failures.add(r);
            }
        }

        if (!failures.isEmpty()) {
            ConversionSummary summary =
                new ConversionSummary(converted, validated, deleted, failures.size(), List.copyOf(failures));
            logReverseProtectionAlert(regionFolder, summary, haltsServer);
            throw new ConversionProtectionException(
                "Linear reverse conversion halted with " + failures.size() + " failed file(s) in " + regionFolder,
                summary);
        }
        return new ConversionSummary(converted, validated, deleted, 0, List.of());
    }

    private static final class ReverseFileTask implements Callable<TaskOutcome> {
        private final Path source;
        private final Path target;
        private final int level;
        private final Listener events;

        ReverseFileTask(Path source, Path target, int level, Listener events) {
            this.source = source;
            this.target = target;
            this.level = level;
            this.events = events;
        }

        @Override
        public TaskOutcome call() {
            FileState state = FileState.CONVERTING;
            String lastError = "";
            int attempt = 0;
            while (shouldRetry(attempt)) {
                attempt++;
                boolean skippedConvert = false;
                try {
                    state = FileState.CONVERTING;
                    if (Files.isRegularFile(this.target)) {
                        try {
                            validateReversePair(this.source, this.target);
                            skippedConvert = true;
                        } catch (Exception notYetValid) {
                            skippedConvert = false;
                        }
                    }
                    if (!skippedConvert) {
                        convertSingleFileReverse(this.source, this.target, this.level);
                    }
                    state = transition(state, true);
                    safeOnFile(this.events, new FileResult(this.source, this.target, Status.CONVERTED,
                        "attempt " + attempt
                            + (skippedConvert ? " target already valid, skipped rewrite"
                                : " reverse conversion ok")));

                    state = FileState.VALIDATING;
                    String validation = validateReversePair(this.source, this.target);
                    state = transition(state, true);
                    safeOnFile(this.events, new FileResult(this.source, this.target, Status.VALIDATED,
                        "attempt " + attempt + " reverse validation ok: " + validation));

                    state = FileState.DELETING;
                    // TOCTOU adjacency: this recheck immediately precedes the
                    // source delete below with no intervening IO on the
                    // proceed path (see preDeleteSkip for the residual window
                    // and the shadow-pair backstop).
                    String deferred = preDeleteSkip(this.source, REVERSE_SKIP);
                    if (deferred != null) {
                        // Same defer-before-delete contract as the forward
                        // path, with the skipped-rewrite guard from below: a
                        // pre-existing valid target is someone else's committed
                        // file and must never be wiped (only this attempt's
                        // own staging output may go). Without our own output
                        // there is nothing to clean; either way the source
                        // stands and the re-run reconverts cleanly instead of
                        // tripping a shadow-pair protection halt.
                        if (!skippedConvert) {
                            cleanAttemptArtifacts(this.source, this.target);
                        }
                        FileResult skipped = new FileResult(this.source, this.target, Status.SKIPPED,
                            "attempt " + attempt + " deferred before delete: " + deferred);
                        safeOnFile(this.events, skipped);
                        return new TaskOutcome(skipped);
                    }
                    Files.deleteIfExists(this.source);
                    if (this.source.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                        // Buffered sources carry scratch siblings that must not
                        // linger: nothing will ever open them again and the next
                        // boot would mistake the swap for live state.
                        deleteBufferedSidecars(this.source);
                    }
                    state = transition(state, true);
                    FileResult done = new FileResult(this.source, this.target, Status.DELETED,
                        "reverse converted+validated+deleted on attempt " + attempt);
                    safeOnFile(this.events, done);
                    return new TaskOutcome(done);
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                    FileState reverseFailureStage = state;
                    state = FileState.FAILED;
                    // Skip the target wipe when the target was already valid:
                    // skipped rewrites (shadow-valid) and DELETING failures
                    // (convert+validate succeeded) retry only the source delete.
                    if (!skippedConvert && reverseFailureStage != FileState.DELETING) {
                        cleanAttemptArtifacts(this.source, this.target);
                    }
                    if (shouldRetry(attempt)) {
                        safeOnRetry(this.events, this.source, attempt, lastError);
                    }
                }
            }
            FileResult failed = new FileResult(this.source, this.target, Status.FAILED,
                "3 reverse attempts exhausted (" + state + "); last error: " + lastError);
            safeOnFile(this.events, failed);
            return new TaskOutcome(failed);
        }
    }

    /**
     * Copies all chunks from {@code source (.linear)} to
     * {@code finalTarget (.mca)} via the sibling {@code new_<folder>} staging
     * directory, then moves the staged file to its final name. Plain move (NOT
     * ATOMIC_MOVE) for parity with the forward path. Source reads go through
     * {@link #OPENER}; target writes go through {@link #SINK_OPENER} as raw
     * NBT bytes (legs envelop).
     */
    static void convertSingleFileReverse(Path source, Path finalTarget, int compressionLevel) throws IOException {
        RegionName coords = parseRegionName(source.getFileName().toString());
        String sourceName = source.getFileName().toString();
        boolean sourceLinear = sourceName.endsWith(RegionFileFormat.LINEAR_EXTENSION);
        boolean sourceBuffered = sourceName.endsWith(RegionFileFormat.B_LINEAR_EXTENSION);
        if (coords == null || (!sourceLinear && !sourceBuffered)) {
            throw new IOException("Not a convertible linear-family region file: " + source);
        }
        Path folder = source.getParent();
        Path stagingDir = resolveRecreateDirectory(folder);
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
        long reverseMin = sourceBuffered ? MIN_BLINEAR_SIZE : MIN_VALID_SIZE;
        if (reverseSourceSize != 0L && reverseSourceSize <= reverseMin) {
            throw new IOException("Reverse source too small to hold a valid linear header ("
                + reverseSourceSize + " bytes): " + source);
        }
        if (reverseSourceSize != 0L) {
            checkHeaderSanity(source);
        }

        int copied = 0;
        int sourceCount = 0;
        try (AbstractRegionFile src = openProbed(source, folder, level)) {
            sourceCount = countCopyableChunks(src, coords.x(), coords.z());
            if (sourceCount == 0) {
                materializeEmptyMcaTarget(stagingTarget, level);
            } else {
                try (ChunkSink dst = SINK_OPENER.open(stagingTarget, folder, level)) {
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
    static void materializeEmptyMcaTarget(Path stagingTarget, int compressionLevel) throws IOException {
        Files.deleteIfExists(stagingTarget);
        int level = RegionFileFormat.clampCompressionLevel(compressionLevel);
        Path folder = stagingTarget.getParent();
        if (folder == null) {
            folder = stagingTarget;
        }
        try (ChunkSink dst = SINK_OPENER.open(stagingTarget, folder, level)) {
            dst.flush();
        }
        if (!Files.isRegularFile(stagingTarget)) {
            throw new IOException("Empty-target reverse materialization left no file: " + stagingTarget);
        }
    }

    /**
     * Reverse validation predicate (existence + anvil size + count parity +
     * full payload verification).
     *
     * <p>Checks, in order: (a) source opens and counts copy-exact; (b) target
     * exists and is at least {@link #MIN_ANVIL_SIZE} (vanilla pads every
     * region file to two 4 KiB sectors on open, so a smaller file is
     * truncated/corrupt — this holds for 0-chunk targets too, which
     * materialize as a padded empty {@code .mca}); (c) chunk-count parity
     * between source and target, with {@code 0 == 0} VALID; (d) full drain of
     * every present target payload (torn middle with valid framing fails).</p>
     *
     * @return human-readable validation detail for logging
     */
    static String validateReversePair(Path source, Path target) throws IOException {
        RegionName coords = parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable reverse region name: " + source);
        }
        int sourceCount;
        int targetCount;
        try (AbstractRegionFile src = openProbed(source, source.getParent(),
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
        try (AbstractRegionFile dst = openProbed(target, target.getParent(),
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

    /**
     * Reverse crash-resume step (b): collect {@code .linear} candidates and
     * resolve shadow pairs.
     *
     * <p>Shadow policy (mirror of the forward path, reversed): a
     * {@code .linear + .mca} pair with the same coordinates means the anvil
     * target already exists. A pair that VALIDATES under the reverse checks
     * is NEVER auto-deleted: after a one-way upgrade both sides can hold
     * unique chunks at equal counts, and count-parity validation cannot prove
     * either side redundant. Valid-shadow pairs escalate to FAILED /
     * protection with NEITHER side deleted; the operator compares chunk
     * contents before touching either file. An INVALID shadow beside a
     * readable linear source is an orphan: drop the bad {@code .mca} and
     * requeue the {@code .linear} for a full convert (escalating instead when
     * the linear source itself is unreadable, so a corrupt source is never
     * destroyed — FAILED, both kept). Linear-only files queue for a full
     * convert; {@code .mca}-only files are already anvil and report SKIPPED.</p>
     */
    static List<Path> collectReverseCandidates(Path regionFolder, Listener events, List<FileResult> preDone) {
        Map<String, Path> mca = new HashMap<>();
        Map<String, List<Path>> lin = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                Matcher m = REGION_NAME.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                String key = m.group(1) + "," + m.group(2);
                String ext = m.group(3);
                if ("mca".equals(ext)) {
                    mca.put(key, p);
                } else {
                    lin.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Linear reverse conversion: cannot list " + regionFolder + ": " + e.getMessage());
            return List.of();
        }

        List<Path> out = new ArrayList<>();
        for (Map.Entry<String, List<Path>> e : lin.entrySet()) {
            List<Path> sameCoords = e.getValue();
            if (sameCoords.size() > 1) {
                // Ambiguous same-coordinates linear sources (e.g.
                // r.0.0.linear beside r.0.0.b_linear): both map onto one
                // .mca target, so queueing either silently drops the other.
                // Escalate with everything kept; the operator keeps one
                // file, moves the other aside, and re-runs.
                sameCoords.sort(Comparator.comparing(p -> p.getFileName().toString()));
                preDone.add(new FileResult(sameCoords.get(0), targetForSourceReverse(sameCoords.get(0)),
                    Status.FAILED,
                    "ambiguous same-coordinates reverse sources ("
                        + sameCoords.stream().map(p -> p.getFileName().toString())
                            .reduce((a, b) -> a + ", " + b).orElse("?")
                        + "); all files left untouched: keep one, move the rest aside, re-run"));
                LOGGER.severe("Linear reverse conversion: ambiguous same-coordinates sources left untouched: "
                    + sameCoords);
                continue;
            }
            Path source = sameCoords.get(0);
            Path shadow = mca.get(e.getKey());
            Path targetMca = shadow != null ? shadow : targetForSourceReverse(source);
            boolean skip;
            try {
                skip = REVERSE_SKIP.test(source);
            } catch (RuntimeException predicateFailed) {
                // Fail-closed: a throwing skip predicate never queues the file.
                // Report SKIPPED so the operator re-runs after the transient
                // predicate failure clears, instead of converting a possibly
                // loaded/dirty file.
                safeOnFile(events, new FileResult(source, targetMca, Status.SKIPPED,
                    "skip check failed, deferred: " + String.valueOf(predicateFailed.getMessage())));
                continue;
            }
            if (skip) {
                safeOnFile(events, new FileResult(source, targetMca, Status.SKIPPED,
                    "loaded or recently written, re-run to finish"));
                continue;
            }
            if (shadow == null) {
                out.add(source);
                continue;
            }
            boolean pairValid;
            try {
                validateReversePair(source, shadow);
                pairValid = true;
            } catch (Exception invalid) {
                pairValid = false;
            }
            if (pairValid) {
                // NEVER auto-delete a valid reverse shadow. One-way-upgrade caveat:
                // a post-upgrade pair can hold UNIQUE chunks on both sides at
                // equal counts, so count parity cannot prove either side
                // redundant and deleting either could destroy the only copy of
                // some chunks. Escalate to protection with NEITHER side deleted;
                // the operator compares chunk contents before touching either file.
                preDone.add(new FileResult(source, shadow, Status.FAILED,
                    "reverse shadow pair: valid .mca shadow beside readable source; one-way upgrade may leave"
                        + " unique chunks on either side (count parity cannot prove redundancy);"
                        + " both files left untouched for operator comparison"));
                LOGGER.severe("Linear reverse conversion: valid shadow pair left untouched for inspection: source="
                    + source + " shadow=" + shadow);
                continue;
            }
            boolean sourceReadable;
            try {
                try (AbstractRegionFile src = openProbed(source, source.getParent(),
                    RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                    RegionName coords = parseRegionName(source.getFileName().toString());
                    countChunks(src, coords.x(), coords.z());
                }
                sourceReadable = true;
            } catch (Exception corrupt) {
                sourceReadable = false;
            }
            if (!sourceReadable) {
                preDone.add(new FileResult(source, shadow, Status.FAILED,
                    "reverse shadow pair: source unreadable; shadow left untouched for inspection"));
                LOGGER.severe("Linear reverse conversion: shadow pair with corrupt source left untouched: " + source);
                continue;
            }
            try {
                Files.deleteIfExists(shadow);
                LOGGER.info("Linear reverse conversion: removed invalid anvil shadow " + shadow.getFileName()
                    + "; requeueing " + source.getFileName());
            } catch (IOException deleteFail) {
                preDone.add(new FileResult(source, shadow, Status.FAILED,
                    "reverse shadow pair: cannot remove invalid orphan target " + shadow + ": "
                        + deleteFail.getMessage()));
                continue;
            }
            out.add(source);
        }
        for (Map.Entry<String, Path> e : mca.entrySet()) {
            if (!lin.containsKey(e.getKey())) {
                Path t = e.getValue();
                Path pseudoSource = t.resolveSibling(
                    t.getFileName().toString().substring(0, t.getFileName().toString().length() - ".mca".length())
                        + RegionFileFormat.LINEAR_EXTENSION);
                safeOnFile(events, new FileResult(pseudoSource, t, Status.SKIPPED, "already anvil; no linear source"));
            }
        }
        return out;
    }

    static Path targetForSourceReverse(Path source) {
        String name = source.getFileName().toString();
        if (name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            name = name.substring(0, name.length() - RegionFileFormat.LINEAR_EXTENSION.length())
                + RegionFileFormat.ANVIL_EXTENSION;
        } else if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
            name = name.substring(0, name.length() - RegionFileFormat.B_LINEAR_EXTENSION.length())
                + RegionFileFormat.ANVIL_EXTENSION;
        } else {
            name = name + RegionFileFormat.ANVIL_EXTENSION;
        }
        return source.resolveSibling(name);
    }

    /**
     * Reverse protection-mode terminal alert: highly descriptive,
     * operator-actionable. Sources ({@code .linear}) are never deleted before
     * reverse validation passes, so every failed file still has its original
     * {@code .linear} in place. Conversion resumes automatically on the next
     * run since unconverted sources remain. No halt/exit here: the caller
     * decides to halt.
     */
    static void logReverseProtectionAlert(Path regionFolder, ConversionSummary summary, boolean haltsServer) {
        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("LINEAR REVERSE CONVERSION PROTECTION MODE\n");
        sb.append("============================================================\n");
        sb.append("Folder: ").append(regionFolder.toAbsolutePath()).append("\n");
        sb.append("Direction: ").append(describePairExtensions(summary.failures()))
            .append(" (reverse, downgrade to anvil)\n");
        sb.append("Converted=").append(summary.converted())
            .append(" Validated=").append(summary.validated())
            .append(" Deleted=").append(summary.deleted())
            .append(" Failed=").append(summary.failed()).append("\n");
        sb.append("Guarantee: linear sources are NEVER deleted before reverse validation passes.\n");
        sb.append("All failed files below still have their original sources in place.\n");
        sb.append("Partial .mca targets (if any) were left beside the source.\n");
        sb.append("------------------------------------------------------------\n");
        for (FileResult f : summary.failures()) {
            sb.append("FAILED reverse source=").append(f.source())
                .append(" target=").append(f.target())
                .append(" detail=").append(f.detail()).append("\n");
        }
        sb.append("------------------------------------------------------------\n");
        sb.append("Operator next steps:\n");
        sb.append(" 1. Inspect each FAILED reverse source above (file size, readability,\n");
        sb.append("    disk space, permissions). Common causes: corrupt linear\n");
        sb.append("    header, truncated chunk, full disk, read-only mount.\n");
        sb.append(" 2. Restore the file from backup, or free disk / fix perms.\n");
        sb.append(" 3. Do NOT manually delete source files; re-run and reverse\n");
        sb.append("    conversion resumes automatically (unconverted sources remain).\n");
        sb.append(" 4. If a .mca shadow exists beside a failed linear source (reverse\n");
        sb.append("    shadow-pair escalation), compare chunk CONTENTS before touching\n");
        sb.append("    either file: equal counts do not prove redundancy.\n");
        if (haltsServer) {
            sb.append("Server halts for inspection (caller halts on this exception).\n");
        } else {
            sb.append("The convert job recorded this failure and keeps running;"
                + " sources are untouched, re-run resumes automatically.\n");
        }
        sb.append("============================================================");
        LOGGER.severe(sb.toString());
    }

    private record TaskOutcome(FileResult result) {}

    /**
     * Listener dispatch that can never reclassify a file: a throwing observer
     * is logged and swallowed so its exception neither escapes the worker nor
     * changes the file's stage/outcome.
     */
    private static void safeOnFile(Listener events, FileResult r) {
        try {
            events.onFile(r);
        } catch (RuntimeException le) {
            LOGGER.warning("Linear conversion: listener onFile threw for " + r.source() + ": " + le);
        }
    }

    /** Same never-throw guarantee for retry callbacks. */
    private static void safeOnRetry(Listener events, Path source, int attempt, String reason) {
        try {
            events.onRetry(source, attempt, reason);
        } catch (RuntimeException le) {
            LOGGER.warning("Linear conversion: listener onRetry threw for " + source + ": " + le);
        }
    }

    /**
     * Pre-delete recheck: evaluates the direction's skip predicate AFTER a
     * successful validate and BEFORE the source delete. A file dirtied while
     * its copy was being made must not be deleted out from under the live
     * writer (soak: d-load proof that deletes of re-dirtied files orphan live
     * handles, which then resurrect the source on their next flush and force
     * a shadow-pair protection halt).
     *
     * <p>TOCTOU narrowing: the recheck and the source delete are kept
     * adjacent with no intervening IO at both call sites (no logging,
     * callbacks, validation, or cleanup between the check and the delete on
     * the proceed path), so the race window is exactly one predicate test
     * plus one filesystem delete. No coordinator/folder lock is held across
     * the pair: none fits here. The coordinator (flush module) guards handle
     * identity under its own per-folder monitor, while this core sees only
     * {@code Path}s through a leg-supplied predicate evaluated outside any
     * lock — taking the coordinator monitor in core would serialize against
     * nothing (the leg's check runs outside it either way) and would add a
     * convert-to-flush module edge for the NMS-free boot path, so the
     * check-then-delete form stands.</p>
     *
     * <p>Residual window (accepted, fail-closed): a write landing after the
     * recheck but before the delete still deletes a just-dirtied source. The
     * backstop is the shadow-pair policy, not this check: the orphaned live
     * handle resurrects the source on its next flush, the next run sees a
     * valid shadow pair and escalates to FAILED / protection with neither
     * side deleted. Data is never lost; the cost is one operator-visible
     * halt, which is why the recheck exists (to make that halt rare, not
     * impossible).</p>
     *
     * <p>Folder-granularity over-defer (accepted cost): the leg predicate
     * typically reports folder dirtiness, so one dirty file defers every file
     * in its folder. Per-file dirty tracking was rejected as too invasive:
     * the coordinator keys unflushed state by live handle identity
     * ({@code AbstractRegionFile} exposes no path), while conversion keys by
     * {@code Path}; joining them would couple the boot-time converter to
     * flush-handle lifecycles for marginal benefit. Legs share one
     * misshape contract via the flush-side {@code isFolderDirty} helper.</p>
     *
     * @return {@code null} when the delete may proceed, otherwise the SKIPPED
     *     detail line (fail-closed on predicate throw, like collection time)
     */
    private static String preDeleteSkip(Path source, java.util.function.Predicate<Path> skip) {
        try {
            if (skip.test(source)) {
                return "loaded or recently written during conversion, re-run to finish";
            }
            return null;
        } catch (RuntimeException predicateFailed) {
            return "skip check failed, deferred: " + String.valueOf(predicateFailed.getMessage());
        }
    }

    /**
     * Thrown when a pre-swap folder-dirty recheck aborts a same-path swap.
     * Carries the SKIPPED detail so the caller reports DEFERRED (source
     * untouched, staging cleaned) instead of FAILED/retry.
     */
    static final class DeferredBeforeSwapException extends IOException {
        DeferredBeforeSwapException(String detail) {
            super(detail);
        }
    }

    private static final class FileTask implements Callable<TaskOutcome> {
        private final Path source;
        private final Path target;
        private final int level;
        private final Listener events;
        private final RegionFileFormat targetFormat;

        FileTask(Path source, Path target, int level, Listener events, RegionFileFormat targetFormat) {
            this.source = source;
            this.target = target;
            this.level = level;
            this.events = events;
            this.targetFormat = targetFormat;
        }

        @Override
        public TaskOutcome call() {
            FileState state = FileState.CONVERTING;
            String lastError = "";
            int attempt = 0;
            while (shouldRetry(attempt)) {
                attempt++;
                try {
                    state = FileState.CONVERTING;
                    convertSingleFileToFormat(this.source, this.target, this.level, this.targetFormat);
                    state = transition(state, true);
                    safeOnFile(this.events, new FileResult(this.source, this.target, Status.CONVERTED,
                        "attempt " + attempt + " conversion ok"));

                    state = FileState.VALIDATING;
                    String validation = validatePair(this.source, this.target);
                    state = transition(state, true);
                    safeOnFile(this.events, new FileResult(this.source, this.target, Status.VALIDATED,
                        "attempt " + attempt + " validation ok: " + validation));

                    state = FileState.DELETING;
                    // TOCTOU adjacency: this recheck immediately precedes the
                    // source delete below with no intervening IO on the
                    // proceed path (see preDeleteSkip for the residual window
                    // and the shadow-pair backstop).
                    String deferred = preDeleteSkip(this.source, FORWARD_SKIP);
                    if (deferred != null) {
                        // Defer, do not delete: drop this attempt's uncommitted
                        // target so the source stands alone again. Leaving a
                        // validated target beside a live source would escalate
                        // to a shadow-pair protection halt on the next run;
                        // removing it restores the exact pre-run state and the
                        // re-run reconverts cleanly.
                        cleanAttemptArtifacts(this.source, this.target);
                        FileResult skipped = new FileResult(this.source, this.target, Status.SKIPPED,
                            "attempt " + attempt + " deferred before delete: " + deferred);
                        safeOnFile(this.events, skipped);
                        return new TaskOutcome(skipped);
                    }
                    Files.deleteIfExists(this.source);
                    if (this.source.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                        // Buffered sources carry scratch siblings that must not
                        // linger: nothing will ever open them again and the next
                        // boot would mistake the swap for live state.
                        deleteBufferedSidecars(this.source);
                    }
                    // Finished B_LINEAR targets ship master-only (WAL pruned):
                    // validation reopened the target, recreating its sidecar.
                    pruneFinishedBufferedTarget(this.target);
                    state = transition(state, true);
                    FileResult done = new FileResult(this.source, this.target, Status.DELETED,
                        "converted+validated+deleted on attempt " + attempt);
                    safeOnFile(this.events, done);
                    return new TaskOutcome(done);
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                    FileState failureStage = state;
                    state = FileState.FAILED;
                    // DELETING means convert+validate already succeeded: the
                    // target is validated, so retry only the source delete.
                    // Wiping the validated target here would destroy the only
                    // good copy and force a full reconvert.
                    if (failureStage != FileState.DELETING) {
                        cleanAttemptArtifacts(this.source, this.target);
                    }
                    if (shouldRetry(attempt)) {
                        safeOnRetry(this.events, this.source, attempt, lastError);
                    }
                }
            }
            FileResult failed = new FileResult(this.source, this.target, Status.FAILED,
                "3 attempts exhausted (" + state + "); last error: " + lastError);
            safeOnFile(this.events, failed);
            return new TaskOutcome(failed);
        }
    }

    /**
     * Storage identity used only to construct region-file handles for chunk IO.
     * Storage opener seam: the adapter supplies NMS-backed opens (the old
     * factory-get behavior); {@code format == null} probes the on-disk extension.
     * Default opens LINEAR directly and rejects anything else, so unit tests
     * exercise conversion end to end without NMS.
     */
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

    static AbstractRegionFile openProbed(Path file, Path folder, int level) throws IOException {
        return OPENER.open(file, folder, false, null, level);
    }

    /**
     * Write-side seam for the reverse ({@code .linear} -&gt; {@code .mca})
     * pipeline. Legs supply an NMS {@code RegionFile} writer: raw NBT bytes
     * from the linear source are passed through unchanged, the leg envelops
     * (compression wrapper + sector allocation) on write.
     */
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

    private static volatile java.util.function.Predicate<Path> REVERSE_SKIP = p -> false;

    /**
     * Reverse skip seam: files testing {@code true} are reported SKIPPED
     * ("loaded or recently written, re-run to finish") and never queued.
     * Legs implement the loaded/dirty check later; default converts everything.
     *
     * <p>Folder-granularity over-defer (accepted cost): legs normally answer
     * from folder dirtiness, so one dirty file defers the whole folder; see
     * {@code preDeleteSkip} for why per-file tracking was rejected. Legs
     * should share one misshape contract via the flush-side
     * {@code LinearFlushCoordinator.isFolderDirty} helper (fail-closed:
     * unknown/misshape/exception reads dirty).</p>
     */
    public static void linear$setReverseSkipPredicate(java.util.function.Predicate<Path> predicate) {
        REVERSE_SKIP = java.util.Objects.requireNonNull(predicate, "predicate");
    }

    private static volatile java.util.function.Predicate<Path> FORWARD_SKIP = p -> false;

    /**
     * Forward skip seam (parity with {@link #linear$setReverseSkipPredicate}):
     * {@code .mca} files testing {@code true} are reported SKIPPED ("loaded
     * or recently written, re-run to finish") and never queued, so a live
     * forward convert defers loaded regions instead of copying a file the
     * server is actively writing. Legs register the same loaded/dirty check
     * they use for reverse; default converts everything.
     *
     * <p>Soak finding (d-load, Oct 2026): forward had no skip while reverse
     * did, so a forward convert under chunk traffic copied loaded files
      * outright where reverse would have deferred them.
      *
      * <p>Folder-granularity over-defer (accepted cost): same contract as the
      * reverse seam — one dirty file defers the whole folder; see
      * {@code preDeleteSkip} for why per-file tracking was rejected. Legs
      * should share one misshape contract via the flush-side
      * {@code LinearFlushCoordinator.isFolderDirty} helper (fail-closed:
      * unknown/misshape/exception reads dirty).</p>
      */
    public static void linear$setForwardSkipPredicate(java.util.function.Predicate<Path> predicate) {
        FORWARD_SKIP = java.util.Objects.requireNonNull(predicate, "predicate");
    }

    /**
     * Copies all chunks from {@code source (.mca)} to {@code finalTarget (.linear)}
     * via the sibling {@code new_<folder>} staging directory, then moves the
     * staged file to its final name. Plain move (NOT ATOMIC_MOVE) for parity
     * with {@code RegionStorageUpgrader.onFileFinished}: atomic moves are not
     * guaranteed across filesystems and the existing commit path uses plain moves.
     */
    static void convertSingleFile(Path source, Path finalTarget, int compressionLevel) throws IOException {
        convertSingleFileToFormat(source, finalTarget, compressionLevel, RegionFileFormat.LINEAR);
    }

    /**
     * Copies all chunks from {@code source} (any convertible format) to
     * {@code finalTarget} ({@code .linear} or {@code .b_linear}) via staging,
     * then moves the staged file(s) to the final name. Same validate-before-
     * delete contract as {@link #convertSingleFile}; buffered targets move
     * their {@code .swp} sibling alongside the master.
     */
    static void convertSingleFileToFormat(Path source, Path finalTarget, int compressionLevel,
            RegionFileFormat targetFormat) throws IOException {
        if (targetFormat != RegionFileFormat.LINEAR && targetFormat != RegionFileFormat.B_LINEAR) {
            throw new IOException("Not a convertible target format " + targetFormat + " for " + source);
        }
        RegionName coords = parseRegionName(source.getFileName().toString());
        String sourceName = source.getFileName().toString();
        boolean sourceAnvil = sourceName.endsWith(RegionFileFormat.ANVIL_EXTENSION);
        boolean sourceLinear = sourceName.endsWith(RegionFileFormat.LINEAR_EXTENSION);
        boolean sourceBuffered = sourceName.endsWith(RegionFileFormat.B_LINEAR_EXTENSION);
        if (coords == null || (!sourceAnvil && !sourceLinear && !sourceBuffered)) {
            throw new IOException("Not a convertible region file: " + source);
        }
        Path folder = source.getParent();
        Path stagingDir = resolveRecreateDirectory(folder);
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
            if (sourceSize != 0L && sourceSize < MIN_ANVIL_SIZE) {
                throw new IOException("Source too small to hold a valid .mca header ("
                    + sourceSize + " bytes): " + source);
            }
        } else if (sourceLinear) {
            if (sourceSize != 0L && sourceSize <= MIN_VALID_SIZE) {
                throw new IOException("Source too small to hold a valid .linear header ("
                    + sourceSize + " bytes): " + source);
            }
            if (sourceSize != 0L) {
                checkHeaderSanity(source);
            }
        } else {
            if (sourceSize != 0L && sourceSize < MIN_BLINEAR_SIZE) {
                throw new IOException("Source too small to hold a valid .b_linear header ("
                    + sourceSize + " bytes): " + source);
            }
            if (sourceSize != 0L) {
                checkHeaderSanity(source);
            }
        }

        int copied = 0;
        int sourceCount = 0;
        try (AbstractRegionFile src = openProbed(source, folder, level)) {
            // Count EXACTLY the way the copy loop below copies (MED-1):
            // degenerate slots (hasChunk true but null/empty payload) are
            // skipped by the copy, so counting them here would manufacture a
            // mismatch, burn 3 retries and halt boot. IO errors propagate.
            sourceCount = countCopyableChunks(src, coords.x(), coords.z());
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
    static int copyChunkRange(AbstractRegionFile src, AbstractRegionFile dst, RegionName coords) throws IOException {
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
    static void materializeEmptyTarget(Path stagingTarget, int compressionLevel, long dummyKey) throws IOException {
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
    static void materializeEmptyBufferedTarget(Path stagingTarget, int compressionLevel, long dummyKey)
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
     * (d) decompress-verification of every counted target payload: header,
     * footer and parity cannot catch a torn middle (valid framing, corrupt
     * chunk frame), so each present chunk is fully drained. Files are small
     * and this runs at boot: correctness over speed.</p>
     *
     * @return human-readable validation detail for logging
     */
    static String validatePair(Path source, Path target) throws IOException {
        RegionName coords = parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable region name: " + source);
        }
        int sourceCount;
        int targetCount;
        try (AbstractRegionFile src = openProbed(source, source.getParent(),
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
        try (AbstractRegionFile dst = openProbed(target, target.getParent(),
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

    /** Header/footer sanity: magic + version range + footer magic. Pure byte logic. */
    static void checkHeaderSanity(Path target) throws IOException {
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
    static void checkBufferedHeaderSanity(Path target) throws IOException {
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

    /** Counts present chunks for the region at (regionX, regionZ). */
    static int countChunks(AbstractRegionFile file, int regionX, int regionZ) {
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
     * exactly the slots the copy loop in {@link #convertSingleFile} writes.
     * IO errors propagate (a genuinely corrupt chunk fails loudly into the
     * FAILED path instead of converging silently).
     */
    static int countCopyableChunks(AbstractRegionFile src, int regionX, int regionZ) throws IOException {
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
    static void verifyTargetPayloads(AbstractRegionFile dst, int regionX, int regionZ) throws IOException {
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

    /** Parity rule: equal counts; 0 == 0 is explicitly VALID (trivially empty file). */
    static boolean countsMatch(int sourceCount, int targetCount) {
        return sourceCount == targetCount;
    }

    /** Retry rule: attempts are 1-based; attempts 1..2 may retry, attempt 3 is terminal. */
    static boolean shouldRetry(int completedAttempts) {
        return completedAttempts < MAX_ATTEMPTS;
    }

    /** State-machine step. Package-visible pure logic for unit tests. */
    static FileState transition(FileState current, boolean stageSuccess) {
        if (!stageSuccess) {
            return FileState.FAILED;
        }
        return switch (current) {
            case CONVERTING -> FileState.CONVERTED;
            case CONVERTED -> FileState.VALIDATING;
            case VALIDATING -> FileState.VALIDATED;
            case VALIDATED -> FileState.DELETING;
            case DELETING -> FileState.DELETED;
            default -> current;
        };
    }

    /**
     * Deletes leftover staging plus the partial final target between attempts.
     * Best-effort: a torn final target must never linger where a later crash
     * could mistake it for a finished conversion. The source is never touched.
     */
    static void cleanAttemptArtifacts(Path source, Path target) {
        try {
            Path staging = resolveRecreateDirectory(source.getParent()).resolve(target.getFileName().toString());
            Files.deleteIfExists(staging);
            Files.deleteIfExists(staging.resolveSibling(staging.getFileName() + ".swp"));
            Files.deleteIfExists(staging.resolveSibling(staging.getFileName() + ".tmp"));
            Files.deleteIfExists(staging.resolveSibling(staging.getFileName() + ".swp.tmp"));
            Files.deleteIfExists(target);
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".swp"));
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".tmp"));
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".swp.tmp"));
        } catch (Exception ignored) {
        }
    }

    /**
     * WAL-at-rest prune for finished B_LINEAR targets: validation reopened
     * the target (recreating its swap sidecar), but the master holds the
     * full snapshot, so the sidecar is scratch. Best-effort; the next open
     * rebuilds swap state from the master. Never called on live sources.
     * Covers the swap plus both staging suffixes (master {@code .tmp} from
     * a killed flush, compact {@code .swp.tmp}).
     */
    static void pruneFinishedBufferedTarget(Path target) {
        try {
            if (target.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                deleteBufferedSidecars(target);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Deletes every scratch sibling of a B_LINEAR master: the swap sidecar
     * plus both staging suffixes. Best-effort; used for dead sources (never
     * opened again) and finished targets (master-only ship).
     */
    static void deleteBufferedSidecars(Path master) {
        try {
            Files.deleteIfExists(master.resolveSibling(master.getFileName() + ".swp"));
            Files.deleteIfExists(master.resolveSibling(master.getFileName() + ".tmp"));
            Files.deleteIfExists(master.resolveSibling(master.getFileName() + ".swp.tmp"));
        } catch (Exception ignored) {
        }
    }

    /**
     * Crash-resume step (a): delete orphan {@code new_<folder>} content left by a
     * crashed run, plus stale {@code *.pumpkin-tmp} beside the source left by a
     * crashed same-path Pumpkin swap. Crash-before-move is safe to retry (source
     * intact, fragment ignored); without this cleanup a stale staged file could
     * be mistaken for fresh work on the next attempt. {@code *.pumpkin-tmp}
     * files never match {@code REGION_NAME} so collection stays blind to them;
     * the preamble still wipes them here.
     *
     * @return number of deleted entries
     */
    static int cleanRecreateDirectory(Path regionFolder) {
        int removed = 0;
        // Stale same-path Pumpkin tmps beside the source (invisible to REGION_NAME).
        // Region-scoped like the staging sweep below so unrelated files kept.
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder, "r.*.pumpkin-tmp")) {
            for (Path p : ds) {
                try {
                    if (Files.isRegularFile(p) && Files.deleteIfExists(p)) {
                        removed++;
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
            // Missing/unreadable folder: staging cleanup below still runs (or no-ops).
        }
        // Stale flush/compact staging beside live region files (killed
        // mid-flush/compact). Runs under the startup quiesce gate, so no live
        // flush can be authoring these; a forced-but-unmoved tmp is garbage
        // by definition (the master is old truth until the move lands).
        // Globs are region-scoped (r.*) so unrelated operator tmps are kept.
        for (String glob : new String[]{"r.*.tmp", "r.*.swp.tmp"}) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder, glob)) {
                for (Path p : ds) {
                    try {
                        if (Files.isRegularFile(p) && Files.deleteIfExists(p)) {
                            removed++;
                        }
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
        Path staging = resolveRecreateDirectory(regionFolder);
        if (!Files.isDirectory(staging)) {
            return removed;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(staging)) {
            List<Path> entries = new ArrayList<>();
            for (Path p : ds) {
                entries.add(p);
            }
            for (Path p : entries) {
                try {
                    if (Files.isDirectory(p)) {
                        try (java.util.stream.Stream<Path> walk = Files.walk(p)) {
                            List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
                            for (Path q : all) {
                                Files.deleteIfExists(q);
                                removed++;
                            }
                        }
                    } else {
                        Files.deleteIfExists(p);
                        removed++;
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return removed;
    }

    /**
     * Crash-resume step (b): collect conversion candidates and resolve shadow pairs.
     *
     * <p>Shadow policy: the live dual-read prefers {@code .mca}, so a
     * {@code .mca + .linear} pair with the same coordinates can only be a crash
     * remnant (live conversion deletes sources post-validation) or operator
     * action. A {@code .linear} shadow that FAILS validation is an orphan:
     * delete it and requeue the {@code .mca} for conversion (escalating instead
     * when the source itself is unreadable, so a corrupt source is never
     * destroyed). A shadow that VALIDATES is NEVER auto-deleted: after a
     * one-way downgrade both sides can hold unique chunks at equal counts, and
     * count-parity validation cannot prove the shadow redundant. Valid-shadow
     * pairs escalate to FAILED / protection with neither side deleted; the
     * operator compares chunk contents before touching either file.</p>
     */
    static List<Path> collectCandidates(Path regionFolder, Listener events, List<FileResult> preFailures) {
        return collectForwardCandidates(regionFolder, events, preFailures, RegionFileFormat.LINEAR);
    }

    /**
     * Forward crash-resume step: collect convertible non-target candidates for
     * {@code targetFormat} and resolve shadow pairs. Union semantics: every
     * convertible format other than the target converts (e.g. toward LINEAR,
     * both {@code .mca} and {@code .b_linear} sources queue).
     *
     * <p>Shadow policy mirrors the legacy path per target: a target-format
     * file beside a convertible source is suspect. A pair that VALIDATES is
     * NEVER auto-deleted (one-way caveat: unique chunks can hide on both
     * sides at equal counts); it escalates to FAILED with NEITHER side
     * deleted. An INVALID shadow beside a readable source is an orphan: drop
     * the bad target and requeue the source (escalating instead when the
     * source itself is unreadable). Target-only files report SKIPPED.</p>
     */
    static List<Path> collectForwardCandidates(Path regionFolder, Listener events, List<FileResult> preFailures,
            RegionFileFormat targetFormat) {
        String targetExt = targetFormat == RegionFileFormat.B_LINEAR
            ? RegionFileFormat.B_LINEAR_EXTENSION
            : RegionFileFormat.LINEAR_EXTENSION;
        Map<String, List<Path>> sourcesByCoords = new HashMap<>();
        Map<String, Path> targets = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                Matcher m = REGION_NAME.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                String key = m.group(1) + "," + m.group(2);
                String fileName = p.getFileName().toString();
                if (fileName.endsWith(targetExt)) {
                    targets.put(key, p);
                } else {
                    sourcesByCoords.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Linear conversion: cannot list " + regionFolder + ": " + e.getMessage());
            return List.of();
        }

        List<Path> out = new ArrayList<>();
        for (Map.Entry<String, List<Path>> e : sourcesByCoords.entrySet()) {
            List<Path> sameCoords = e.getValue();
            if (sameCoords.size() > 1) {
                // Ambiguous same-coordinates sources (e.g. r.0.0.mca beside
                // r.0.0.b_linear): queueing both would map them onto one
                // target path, and picking one silently drops the other.
                // Escalate with everything kept; the operator keeps one
                // file, moves the other aside, and re-runs.
                sameCoords.sort(Comparator.comparing(p -> p.getFileName().toString()));
                preFailures.add(new FileResult(sameCoords.get(0),
                    targetForSource(sameCoords.get(0), targetFormat), Status.FAILED,
                    "ambiguous same-coordinates sources ("
                        + sameCoords.stream().map(p -> p.getFileName().toString())
                            .reduce((a, b) -> a + ", " + b).orElse("?")
                        + "); all files left untouched: keep one, move the rest aside, re-run"));
                LOGGER.severe("Linear conversion: ambiguous same-coordinates sources left untouched: "
                    + sameCoords);
                continue;
            }
            Path source = sameCoords.get(0);
            Path shadow = targets.get(e.getKey());
            Path target = targetForSource(source, targetFormat);
            // F1: startup with a non-Pumpkin target must never convert Pumpkin sources.
            // A Pumpkin .linear beside a B_LINEAR target would otherwise queue as a
            // convertible source, convert, then delete the Pumpkin original. Skip it
            // (SKIPPED, source untouched) and name explicit convert as the remedy.
            // LINEAR targets never reach here (.linear files are target occupants,
            // handled as shadows below and left alone), so this only fires for B_LINEAR.
            if (isPumpkinLinearFile(source)) {
                safeOnFile(events, new FileResult(source, target, Status.SKIPPED,
                    "Pumpkin layout, startup never converts Pumpkin;"
                        + " use /linear convert --from pumpkin --to <target> explicit migration"));
                LOGGER.info("Linear conversion: Pumpkin source skipped (startup never converts Pumpkin): "
                    + source.getFileName()
                    + "; use /linear convert --from pumpkin --to <target> explicit migration");
                continue;
            }
            if (shadow == null) {
                // Soak parity (d-load, Oct 2026): never queue a file the
                // server is actively writing blindly, or the copy races live
                // writes and the delete can orphan a live handle.
                boolean skip;
                try {
                    skip = FORWARD_SKIP.test(source);
                } catch (RuntimeException predicateFailed) {
                    // Fail-closed like reverse: a throwing skip check defers
                    // the file instead of converting a possibly loaded one.
                    safeOnFile(events, new FileResult(source, target, Status.SKIPPED,
                        "skip check failed, deferred: " + String.valueOf(predicateFailed.getMessage())));
                    continue;
                }
                if (skip) {
                    safeOnFile(events, new FileResult(source, target, Status.SKIPPED,
                        "loaded or recently written, re-run to finish"));
                    continue;
                }
                out.add(source);
                continue;
            }
            String shadowExt = shadow.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)
                ? ".b_linear"
                : ".linear";
            boolean targetValid;
            try {
                validatePair(source, shadow);
                targetValid = true;
            } catch (Exception invalid) {
                targetValid = false;
            }
            if (targetValid) {
                // NEVER auto-delete a valid shadow. One-way caveat: a pair
                // can hold UNIQUE chunks on both sides at equal counts, so
                // count parity cannot prove the shadow redundant and deleting
                // it could destroy the only copy of some chunks. Escalate to
                // protection with NEITHER side deleted; the operator compares
                // chunk contents before touching either file.
                preFailures.add(new FileResult(source, target, Status.FAILED,
                    "shadow pair: valid " + shadowExt + " shadow beside readable source; one-way conversion may leave"
                        + " unique chunks on either side (count parity cannot prove redundancy);"
                        + " both files left untouched for operator comparison"));
                LOGGER.severe("Linear conversion: valid shadow pair left untouched for inspection: source="
                    + source + " shadow=" + shadow);
                continue;
            }
            // Orphan target is invalid: drop it and requeue the source.
            // If the source itself is corrupt, conversion attempts will fail
            // 3x and enter protection there (nothing deleted meanwhile).
            // Startup gate (FR3/FR7): a VALID Pumpkin (.linear wire-2 bucketed) shadow is
            // NEVER auto-deleted here. Startup auto-conversion only processes .mca
            // (+ .b_linear union) sources toward LINEAR and must not rewrite Pumpkin
            // files: a count-invalid but structurally-valid Pumpkin shadow could still
            // hold unique chunks (one-way caveat) that only an explicit --from/--to
            // job may touch. Escalate with both kept instead. Corrupt/ambiguous
            // shadows keep the legacy orphan path (a 10-byte torn header was never a
            // valid Pumpkin file and is safe to drop when the source is readable).
            try {
                String shadowName = shadow.getFileName().toString();
                if (shadowName.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                    boolean shadowIsPumpkin = false;
                    boolean shadowAmbiguousPumpkin = false;
                    try {
                        shadowIsPumpkin = detectLinearFormat(shadow)
                            == net.linear.versions.RegionFormatDetector.DetectedFormat.PUMPKIN;
                    } catch (IOException detectFail) {
                        String msg = String.valueOf(detectFail.getMessage());
                        // Ambiguous layouts naming PUMPKIN could be genuine Pumpkin bytes
                        // (both V1 length equation and Pumpkin structure verify): escalate.
                        // Pure corrupt/unknown (zero candidates, e.g. 10-byte torn header)
                        // keeps the legacy orphan path below.
                        shadowAmbiguousPumpkin = msg.contains("Ambiguous")
                            && msg.contains("PUMPKIN");
                    }
                    if (shadowIsPumpkin || shadowAmbiguousPumpkin) {
                        preFailures.add(new FileResult(source, target, Status.FAILED,
                            "shadow pair: Pumpkin shadow beside readable source;"
                                + " startup auto-conversion never rewrites Pumpkin files;"
                                + " both files left untouched for operator comparison;"
                                + " use /linear convert --from pumpkin --to <target> explicit migration"));
                        LOGGER.severe("Linear conversion: Pumpkin shadow left untouched (no auto-rewrite): source="
                            + source + " shadow=" + shadow
                            + "; use /linear convert --from pumpkin --to <target> explicit migration");
                        continue;
                    }
                }
            } catch (RuntimeException gateFail) {
                // Fail-closed: a throwing gate never queues the file for overwrite.
                preFailures.add(new FileResult(source, target, Status.FAILED,
                    "shadow pair: Pumpkin gate failed; both files left untouched for inspection"));
                LOGGER.severe("Linear conversion: Pumpkin gate failure left untouched: source="
                    + source + " shadow=" + shadow);
                continue;
            }
            boolean sourceReadable;
            try {
                try (AbstractRegionFile src = openProbed(source, source.getParent(),
                    RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                    RegionName coords = parseRegionName(source.getFileName().toString());
                    countChunks(src, coords.x(), coords.z());
                }
                sourceReadable = true;
            } catch (Exception corrupt) {
                sourceReadable = false;
            }
            if (!sourceReadable) {
                // Source unreadable; shadow left untouched for inspection:
                // conversion cannot succeed, so escalate now rather than
                // burning 3 doomed attempts. Neither side is deleted.
                preFailures.add(new FileResult(source, target, Status.FAILED,
                    "shadow pair: source unreadable; shadow left untouched for inspection"));
                LOGGER.severe("Linear conversion: shadow pair with corrupt source left untouched: " + source);
                continue;
            }
            try {
                Files.deleteIfExists(shadow);
                Files.deleteIfExists(shadow.resolveSibling(shadow.getFileName() + ".swp"));
                LOGGER.info("Linear conversion: removed invalid shadow " + shadow.getFileName()
                    + "; requeueing " + source.getFileName());
            } catch (IOException deleteFail) {
                preFailures.add(new FileResult(source, target, Status.FAILED,
                    "shadow pair: cannot remove invalid orphan target " + shadow + ": " + deleteFail.getMessage()));
                continue;
            }
            out.add(source);
        }
        // Already-converted (target-only) files are reported as SKIPPED for visibility.
        for (Map.Entry<String, Path> e : targets.entrySet()) {
            if (!sourcesByCoords.containsKey(e.getKey())) {
                Path t = e.getValue();
                String base = t.getFileName().toString();
                base = base.substring(0, base.length() - targetExt.length());
                Path pseudoSource = t.resolveSibling(base + RegionFileFormat.ANVIL_EXTENSION);
                safeOnFile(events, new FileResult(pseudoSource, t, Status.SKIPPED, "already converted; no source"));
            }
        }
        // No lock file is used: conversion runs exactly once on the single boot
        // thread before worlds load, so no second converter can race it. A lock
        // file would itself need crash-resume (stale-lock detection), adding
        // failure modes for no concurrency benefit.
        return out;
    }

    static Path targetForSource(Path source) {
        return targetForSource(source, RegionFileFormat.LINEAR);
    }

    static Path targetForSource(Path source, RegionFileFormat targetFormat) {
        String ext = targetFormat == RegionFileFormat.B_LINEAR
            ? RegionFileFormat.B_LINEAR_EXTENSION
            : RegionFileFormat.LINEAR_EXTENSION;
        String name = source.getFileName().toString();
        for (String known : new String[] {RegionFileFormat.ANVIL_EXTENSION, RegionFileFormat.LINEAR_EXTENSION,
                RegionFileFormat.B_LINEAR_EXTENSION}) {
            if (name.endsWith(known)) {
                name = name.substring(0, name.length() - known.length()) + ext;
                return source.resolveSibling(name);
            }
        }
        return source.resolveSibling(name + ext);
    }

    static Path resolveRecreateDirectory(Path directoryPath) {
        return directoryPath.resolveSibling(NEW_DIRECTORY_PREFIX + directoryPath.getFileName().toString());
    }

    /**
     * One-line source-to-target extension summary for alerts
     * (e.g. {@code .mca -> .b_linear}); heterogeneous failure sets report
     * {@code mixed} rather than guessing.
     */
    static String describePairExtensions(java.util.List<FileResult> failures) {
        String pair = null;
        for (FileResult f : failures) {
            String current = extensionOf(f.source()) + " -> " + extensionOf(f.target());
            if (pair == null) {
                pair = current;
            } else if (!pair.equals(current)) {
                return "mixed";
            }
        }
        return pair != null ? pair : "unknown";
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
            return ".b_linear";
        }
        if (name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            return ".linear";
        }
        if (name.endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
            return ".mca";
        }
        return "(unknown)";
    }

    private record RegionName(int x, int z) {}

    static RegionName parseRegionName(String fileName) {
        Matcher m = REGION_NAME.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        try {
            return new RegionName(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Protection-mode terminal alert: highly descriptive, operator-actionable.
     * Sources are never deleted before validation, so every failed file still
     * has its original {@code .mca} in place plus whatever partial target was
     * left. Conversion resumes automatically on the next boot since unconverted
     * sources remain. No halt/exit here: the caller (B2) decides to halt.
     */
    static void logProtectionAlert(Path regionFolder, ConversionSummary summary, boolean haltsServer) {
        logProtectionAlert(regionFolder, summary, haltsServer, null);
    }

    /**
     * @param explicitRemedy when non-null (explicit-convert halts), logged as
     *        an additional operator step naming the explicit migration command
     */
    static void logProtectionAlert(Path regionFolder, ConversionSummary summary, boolean haltsServer,
            String explicitRemedy) {
        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("LINEAR CONVERSION PROTECTION MODE\n");
        sb.append("============================================================\n");
        sb.append("Folder: ").append(regionFolder.toAbsolutePath()).append("\n");
        sb.append("Direction: ").append(describePairExtensions(summary.failures())).append("\n");
        sb.append("Converted=").append(summary.converted())
            .append(" Validated=").append(summary.validated())
            .append(" Deleted=").append(summary.deleted())
            .append(" Failed=").append(summary.failed()).append("\n");
        sb.append("Guarantee: sources are NEVER deleted before validation passes.\n");
        sb.append("All failed files below still have their original sources in place.\n");
        sb.append("Partial targets (if any) were left beside the source.\n");
        sb.append("------------------------------------------------------------\n");
        for (FileResult f : summary.failures()) {
            sb.append("FAILED source=").append(f.source())
                .append(" target=").append(f.target())
                .append(" detail=").append(f.detail()).append("\n");
        }
        sb.append("------------------------------------------------------------\n");
        sb.append("Operator next steps:\n");
        sb.append(" 1. Inspect each FAILED source above (file size, readability,\n");
        sb.append("    disk space, permissions). Common causes: corrupt region\n");
        sb.append("    header, truncated chunk, full disk, read-only mount.\n");
        sb.append(" 2. Restore the file from backup, or free disk / fix perms.\n");
        sb.append(" 3. Do NOT manually delete source files; re-run the server and\n");
        sb.append("    conversion resumes automatically (unconverted sources remain).\n");
        sb.append(" 4. If a converted shadow exists beside a failed source (shadow-pair\n");
        sb.append("    escalation), compare chunk CONTENTS before touching either file:\n");
        sb.append("    equal counts do not prove redundancy after a one-way conversion.\n");
        if (explicitRemedy != null) {
            sb.append(" 5. ").append(explicitRemedy).append("\n");
        }
        if (haltsServer) {
            sb.append("Server halts for inspection (caller halts on this exception).\n");
        } else {
            sb.append("The convert job recorded this failure and keeps running;"
                + " sources are untouched, re-run resumes automatically.\n");
        }
        sb.append("============================================================");
        LOGGER.severe(sb.toString());
    }

    // =====================================================================
    // Phase-3: Pumpkin as source AND target (FR3/FR4/FR7 + section 4).
    //
    // Canonical form = slot tables (parse source via its Version -> flush
    // target via its Version). No new abstraction layer: versions/*.parse +
    // versions/*.flush ARE the translation path. Concretely:
    //   .linear sources are identified with RegionFormatDetector (never
    //   wire-byte alone); V1/V2/V3 parse via LinearBase.getShared, PUMPKIN via
    //   PumpkinLinear. V1/V2/V3 targets reuse existing LinearRegionFile staging
    //   (impl + grid); B_LINEAR reuses BufferedLinearRegionFile; PUMPKIN targets
    //   use PumpkinLinear.shared().flush image + tmp+fsync+atomic-move.
    // This decision keeps translation ownership in the version codecs (R1/R2)
    // instead of a new converter-owned envelope layer.
    // =====================================================================

    /** Pumpkin default grid (upstream DEFAULT_GRID_SIZE) when the job omits grid. */
    static final int PUMPKIN_DEFAULT_GRID = 2;
    /** V2/V3 default grid when the job omits grid. */
    static final int BUCKETED_DEFAULT_GRID = 8;

    /**
     * Detects the layout of a {@code .linear} file image (never wire-byte alone:
     * byte 17 alone cannot discriminate V1 level 1..22 from Pumpkin/V2 grid).
     *
     * @return single validating format (V1/V2/V3/PUMPKIN)
     * @throws IOException fail-closed with diagnosis (corrupt/unknown or ambiguous,
     *         candidates named; never falls through into another layout's reader)
     */
    static net.linear.versions.RegionFormatDetector.DetectedFormat detectLinearFormat(Path file)
            throws IOException {
        RegionName coords = parseRegionName(file.getFileName().toString());
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
    static boolean isPumpkinLinearFile(Path file) {
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
    static net.linear.versions.ChunkSlotTable parseLinearToSlots(Path file) throws IOException {
        RegionName coords = parseRegionName(file.getFileName().toString());
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
    static net.linear.versions.ChunkSlotTable parseLinearToSlots(Path file, int regionX, int regionZ)
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
                version = net.linear.LinearBase.getShared((byte) 2);
                break;
            case V2:
                version = net.linear.LinearBase.getShared((byte) 3);
                break;
            case V3:
                version = net.linear.LinearBase.getShared((byte) 4);
                break;
            case PUMPKIN:
                version = net.linear.versions.PumpkinLinear.shared();
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
    static ConvertFormat actualFormatOf(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
            return ConvertFormat.B_LINEAR;
        }
        if (name.endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
            return ConvertFormat.MCA;
        }
        if (name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            net.linear.versions.RegionFormatDetector.DetectedFormat d = detectLinearFormat(file);
            switch (d) {
                case V1:
                    return ConvertFormat.V1;
                case V2:
                    return ConvertFormat.V2;
                case V3:
                    return ConvertFormat.V3;
                case PUMPKIN:
                    return ConvertFormat.PUMPKIN;
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
    public static boolean matchesFrom(ConvertFormat actual, ConvertFormat from) {
        if (from == null || from == ConvertFormat.AUTO) {
            return true;
        }
        if (from == ConvertFormat.LINEAR) {
            return actual == ConvertFormat.V1 || actual == ConvertFormat.V2
                || actual == ConvertFormat.V3 || actual == ConvertFormat.PUMPKIN;
        }
        return actual == from;
    }

    /** Target path for an explicit job (same-folder; {@code .linear} targets share the name per FR7). */
    static Path targetForSourceExplicit(Path source, ConvertFormat to) {
        String ext = to == null ? RegionFileFormat.LINEAR_EXTENSION : to.extension();
        String name = source.getFileName().toString();
        for (String known : new String[] {RegionFileFormat.ANVIL_EXTENSION, RegionFileFormat.LINEAR_EXTENSION,
                RegionFileFormat.B_LINEAR_EXTENSION}) {
            if (name.endsWith(known)) {
                name = name.substring(0, name.length() - known.length()) + ext;
                return source.resolveSibling(name);
            }
        }
        return source.resolveSibling(name + ext);
    }

    /** Grid for explicit targets (0/whitelist-miss => format default; Pumpkin 2, V2/V3 8). */
    static int resolveExplicitGrid(ConvertFormat to, int requestedGrid) {
        if (to == ConvertFormat.PUMPKIN) {
            return net.linear.LinearBucketGrid.isValid(requestedGrid) ? requestedGrid : PUMPKIN_DEFAULT_GRID;
        }
        if (to == ConvertFormat.V2 || to == ConvertFormat.V3) {
            return net.linear.LinearBucketGrid.isValid(requestedGrid) ? requestedGrid : BUCKETED_DEFAULT_GRID;
        }
        return requestedGrid;
    }

    /** Maps explicit {@code to} to a staging implementation (V1/V2/V3 via LinearRegionFile). */
    static net.linear.LinearImplementation implForExplicit(ConvertFormat to) {
        if (to == ConvertFormat.V2) {
            return net.linear.LinearImplementation.V2;
        }
        if (to == ConvertFormat.V3) {
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
    public static String explicitParamsDetail(ConvertFormat to, int level, int requestedGrid) {
        int clamped = RegionFileFormat.clampCompressionLevel(level);
        if (to == ConvertFormat.PUMPKIN) {
            return "level ignored for pumpkin (fixed 1), grid " + resolveExplicitGrid(to, requestedGrid);
        }
        if (to == ConvertFormat.V2 || to == ConvertFormat.V3) {
            return "level " + clamped + ", grid " + resolveExplicitGrid(to, requestedGrid);
        }
        return "level " + clamped;
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
     * preserved) or escalates when the target path is occupied.
     */
    static void convertSingleFileExplicit(Path source, Path finalTarget, int compressionLevel,
            int requestedGrid, ConvertFormat from, ConvertFormat to) throws IOException {
        if (to == null || to == ConvertFormat.AUTO || to == ConvertFormat.LINEAR) {
            throw new IOException("Explicit target must be a concrete format, got " + to + " for " + source
                + " (--to linear means V1, normalized at parse time)");
        }
        RegionName coords = parseRegionName(source.getFileName().toString());
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
            if (sourceSize != 0L && sourceSize < MIN_ANVIL_SIZE) {
                throw new IOException("Source too small to hold a valid .mca header ("
                    + sourceSize + " bytes): " + source);
            }
        } else if (sourceLinear) {
            if (sourceSize != 0L && sourceSize <= MIN_VALID_SIZE) {
                throw new IOException("Source too small to hold a valid .linear header ("
                    + sourceSize + " bytes): " + source);
            }
            if (sourceSize != 0L) {
                // Detect-then-parse pre-check: fail-closed with diagnosis (never wire-byte alone).
                // Validates full structure (features terminate, table bounds, per-bucket
                // xxhash, footer, Pumpkin coords) without decompressing payloads.
                detectLinearFormat(source);
                checkHeaderSanity(source);
            }
        } else {
            if (sourceSize != 0L && sourceSize < MIN_BLINEAR_SIZE) {
                throw new IOException("Source too small to hold a valid .b_linear header ("
                    + sourceSize + " bytes): " + source);
            }
            if (sourceSize != 0L) {
                checkHeaderSanity(source);
            }
        }
        // Explicit --from filter (fail-closed, never silently convert the wrong layout).
        // LINEAR matches any linear-family layout (V1/V2/V3/PUMPKIN); AUTO accepts any.
        // 0-byte files are trivially empty (materialize-empty path below) and skip the filter
        // (operator detail surfaced in the CONVERTED line by the caller).
        if (from != null && from != ConvertFormat.AUTO && sourceSize != 0L) {
            ConvertFormat actual;
            try {
                if (sourceLinear) {
                    actual = actualFormatOf(source);
                } else if (sourceAnvil) {
                    actual = ConvertFormat.MCA;
                } else {
                    actual = ConvertFormat.B_LINEAR;
                }
            } catch (IOException corrupt) {
                throw new IOException("Source layout does not satisfy --from " + from + ": "
                    + corrupt.getMessage() + ": " + source, corrupt);
            }
            boolean empty = false;
            try (AbstractRegionFile probe = openProbed(source, source.getParent(), level)) {
                empty = countCopyableChunks(probe, coords.x(), coords.z()) == 0;
            } catch (Exception probeFail) {
                throw new IOException("Source layout does not satisfy --from " + from + ": "
                    + probeFail.getMessage() + ": " + source, probeFail);
            }
            if (!empty && !matchesFrom(actual, from)) {
                throw new IOException("Source is " + actual + ", job requires --from " + from + ": " + source);
            }
        }

        if (to == ConvertFormat.MCA) {
            // Explicit toward anvil reuses the reverse chunk-copy (legs envelop via sink).
            // Same-path cannot happen (different extensions); delegate to the legacy single-file
            // reverse (staging + plain move, validate-before-delete by the caller).
            if (samePath) {
                throw new IOException("Refusing same-path explicit convert (mixed-pair escalation, both kept): "
                    + source);
            }
            convertSingleFileReverse(source, finalTarget, level);
            return;
        }
        if (to == ConvertFormat.B_LINEAR) {
            if (samePath) {
                throw new IOException("Refusing same-path explicit convert (mixed-pair escalation, both kept): "
                    + source);
            }
            convertSingleFileToFormat(source, finalTarget, level, RegionFileFormat.B_LINEAR);
            return;
        }
        if (to == ConvertFormat.PUMPKIN) {
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
        Path stagingDir = resolveRecreateDirectory(folder);
        Files.createDirectories(stagingDir);
        Path stagingTarget = stagingDir.resolve(finalTarget.getFileName().toString());
        int sourceCount;
        try (AbstractRegionFile src = openProbed(source, folder, level)) {
            sourceCount = countCopyableChunks(src, coords.x(), coords.z());
            if (sourceCount == 0) {
                materializeEmptyTarget(stagingTarget, level,
                    ChunkKey.of(coords.x() * 32, coords.z() * 32));
                // Re-stage with the requested impl/grid when it is not the default V1:
                // materializeEmptyTarget always writes V1, so rewrite buckets when needed.
                if (impl != net.linear.LinearImplementation.V1) {
                    restageAsImpl(stagingTarget, level, impl, grid);
                }
            } else {
                try (LinearRegionFile dst = new LinearRegionFile(stagingTarget, level, impl, grid)) {
                    copyChunkRange(src, dst, coords);
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
    static void restageAsImpl(Path stagingTarget, int level, net.linear.LinearImplementation impl, int grid)
            throws IOException {
        Path tmp = stagingTarget.resolveSibling(stagingTarget.getFileName() + ".restage-tmp");
        Files.deleteIfExists(tmp);
        try (LinearRegionFile src = new LinearRegionFile(stagingTarget, level)) {
            RegionName coords = parseRegionName(stagingTarget.getFileName().toString());
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
     * Same-path {@code .linear} -> {@code .linear} (V1/V2/V3) via staging +
     * validate-before-swap + atomic-swap (never in-place overwrite, never
     * delete-after-swap). The staging file is validated against the live source
     * BEFORE the swap; on failure the staging is dropped and the source stands.
     */
    static void convertSingleFileToLinearSamePath(Path source, int level,
            net.linear.LinearImplementation impl, int grid) throws IOException {
        RegionName coords = parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Not a convertible region file: " + source);
        }
        Path folder = source.getParent();
        Path stagingDir = resolveRecreateDirectory(folder);
        Files.createDirectories(stagingDir);
        Path staging = stagingDir.resolve(source.getFileName().toString());
        int sourceCount;
        try (AbstractRegionFile src = openProbed(source, folder, level)) {
            sourceCount = countCopyableChunks(src, coords.x(), coords.z());
            if (sourceCount == 0) {
                materializeEmptyTarget(staging, level, ChunkKey.of(coords.x() * 32, coords.z() * 32));
                if (impl != net.linear.LinearImplementation.V1) {
                    restageAsImpl(staging, level, impl, grid);
                }
            } else {
                try (LinearRegionFile dst = new LinearRegionFile(staging, level, impl, grid)) {
                    copyChunkRange(src, dst, coords);
                    dst.flush();
                }
            }
        }
        // Validate the STAGING file against the live source BEFORE the swap.
        String detail = validateStagingAgainstSource(source, staging);
        // Layered dirty guards (collection-time skip is primary; this pre-swap
        // recheck is secondary): abort here when the folder dirtied during
        // conversion, so a live file is never swapped underneath its writer.
        // Abort => DEFERRED (source untouched, staging cleaned), never FAILED.
        String preSwapDeferred = preDeleteSkip(source, FORWARD_SKIP);
        if (preSwapDeferred != null) {
            try {
                Files.deleteIfExists(staging);
            } catch (Exception ignored) {
            }
            throw new DeferredBeforeSwapException("deferred before swap: " + preSwapDeferred);
        }
        // Atomic swap (fallback plain move where the FS forbids atomics).
        try {
            Files.move(staging, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(staging, source, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Validates a same-path staging file against its live source (count parity +
     * header sanity + full payload drain of the staging file). Returns detail.
     */
    static String validateStagingAgainstSource(Path source, Path staging) throws IOException {
        RegionName coords = parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable region name: " + source);
        }
        int sourceCount;
        try (AbstractRegionFile src = openProbed(source, source.getParent(),
            RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
            sourceCount = countCopyableChunks(src, coords.x(), coords.z());
        } catch (Exception e) {
            throw new IOException("Cannot open/count source " + source + ": " + e.getMessage(), e);
        }
        if (!Files.isRegularFile(staging)) {
            throw new IOException("Staging missing: " + staging);
        }
        long size = Files.size(staging);
        if (size <= MIN_VALID_SIZE) {
            throw new IOException("Staging too small (" + size + " bytes): " + staging);
        }
        checkHeaderSanity(staging);
        try (AbstractRegionFile dst = openProbed(staging, staging.getParent(),
            RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
            int targetCount = countChunks(dst, coords.x(), coords.z());
            if (!countsMatch(sourceCount, targetCount)) {
                throw new IOException("Chunk-count mismatch source=" + sourceCount + " target=" + targetCount
                    + " for " + source.getFileName());
            }
            verifyTargetPayloads(dst, coords.x(), coords.z());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot open/count staging " + staging + ": " + e.getMessage(), e);
        }
        return "chunks=" + sourceCount + " size=" + size + "B header ok, payloads verified (pre-swap)";
    }

    /**
     * Source -> Pumpkin ({@code .linear} per FR7).
     *
     * <p>{@code .linear} sources use the canonical slot tables
     * (detect-then-parse via their Version, preserving stored timestamps with
     * Pumpkin u64->i32 clamping); {@code .mca}/{@code .b_linear} sources have
     * no Version, so they copy chunk-by-chunk into a temp V1 image first (the
     * existing byte-identical forward path) and that temp image supplies the
     * slots — no new envelope code, no payload reinterpretation.
     * Same-path ({@code source.equals(finalTarget)}) stages to tmp, validates
     * the tmp BEFORE any swap, then atomic-swaps (never in-place overwrite).
     */
    static void convertSingleFileToPumpkin(Path source, Path finalTarget, int level, int requestedGrid,
            boolean samePath) throws IOException {
        RegionName coords = parseRegionName(source.getFileName().toString());
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
            Path stagingDir = resolveRecreateDirectory(folder);
            Files.createDirectories(stagingDir);
            Path tmpV1 = stagingDir.resolve(source.getFileName() + ".pumpkin-tmpV1.linear");
            Files.deleteIfExists(tmpV1);
            try {
                convertSingleFileToFormat(source, tmpV1, level, RegionFileFormat.LINEAR);
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
            int grid = resolveExplicitGrid(ConvertFormat.PUMPKIN, requestedGrid);
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
                image = net.linear.versions.PumpkinLinear.shared().flush(slots, params);
            } catch (IOException e) {
                throw new IOException(e.getMessage() + " (pumpkin flush)", e);
            }
            Files.createDirectories(tmp.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                net.linear.LinearDirectStreams.writeFully(channel, image);
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
            // Abort => DEFERRED (source untouched, tmp cleaned), never FAILED.
            String preSwapDeferred = preDeleteSkip(source, FORWARD_SKIP);
            if (preSwapDeferred != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
                throw new DeferredBeforeSwapException("deferred before swap: " + preSwapDeferred);
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
        Path stagingDir = resolveRecreateDirectory(folder);
        Files.createDirectories(stagingDir);
        Path staging = stagingDir.resolve(finalTarget.getFileName().toString());
        // Reuse the tmp+fsync discipline by flushing to the staging path via a temp sibling.
        Path stagingTmp = staging.resolveSibling(staging.getFileName() + ".tmp");
        int grid = resolveExplicitGrid(ConvertFormat.PUMPKIN, requestedGrid);
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
            image2 = net.linear.versions.PumpkinLinear.shared().flush(slots, params2);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " (pumpkin flush)", e);
        }
        try (FileChannel channel = FileChannel.open(stagingTmp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            net.linear.LinearDirectStreams.writeFully(channel, image2);
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

    /** Validates a Pumpkin staging/tmp file against its live source (pre-swap). */
    static void validatePumpkinStagingAgainstSource(Path source, Path staging) throws IOException {
        RegionName coords = parseRegionName(source.getFileName().toString());
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
            try (AbstractRegionFile src = openProbed(source, source.getParent(),
                RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                sourceCount = countCopyableChunks(src, coords.x(), coords.z());
            } catch (Exception e) {
                throw new IOException("Cannot open/count source " + source + ": " + e.getMessage(), e);
            }
        }
        // Pumpkin targets validate via detector==PUMPKIN + full payload parse
        // (decompress every bucket, count parity).
        assertPumpkinImage(staging, coords.x(), coords.z());
        try (AbstractRegionFile dst = openProbed(staging, staging.getParent(),
            RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
            int targetCount = countChunks(dst, coords.x(), coords.z());
            if (!countsMatch(sourceCount, targetCount)) {
                throw new IOException("Chunk-count mismatch source=" + sourceCount + " target=" + targetCount
                    + " for " + source.getFileName());
            }
            verifyTargetPayloads(dst, coords.x(), coords.z());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot open/count pumpkin staging " + staging + ": " + e.getMessage(), e);
        }
    }

    /**
     * Asserts a file image is Pumpkin (detector==PUMPKIN) and fully parses
     * (decompress every bucket, hash chain verifies, footer validates).
     * Fail-closed with diagnosis; never falls through to another layout.
     */
    static void assertPumpkinImage(Path file, int regionX, int regionZ) throws IOException {
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
            net.linear.versions.PumpkinLinear.shared().parse(image, probe);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " in " + file, e);
        }
        // Count parity is checked by the caller; full-bucket decompression happened in parse
        // (every bucket's zstd frame inflated, every xxhash verified fail-high).
    }

    /**
     * Explicit validation (validate-then-delete preserved).
     * Pumpkin targets: detector==PUMPKIN + full payload parse (decompress every
     * bucket, count parity). Pumpkin sources: successful canonical parse.
     * All other pairs delegate to the legacy {@link #validatePair} byte-identically.
     */
    static String validateExplicitPair(Path source, Path target, ConvertFormat from, ConvertFormat to)
            throws IOException {
        RegionName coords = parseRegionName(source.getFileName().toString());
        if (coords == null) {
            throw new IOException("Unparseable region name: " + source);
        }
        if (to == ConvertFormat.PUMPKIN) {
            int sourceCount;
            String sourceName = source.getFileName().toString();
            boolean targetIsSource = source.equals(target);
            if (targetIsSource) {
                // Same-file swap already validated pre-swap; re-assert the swapped image.
                assertPumpkinImage(target, coords.x(), coords.z());
                try (AbstractRegionFile dst = openProbed(target, target.getParent(),
                    RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                    int n = countChunks(dst, coords.x(), coords.z());
                    verifyTargetPayloads(dst, coords.x(), coords.z());
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
                try (AbstractRegionFile src = openProbed(source, source.getParent(),
                    RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                    sourceCount = countCopyableChunks(src, coords.x(), coords.z());
                } catch (Exception e) {
                    throw new IOException("Cannot open/count source " + source + ": " + e.getMessage(), e);
                }
            }
            if (!Files.isRegularFile(target)) {
                throw new IOException("Target missing: " + target);
            }
            long size = Files.size(target);
            if (size <= MIN_VALID_SIZE) {
                throw new IOException("Target too small (" + size + " bytes): " + target);
            }
            assertPumpkinImage(target, coords.x(), coords.z());
            try (AbstractRegionFile dst = openProbed(target, target.getParent(),
                RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                int targetCount = countChunks(dst, coords.x(), coords.z());
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
            return "chunks=" + sourceCount + " size=" + size + "B pumpkin ok, payloads verified";
        }
        if (to == ConvertFormat.MCA) {
            // Explicit toward anvil uses the reverse validation (anvil size + parity +
            // full payload drain); Pumpkin sources validate by successful canonical parse
            // inside the reverse open (fail-closed with detector diagnosis on corrupt).
            return validateReversePair(source, target);
        }
        return validatePair(source, target);
    }

    /**
     * Explicit folder conversion toward {@code to} with {@code --from} filtering
     * ({@code AUTO} = detect, {@code LINEAR} = any linear-family layout).
     * Same validate-before-delete, 3-attempt requeue and
     * protection-mode semantics as the legacy paths; same-extension converts
     * stage to tmp then atomic-swap (never in-place overwrite of a live source).
     * {@code LINEAR} is never a concrete target (the parser normalizes
     * {@code --to linear} to {@code V1}); passing it here throws.
     */
    public static ConversionSummary convertRegionFolderExplicit(Path regionFolder, int compressionLevel,
            int requestedGrid, Listener listener, boolean haltsServer, ConvertFormat from, ConvertFormat to)
            throws ConversionProtectionException {
        Objects.requireNonNull(regionFolder, "regionFolder");
        if (to == null || to == ConvertFormat.AUTO || to == ConvertFormat.LINEAR) {
            throw new IllegalArgumentException("explicit target must be concrete, got " + to
                + " (--to linear means V1, normalized at parse time)");
        }
        ConvertFormat effectiveFrom = from == null ? ConvertFormat.AUTO : from;
        final Listener events = listener != null ? listener : new Listener() {
            @Override
            public void onFile(FileResult r) {}
            @Override
            public void onRetry(Path source, int attempt, String reason) {}
        };
        final int level = RegionFileFormat.clampCompressionLevel(compressionLevel);

        int cleaned = cleanRecreateDirectory(regionFolder);
        LinearFlushCoordinator.evict(resolveRecreateDirectory(regionFolder));
        LOGGER.info("Linear explicit conversion: cleaned " + cleaned + " orphan file(s) from new_"
            + " staging beside " + regionFolder + " (from=" + effectiveFrom + " to=" + to + ")");

        List<FileResult> preFailures = new ArrayList<>();
        List<ExplicitCandidate> candidates =
            collectExplicitCandidates(regionFolder, events, preFailures, effectiveFrom, to);

        if (candidates.isEmpty() && preFailures.isEmpty()) {
            return new ConversionSummary(0, 0, 0, 0, List.of());
        }

        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
        AtomicInteger nameSeq = new AtomicInteger(0);
        ExecutorService pool = new ThreadPoolExecutor(
            threads, threads, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r);
                t.setName("linear-explicit-" + nameSeq.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

        List<Future<TaskOutcome>> futures = new ArrayList<>(candidates.size());
        for (ExplicitCandidate c : candidates) {
            futures.add(pool.submit(new ExplicitFileTask(c.source, c.target, level, requestedGrid,
                events, effectiveFrom, to, c.samePath)));
        }
        pool.shutdown();

        List<FileResult> terminal = new ArrayList<>(preFailures);
        int converted = 0;
        int validated = 0;
        int deleted = 0;
        for (int i = 0; i < futures.size(); i++) {
            Future<TaskOutcome> f = futures.get(i);
            TaskOutcome out;
            try {
                out = f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ExplicitCandidate lost = candidates.get(i);
                terminal.add(new FileResult(lost.source, lost.target, Status.FAILED,
                    "interrupted while joining explicit worker: " + String.valueOf(e.getMessage())));
                continue;
            } catch (Exception e) {
                ExplicitCandidate lost = candidates.get(i);
                terminal.add(new FileResult(lost.source, lost.target, Status.FAILED,
                    "explicit worker future failed: " + e.getClass().getSimpleName() + ": "
                        + String.valueOf(e.getMessage())));
                continue;
            }
            terminal.add(out.result());
            if (out.result().status() == Status.DELETED) {
                converted++;
                validated++;
                deleted++;
            }
        }
        try {
            if (!pool.awaitTermination(1L, TimeUnit.HOURS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        LinearFlushCoordinator.evict(resolveRecreateDirectory(regionFolder));

        List<FileResult> failures = new ArrayList<>();
        for (FileResult r : terminal) {
            if (r.status() == Status.FAILED) {
                failures.add(r);
            }
        }
        if (!failures.isEmpty()) {
            ConversionSummary summary =
                new ConversionSummary(converted, validated, deleted, failures.size(), List.copyOf(failures));
            logProtectionAlert(regionFolder, summary, haltsServer,
                "Re-run explicitly per failed file, e.g. /linear convert --from pumpkin --to "
                    + to.name().toLowerCase() + " (adjust --from/--to to the failed pair).");
            throw new ConversionProtectionException(
                "Linear explicit conversion halted with " + failures.size() + " failed file(s) in " + regionFolder
                    + " (from=" + effectiveFrom + " to=" + to + ")", summary);
        }
        return new ConversionSummary(converted, validated, deleted, 0, List.of());
    }

    private record ExplicitCandidate(Path source, Path target, boolean samePath) {}

    private static final class ExplicitFileTask implements Callable<TaskOutcome> {
        private final Path source;
        private final Path target;
        private final int level;
        private final int grid;
        private final Listener events;
        private final ConvertFormat from;
        private final ConvertFormat to;
        private final boolean samePath;

        ExplicitFileTask(Path source, Path target, int level, int grid, Listener events,
                ConvertFormat from, ConvertFormat to, boolean samePath) {
            this.source = source;
            this.target = target;
            this.level = level;
            this.grid = grid;
            this.events = events;
            this.from = from;
            this.to = to;
            this.samePath = samePath;
        }

        @Override
        public TaskOutcome call() {
            FileState state = FileState.CONVERTING;
            String lastError = "";
            int attempt = 0;
            while (shouldRetry(attempt)) {
                attempt++;
                try {
                    state = FileState.CONVERTING;
                    convertSingleFileExplicit(this.source, this.target, this.level, this.grid,
                        this.from, this.to);
                    state = transition(state, true);
                    String params = explicitParamsDetail(this.to, this.level, this.grid);
                    String emptyNote = "";
                    try {
                        if (Files.size(this.source) == 0L) {
                            emptyNote = ", 0-byte source, --from filter skipped (trivially empty)";
                        }
                    } catch (Exception ignored) {
                    }
                    safeOnFile(this.events, new FileResult(this.source, this.target, Status.CONVERTED,
                        "attempt " + attempt + " explicit conversion ok (from=" + this.from + " to=" + this.to
                            + (this.samePath ? " same-path swap" : "") + ", " + params + emptyNote + ")"));

                    state = FileState.VALIDATING;
                    String validation = validateExplicitPair(this.source, this.target, this.from, this.to);
                    state = transition(state, true);
                    safeOnFile(this.events, new FileResult(this.source, this.target, Status.VALIDATED,
                        "attempt " + attempt + " explicit validation ok: " + validation));

                    state = FileState.DELETING;
                    if (this.samePath) {
                        // Layered dirty guards: collection-time skip is primary (never
                        // queues live files); the pre-swap recheck inside
                        // convertSingleFileExplicit is secondary (aborts a swap when
                        // the folder dirtied mid-conversion). This post-swap check
                        // is retrospective only: the swap already happened, so it
                        // never undoes anything and only reports; the pre-swap
                        // abort is the effective guard (DEFERRED, source untouched,
                        // staging cleaned).
                        String deferred = preDeleteSkip(this.source, FORWARD_SKIP);
                        if (deferred != null) {
                            FileResult skipped = new FileResult(this.source, this.target, Status.SKIPPED,
                                "attempt " + attempt + " deferred before delete: " + deferred);
                            safeOnFile(this.events, skipped);
                            return new TaskOutcome(skipped);
                        }
                        state = transition(state, true);
                        FileResult done = new FileResult(this.source, this.target, Status.DELETED,
                            "explicit converted+validated+swapped on attempt " + attempt);
                        safeOnFile(this.events, done);
                        return new TaskOutcome(done);
                    }
                    String deferred = preDeleteSkip(this.source, FORWARD_SKIP);
                    if (deferred != null) {
                        cleanAttemptArtifacts(this.source, this.target);
                        // Pumpkin different-path targets also travel with a .tmp sibling
                        // that cleanAttemptArtifacts does not cover (it covers staging + .swp/.tmp/.swp.tmp).
                        try {
                            Path stagingTmp = resolveRecreateDirectory(this.source.getParent())
                                .resolve(this.target.getFileName().toString() + ".tmp");
                            Files.deleteIfExists(stagingTmp);
                            Files.deleteIfExists(this.target.resolveSibling(
                                this.target.getFileName() + ".tmp"));
                        } catch (Exception ignored) {
                        }
                        FileResult skipped = new FileResult(this.source, this.target, Status.SKIPPED,
                            "attempt " + attempt + " deferred before delete: " + deferred);
                        safeOnFile(this.events, skipped);
                        return new TaskOutcome(skipped);
                    }
                    Files.deleteIfExists(this.source);
                    if (this.source.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                        deleteBufferedSidecars(this.source);
                    }
                    // Finished B_LINEAR targets ship master-only (WAL pruned).
                    // Same-path swaps own their siblings internally; only
                    // diff-path targets are pruned here.
                    if (!this.samePath) {
                        pruneFinishedBufferedTarget(this.target);
                    }
                    state = transition(state, true);
                    FileResult done = new FileResult(this.source, this.target, Status.DELETED,
                        "explicit converted+validated+deleted on attempt " + attempt);
                    safeOnFile(this.events, done);
                    return new TaskOutcome(done);
                } catch (DeferredBeforeSwapException deferredAbort) {
                    // Pre-swap folder-dirty abort: DEFERRED, source untouched, staging
                    // already cleaned by the callee. Report SKIPPED immediately (no retry:
                    // re-running after quiesce reconverts cleanly).
                    FileResult skipped = new FileResult(this.source, this.target, Status.SKIPPED,
                        "attempt " + attempt + " deferred before swap: "
                            + String.valueOf(deferredAbort.getMessage()));
                    safeOnFile(this.events, skipped);
                    return new TaskOutcome(skipped);
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                    FileState failureStage = state;
                    state = FileState.FAILED;
                    if (!this.samePath && failureStage != FileState.DELETING) {
                        cleanAttemptArtifacts(this.source, this.target);
                        try {
                            Path stagingTmp = resolveRecreateDirectory(this.source.getParent())
                                .resolve(this.target.getFileName().toString() + ".tmp");
                            Files.deleteIfExists(stagingTmp);
                            Files.deleteIfExists(this.target.resolveSibling(
                                this.target.getFileName() + ".tmp"));
                        } catch (Exception ignored) {
                        }
                    }
                    if (this.samePath) {
                        // Same-path failures must never leave a tmp that a later run could
                        // mistake for a finished conversion; the live source stands untouched.
                        try {
                            Files.deleteIfExists(this.source.resolveSibling(
                                this.source.getFileName() + ".pumpkin-tmp"));
                            Path staging = resolveRecreateDirectory(this.source.getParent())
                                .resolve(this.source.getFileName().toString());
                            Files.deleteIfExists(staging);
                            Files.deleteIfExists(staging.resolveSibling(
                                staging.getFileName() + ".restage-tmp"));
                        } catch (Exception ignored) {
                        }
                    }
                    if (shouldRetry(attempt)) {
                        safeOnRetry(this.events, this.source, attempt, lastError);
                    }
                }
            }
            FileResult failed = new FileResult(this.source, this.target, Status.FAILED,
                "3 explicit attempts exhausted (" + state + "); last error: " + lastError);
            safeOnFile(this.events, failed);
            return new TaskOutcome(failed);
        }
    }

    /**
     * Explicit crash-resume step: collect filtered candidates and resolve shadow pairs.
     *
     * <p>Real rule (FR7): a same-path {@code .linear} convert
     * ({@code source.equals(target)}, same coords, same folder) is one inode,
     * never two files — it swaps via tmp + pre-swap validation + atomic swap.
     * A cross-extension shadow (e.g. an MCA source whose Pumpkin target path
     * is already occupied by an existing V1 {@code .linear} file — same target
     * path per FR7, different source extension) escalates to FAILED with both
     * files kept, never deleted, never silently overwritten. Valid shadows of
     * the SAME layout follow the legacy valid-shadow escalation; invalid
     * shadows of the same layout follow the legacy orphan-delete + requeue
     * (except {@code .linear} shadows, which are never auto-deleted).
     * Pumpkin shadows are NEVER deleted (startup auto-conversion must not
     * rewrite Pumpkin files): a Pumpkin shadow beside a convertible source
     * always escalates, even when count-invalid, so no silent cross-format
     * rewrite can happen.
     */
    static List<ExplicitCandidate> collectExplicitCandidates(Path regionFolder, Listener events,
            List<FileResult> preFailures, ConvertFormat from, ConvertFormat to) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                Matcher m = REGION_NAME.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                files.add(p);
            }
        } catch (IOException e) {
            LOGGER.warning("Linear explicit conversion: cannot list " + regionFolder + ": " + e.getMessage());
            return List.of();
        }

        // Group convertible files by coords to detect same-target ambiguity
        // (two sources mapping onto one target path would silently drop one).
        Map<String, List<Path>> byCoords = new HashMap<>();
        for (Path p : files) {
            Matcher m = REGION_NAME.matcher(p.getFileName().toString());
            if (!m.matches()) {
                continue;
            }
            String key = m.group(1) + "," + m.group(2);
            byCoords.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<ExplicitCandidate> out = new ArrayList<>();
        for (Map.Entry<String, List<Path>> e : byCoords.entrySet()) {
            List<Path> group = e.getValue();
            // Determine actual formats (detector for .linear; fail-closed entries escalate).
            Map<Path, ConvertFormat> actualByFile = new HashMap<>();
            Map<Path, String> detectError = new HashMap<>();
            for (Path p : group) {
                String name = p.getFileName().toString();
                try {
                    if (name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                        actualByFile.put(p, actualFormatOf(p));
                    } else if (name.endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                        actualByFile.put(p, ConvertFormat.MCA);
                    } else if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                        actualByFile.put(p, ConvertFormat.B_LINEAR);
                    }
                } catch (IOException corrupt) {
                    detectError.put(p, corrupt.getMessage());
                }
            }
            // Corrupt/ambiguous .linear files never queue: escalate with everything kept.
            for (Map.Entry<Path, String> bad : detectError.entrySet()) {
                Path p = bad.getKey();
                Path pseudoTarget = targetForSourceExplicit(p, to);
                preFailures.add(new FileResult(p, pseudoTarget, Status.FAILED,
                    "explicit detect failed (corrupt/ambiguous layout, both kept): " + bad.getValue()));
                LOGGER.severe("Linear explicit conversion: corrupt layout left untouched: " + p
                    + " (" + bad.getValue() + ")");
            }
            if (!detectError.isEmpty()) {
                continue;
            }

            // Filter by --from (AUTO accepts any, LINEAR accepts any linear family);
            // already-target layouts report SKIPPED.
            // F1: explicit jobs with a non-Pumpkin target must not silently convert
            // Pumpkin sources via --from auto (detect). Require an explicit
            // --from pumpkin (or --from linear family) so no silent cross-format
            // rewrite happens; SKIPPED here names that remedy, never queues.
            List<Path> matching = new ArrayList<>();
            for (Path p : group) {
                ConvertFormat actual = actualByFile.get(p);
                if (actual == null) {
                    continue;
                }
                if (actual == to) {
                    Path t = targetForSourceExplicit(p, to);
                    safeOnFile(events, new FileResult(p, t, Status.SKIPPED,
                        "already " + to + "; no conversion"));
                    continue;
                }
                if (actual == ConvertFormat.PUMPKIN && to != ConvertFormat.PUMPKIN
                        && from == ConvertFormat.AUTO) {
                    Path t = targetForSourceExplicit(p, to);
                    safeOnFile(events, new FileResult(p, t, Status.SKIPPED,
                        "Pumpkin layout, explicit conversion needs --from pumpkin"
                            + " (or --from linear) for Pumpkin sources;"
                            + " use /linear convert --from pumpkin --to " + to.name().toLowerCase()
                            + " explicit migration"));
                    LOGGER.info("Linear explicit conversion: Pumpkin source skipped"
                        + " (needs explicit --from pumpkin): " + p.getFileName()
                        + "; use /linear convert --from pumpkin --to " + to.name().toLowerCase()
                        + " explicit migration");
                    continue;
                }
                if (!matchesFrom(actual, from)) {
                    Path t = targetForSourceExplicit(p, to);
                    safeOnFile(events, new FileResult(p, t, Status.SKIPPED,
                        "source is " + actual + ", job requires --from " + from));
                    continue;
                }
                matching.add(p);
            }
            if (matching.isEmpty()) {
                continue;
            }
            if (matching.size() > 1) {
                // Ambiguous same-coordinates sources mapping onto one target path
                // (e.g. r.0.0.mca beside r.0.0.b_linear toward Pumpkin .linear):
                // escalate with everything kept (extends the legacy ambiguous rule).
                matching.sort(Comparator.comparing(p -> p.getFileName().toString()));
                Path firstTarget = targetForSourceExplicit(matching.get(0), to);
                // Same-target check: only escalate when they actually collide on one target.
                // Different-extension sources toward different-extension targets cannot collide
                // here (all share one explicit to), so any >1 matching group collides.
                preFailures.add(new FileResult(matching.get(0), firstTarget, Status.FAILED,
                    "ambiguous same-coordinates explicit sources ("
                        + matching.stream().map(p -> p.getFileName().toString())
                            .reduce((a, b) -> a + ", " + b).orElse("?")
                        + "); all files left untouched: keep one, move the rest aside, re-run"));
                LOGGER.severe("Linear explicit conversion: ambiguous same-coordinates sources left untouched: "
                    + matching);
                continue;
            }
            Path source = matching.get(0);
            Path target = targetForSourceExplicit(source, to);
            boolean samePath = source.equals(target);
            if (samePath) {
                // Same-file .linear -> .linear (V1<->Pumpkin, V1->V3, ...): allowed via
                // tmp + validate-before-swap + atomic-swap (never in-place overwrite).
                // Skip-gate parity with the legacy path (never queue a live file blindly).
                boolean skip;
                try {
                    skip = FORWARD_SKIP.test(source);
                } catch (RuntimeException predicateFailed) {
                    safeOnFile(events, new FileResult(source, target, Status.SKIPPED,
                        "skip check failed, deferred: " + String.valueOf(predicateFailed.getMessage())));
                    continue;
                }
                if (skip) {
                    safeOnFile(events, new FileResult(source, target, Status.SKIPPED,
                        "loaded or recently written, re-run to finish"));
                    continue;
                }
                out.add(new ExplicitCandidate(source, target, true));
                continue;
            }
            // Different paths: shadow handling with layout awareness (extends the legacy rule).
            Path shadow = null;
            for (Path p : group) {
                if (!p.equals(source) && p.equals(target)) {
                    shadow = p;
                    break;
                }
            }
            // Also consider any same-coords file already at the target path (target exists).
            if (shadow == null && Files.isRegularFile(target)) {
                shadow = target;
            }
            if (shadow == null) {
                boolean skip;
                try {
                    skip = FORWARD_SKIP.test(source);
                } catch (RuntimeException predicateFailed) {
                    safeOnFile(events, new FileResult(source, target, Status.SKIPPED,
                        "skip check failed, deferred: " + String.valueOf(predicateFailed.getMessage())));
                    continue;
                }
                if (skip) {
                    safeOnFile(events, new FileResult(source, target, Status.SKIPPED,
                        "loaded or recently written, re-run to finish"));
                    continue;
                }
                out.add(new ExplicitCandidate(source, target, false));
                continue;
            }
            // A target-path file exists: cross-extension shadow (same target path per
            // FR7, different source extension) escalates. Same-path .linear converts
            // are one inode (swap path above), never two files sharing a name.
            ConvertFormat shadowLayout = null;
            String shadowDetectError = null;
            try {
                String sname = shadow.getFileName().toString();
                if (sname.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                    shadowLayout = actualFormatOf(shadow);
                } else if (sname.endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                    shadowLayout = ConvertFormat.MCA;
                } else if (sname.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                    shadowLayout = ConvertFormat.B_LINEAR;
                }
            } catch (IOException corrupt) {
                shadowDetectError = corrupt.getMessage();
            }
            if (shadowDetectError != null) {
                // Corrupt shadow beside a readable source: never delete a possibly-Pumpkin
                // shadow blindly. If the source itself is also unreadable, escalate with both
                // kept; else the shadow is an orphan only when it is provably non-Pumpkin?
                // Fail-closed: a corrupt .linear shadow could be a torn Pumpkin write —
                // never auto-delete .linear shadows here; escalate for operator comparison.
                // (.mca/.b_linear corrupt shadows keep the legacy orphan path below.)
                String sname = shadow.getFileName().toString();
                if (sname.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                    preFailures.add(new FileResult(source, target, Status.FAILED,
                        "explicit shadow pair: target-path shadow corrupt (" + shadowDetectError
                            + "); both files left untouched for operator comparison"));
                    LOGGER.severe("Linear explicit conversion: corrupt shadow left untouched: source="
                        + source + " shadow=" + shadow);
                    continue;
                }
            }
            if (shadowLayout != null && shadowLayout != to) {
                // Cross-extension shadow (e.g. V1 file at the desired Pumpkin target
                // path beside an MCA source): escalate FAILED, keep both, never
                // delete, never silently overwrite. Same-path .linear converts are
                // one inode (swap path), never two same-name files.
                preFailures.add(new FileResult(source, target, Status.FAILED,
                    "explicit shadow pair: valid " + shadowLayout + " shadow where " + to
                        + " is desired; target path already holds a different layout (FR7);"
                        + " both files left untouched for operator comparison"));
                LOGGER.severe("Linear explicit conversion: cross-extension shadow left untouched: source="
                    + source + " shadow=" + shadow + " (shadow=" + shadowLayout + " desired=" + to + ")");
                continue;
            }
            // Same layout at the target path: legacy valid-shadow vs orphan logic.
            // Pumpkin shadows are NEVER auto-deleted (no silent cross-format rewrite).
            boolean targetValid;
            try {
                if (to == ConvertFormat.PUMPKIN) {
                    validateExplicitPair(source, shadow, from, to);
                } else {
                    validatePair(source, shadow);
                }
                targetValid = true;
            } catch (Exception invalid) {
                targetValid = false;
            }
            if (targetValid) {
                preFailures.add(new FileResult(source, target, Status.FAILED,
                    "explicit shadow pair: valid " + to + " shadow beside readable source;"
                        + " one-way conversion may leave unique chunks on either side;"
                        + " both files left untouched for operator comparison"));
                LOGGER.severe("Linear explicit conversion: valid shadow pair left untouched: source="
                    + source + " shadow=" + shadow);
                continue;
            }
            String sname2 = shadow.getFileName().toString();
            if (sname2.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                // Fail-closed for .linear shadows: a count-invalid .linear shadow could still
                // hold unique chunks (one-way caveat) or be a torn Pumpkin write; the legacy
                // orphan-delete would silently destroy it. Escalate for operator comparison.
                preFailures.add(new FileResult(source, target, Status.FAILED,
                    "explicit shadow pair: target-path .linear shadow invalid but source readable;"
                        + " both files left untouched for operator comparison (never auto-delete .linear shadows)"));
                LOGGER.severe("Linear explicit conversion: invalid .linear shadow left untouched: source="
                    + source + " shadow=" + shadow);
                continue;
            }
            boolean sourceReadable;
            try {
                try (AbstractRegionFile src = openProbed(source, source.getParent(),
                    RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                    RegionName c = parseRegionName(source.getFileName().toString());
                    countChunks(src, c.x(), c.z());
                }
                sourceReadable = true;
            } catch (Exception corrupt) {
                sourceReadable = false;
            }
            if (!sourceReadable) {
                preFailures.add(new FileResult(source, target, Status.FAILED,
                    "explicit shadow pair: source unreadable; shadow left untouched for inspection"));
                LOGGER.severe("Linear explicit conversion: shadow pair with corrupt source left untouched: "
                    + source);
                continue;
            }
            try {
                Files.deleteIfExists(shadow);
                if (shadow.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                    deleteBufferedSidecars(shadow);
                } else {
                    Files.deleteIfExists(shadow.resolveSibling(shadow.getFileName() + ".swp"));
                }
                LOGGER.info("Linear explicit conversion: removed invalid shadow " + shadow.getFileName()
                    + "; requeueing " + source.getFileName());
            } catch (IOException deleteFail) {
                preFailures.add(new FileResult(source, target, Status.FAILED,
                    "explicit shadow pair: cannot remove invalid orphan target " + shadow + ": "
                        + deleteFail.getMessage()));
                continue;
            }
            out.add(new ExplicitCandidate(source, target, false));
        }
        return out;
    }
}
