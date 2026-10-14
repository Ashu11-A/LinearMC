package net.linear.convert.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.linear.convert.cleanup.ConversionAlerts;
import net.linear.convert.cleanup.ConversionCleanup;
import net.linear.convert.copy.ChunkCopier;
import net.linear.convert.routing.ExplicitConvertRouter;
import net.linear.convert.scan.CandidateScanner;
import net.linear.convert.validate.ConversionValidator;
import net.linear.AbstractRegionFile;
import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionConverter;
import net.linear.RegionFileFormat;

/**
 * Convert-pipeline orchestration: pool, per-file state machine, retry rule and
 * the forward/reverse/explicit folder-conversion runs.
 *
 * <p>Canonical home of the orchestration bodies; {@code LinearRegionConverter}
 * keeps the public API (records, seams, thin delegates) and version-specific
 * work stays in {@code ChunkCopier}, {@code ExplicitConvertRouter},
 * {@code CandidateScanner}, {@code ConversionValidator},
 * {@code ConversionCleanup} and {@code ConversionAlerts}, called directly.</p>
 */
public final class ConversionPipeline {

    private static final java.util.logging.Logger LOGGER =
        java.util.logging.Logger.getLogger(ConversionPipeline.class.getName());

    /** Attempts per file before protection mode (first try + 2 retries). */
    public static final int MAX_ATTEMPTS = 3;

    /** Sequence for {@link #poolName} thread prefixes (process-wide, monotonic). */
    private static final AtomicInteger POOL_SEQ = new AtomicInteger(0);

    private ConversionPipeline() {
    }

    /**
     * Bounded convert pool: {@code min(availableProcessors, 4)}. Daemon
     * threads so a halted server never hangs on them; the caller always joins
     * before returning (no fire-and-forget). Four threads cap the concurrent
     * open-file and zstd working sets during startup IO.
     */
    public static ExecutorService newConvertPool(String threadPrefix) {
        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
        AtomicInteger nameSeq = new AtomicInteger(0);
        return new ThreadPoolExecutor(
            threads,
            threads,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r);
                t.setName(threadPrefix + "-" + nameSeq.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
    }

    /** Retry rule: attempts are 1-based; attempts 1..2 may retry, attempt 3 is terminal. */
    public static boolean shouldRetry(int completedAttempts) {
        return completedAttempts < MAX_ATTEMPTS;
    }

    /**
     * Sorted copy of {@code candidates} by file name (deterministic queue
     * order; never mutates the caller's list). Pure orchestration helper.
     */
    public static List<Path> sortCandidates(List<Path> candidates) {
        List<Path> out = new ArrayList<>(candidates);
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    /**
     * Next thread name for a convert pool with the given {@code prefix}
     * (e.g. {@code "linear-explicit-3"}). Pure orchestration helper; the
     * caller still creates the pool via {@link #newConvertPool}.
     */
    public static String poolName(String prefix) {
        return prefix + "-" + POOL_SEQ.incrementAndGet();
    }

    /** State-machine step. Pure logic for unit tests. */
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
     * Listener dispatch that can never reclassify a file: a throwing observer
     * is logged and swallowed so its exception neither escapes the worker nor
     * changes the file's stage/outcome.
     */
    public static void safeOnFile(LinearRegionConverter.Listener events,
            LinearRegionConverter.FileResult r) {
        try {
            events.onFile(r);
        } catch (RuntimeException le) {
            LOGGER.warning("Linear conversion: listener onFile threw for " + r.source() + ": " + le);
        }
    }

    /** Same never-throw guarantee for retry callbacks. */
    public static void safeOnRetry(LinearRegionConverter.Listener events, Path source, int attempt,
            String reason) {
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
    public static String preDeleteSkip(Path source, java.util.function.Predicate<Path> skip) {
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
    public static class DeferredBeforeSwapException extends IOException {
        public DeferredBeforeSwapException(String detail) {
            super(detail);
        }
    }

    private record TaskOutcome(LinearRegionConverter.FileResult result) {}

    /**
     * Forward conversion toward {@code targetFormat} ({@code LINEAR} or
     * {@code B_LINEAR}): every convertible non-target file in
     * {@code regionFolder} is copied chunk-by-chunk to the target format
     * (staging + move, validate-before-delete, retry-3, crash-resume).
     */
    public static LinearRegionConverter.ConversionSummary runForwardConversion(Path regionFolder,
            int compressionLevel, LinearRegionConverter.Listener listener, boolean haltsServer,
            RegionFileFormat targetFormat) throws LinearRegionConverter.ConversionProtectionException {
        if (targetFormat != RegionFileFormat.LINEAR && targetFormat != RegionFileFormat.B_LINEAR) {
            throw new IllegalArgumentException("forward target must be LINEAR or B_LINEAR, got " + targetFormat);
        }
        Objects.requireNonNull(regionFolder, "regionFolder");
        final LinearRegionConverter.Listener events = listener != null ? listener
            : new LinearRegionConverter.Listener() {
                @Override
                public void onFile(LinearRegionConverter.FileResult r) {}
                @Override
                public void onRetry(Path source, int attempt, String reason) {}
            };
        final int level = RegionFileFormat.clampCompressionLevel(compressionLevel);

        // Crash-resume preamble, before any pool work.
        int cleaned = ConversionCleanup.cleanRecreateDirectory(regionFolder);
        // Staging writes self-report to the coordinator (like every core
        // write path), so drop any stale staging registration: a ghost
        // dirty entry would trip future quiesce gates ("save-all and retry"
        // forever) long after its files are gone.
        LinearFlushCoordinator.evict(CandidateScanner.resolveRecreateDirectory(regionFolder));
        LOGGER.info("Linear conversion: cleaned " + cleaned + " orphan file(s) from new_"
            + " staging beside " + regionFolder);

        List<LinearRegionConverter.FileResult> preFailures = new ArrayList<>();
        List<Path> candidates =
            CandidateScanner.collectForwardCandidates(regionFolder, events, preFailures, targetFormat);

        if (candidates.isEmpty() && preFailures.isEmpty()) {
            return new LinearRegionConverter.ConversionSummary(0, 0, 0, 0, List.of());
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
            Path target = CandidateScanner.targetForSource(source, targetFormat);
            futures.add(pool.submit(new FileTask(source, target, level, events, targetFormat)));
        }
        pool.shutdown();

        List<LinearRegionConverter.FileResult> terminal = new ArrayList<>(preFailures);
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
                terminal.add(new LinearRegionConverter.FileResult(lost,
                    CandidateScanner.targetForSource(lost, targetFormat),
                    LinearRegionConverter.Status.FAILED,
                    "interrupted while joining worker: " + String.valueOf(e.getMessage())));
                continue;
            } catch (Exception e) {
                // Should not happen (tasks catch everything), but never lose a file silently:
                // record it FAILED so it enters protection instead of vanishing.
                Path lost = candidates.get(i);
                terminal.add(new LinearRegionConverter.FileResult(lost,
                    CandidateScanner.targetForSource(lost, targetFormat),
                    LinearRegionConverter.Status.FAILED,
                    "worker future failed: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())));
                continue;
            }
            terminal.add(out.result());
            if (out.result().status() == LinearRegionConverter.Status.DELETED) {
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
        LinearFlushCoordinator.evict(CandidateScanner.resolveRecreateDirectory(regionFolder));

        List<LinearRegionConverter.FileResult> failures = new ArrayList<>();
        for (LinearRegionConverter.FileResult r : terminal) {
            if (r.status() == LinearRegionConverter.Status.FAILED) {
                failures.add(r);
            }
        }

        if (!failures.isEmpty()) {
            LinearRegionConverter.ConversionSummary summary = new LinearRegionConverter.ConversionSummary(converted,
                validated, deleted, failures.size(), List.copyOf(failures));
            ConversionAlerts.logProtectionAlert(regionFolder, summary, haltsServer, null, LOGGER);
            throw new LinearRegionConverter.ConversionProtectionException(
                "Linear conversion halted with " + failures.size() + " failed file(s) in " + regionFolder, summary);
        }
        return new LinearRegionConverter.ConversionSummary(converted, validated, deleted, 0, List.of());
    }

    /**
     * Mirror-image reverse conversion: every {@code r.*.linear} in
     * {@code regionFolder} is copied chunk-by-chunk to {@code r.*.mca}
     * (staging + plain move, validate-before-delete, retry-3, crash-resume).
     * Blocks the calling thread until every file settles.
     */
    public static LinearRegionConverter.ConversionSummary runReverseConversion(Path regionFolder,
            int compressionLevel, LinearRegionConverter.Listener listener, boolean haltsServer)
            throws LinearRegionConverter.ConversionProtectionException {
        Objects.requireNonNull(regionFolder, "regionFolder");
        final LinearRegionConverter.Listener events = listener != null ? listener
            : new LinearRegionConverter.Listener() {
                @Override
                public void onFile(LinearRegionConverter.FileResult r) {}
                @Override
                public void onRetry(Path source, int attempt, String reason) {}
            };
        final int level = RegionFileFormat.clampCompressionLevel(compressionLevel);

        int cleaned = ConversionCleanup.cleanRecreateDirectory(regionFolder);
        // Drop stale staging registrations (see forward preamble): ghost
        // dirty entries would trip future quiesce gates after their files
        // are gone.
        LinearFlushCoordinator.evict(CandidateScanner.resolveRecreateDirectory(regionFolder));
        LOGGER.info("Linear reverse conversion: cleaned " + cleaned + " orphan file(s) from new_"
            + " staging beside " + regionFolder);

        List<LinearRegionConverter.FileResult> preDone = new ArrayList<>();
        List<Path> candidates = CandidateScanner.collectReverseCandidates(regionFolder, events, preDone);

        if (candidates.isEmpty() && preDone.isEmpty()) {
            return new LinearRegionConverter.ConversionSummary(0, 0, 0, 0, List.of());
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
            Path target = CandidateScanner.targetForSourceReverse(source);
            futures.add(pool.submit(new ReverseFileTask(source, target, level, events)));
        }
        pool.shutdown();

        List<LinearRegionConverter.FileResult> terminal = new ArrayList<>(preDone);
        int converted = 0;
        int validated = 0;
        int deleted = 0;
        for (LinearRegionConverter.FileResult r : preDone) {
            if (r.status() == LinearRegionConverter.Status.DELETED) {
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
                terminal.add(new LinearRegionConverter.FileResult(lost,
                    CandidateScanner.targetForSourceReverse(lost), LinearRegionConverter.Status.FAILED,
                    "interrupted while joining reverse worker: " + String.valueOf(e.getMessage())));
                continue;
            } catch (Exception e) {
                Path lost = candidates.get(i);
                terminal.add(new LinearRegionConverter.FileResult(lost,
                    CandidateScanner.targetForSourceReverse(lost), LinearRegionConverter.Status.FAILED,
                    "reverse worker future failed: " + e.getClass().getSimpleName() + ": "
                        + String.valueOf(e.getMessage())));
                continue;
            }
            terminal.add(out.result());
            if (out.result().status() == LinearRegionConverter.Status.DELETED) {
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
        LinearFlushCoordinator.evict(CandidateScanner.resolveRecreateDirectory(regionFolder));

        List<LinearRegionConverter.FileResult> failures = new ArrayList<>();
        for (LinearRegionConverter.FileResult r : terminal) {
            if (r.status() == LinearRegionConverter.Status.FAILED) {
                failures.add(r);
            }
        }

        if (!failures.isEmpty()) {
            LinearRegionConverter.ConversionSummary summary = new LinearRegionConverter.ConversionSummary(converted,
                validated, deleted, failures.size(), List.copyOf(failures));
            ConversionAlerts.logReverseProtectionAlert(regionFolder, summary, haltsServer, LOGGER);
            throw new LinearRegionConverter.ConversionProtectionException(
                "Linear reverse conversion halted with " + failures.size() + " failed file(s) in " + regionFolder,
                summary);
        }
        return new LinearRegionConverter.ConversionSummary(converted, validated, deleted, 0, List.of());
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
    public static LinearRegionConverter.ConversionSummary runExplicitConversion(Path regionFolder,
            int compressionLevel, int requestedGrid, LinearRegionConverter.Listener listener, boolean haltsServer,
            LinearRegionConverter.ConvertFormat from, LinearRegionConverter.ConvertFormat to)
            throws LinearRegionConverter.ConversionProtectionException {
        Objects.requireNonNull(regionFolder, "regionFolder");
        if (to == null || to == LinearRegionConverter.ConvertFormat.AUTO
                || to == LinearRegionConverter.ConvertFormat.LINEAR) {
            throw new IllegalArgumentException("explicit target must be concrete, got " + to
                + " (--to linear means V1, normalized at parse time)");
        }
        LinearRegionConverter.ConvertFormat effectiveFrom =
            from == null ? LinearRegionConverter.ConvertFormat.AUTO : from;
        final LinearRegionConverter.Listener events = listener != null ? listener
            : new LinearRegionConverter.Listener() {
                @Override
                public void onFile(LinearRegionConverter.FileResult r) {}
                @Override
                public void onRetry(Path source, int attempt, String reason) {}
            };
        final int level = RegionFileFormat.clampCompressionLevel(compressionLevel);

        int cleaned = ConversionCleanup.cleanRecreateDirectory(regionFolder);
        LinearFlushCoordinator.evict(CandidateScanner.resolveRecreateDirectory(regionFolder));
        LOGGER.info("Linear explicit conversion: cleaned " + cleaned + " orphan file(s) from new_"
            + " staging beside " + regionFolder + " (from=" + effectiveFrom + " to=" + to + ")");

        List<LinearRegionConverter.FileResult> preFailures = new ArrayList<>();
        List<CandidateScanner.ExplicitCandidate> candidates =
            CandidateScanner.collectExplicitCandidates(regionFolder, events, preFailures, effectiveFrom, to);

        if (candidates.isEmpty() && preFailures.isEmpty()) {
            return new LinearRegionConverter.ConversionSummary(0, 0, 0, 0, List.of());
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
        for (CandidateScanner.ExplicitCandidate c : candidates) {
            futures.add(pool.submit(new ExplicitFileTask(c.source(), c.target(), level, requestedGrid,
                events, effectiveFrom, to, c.samePath())));
        }
        pool.shutdown();

        List<LinearRegionConverter.FileResult> terminal = new ArrayList<>(preFailures);
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
                CandidateScanner.ExplicitCandidate lost = candidates.get(i);
                terminal.add(new LinearRegionConverter.FileResult(lost.source(), lost.target(),
                    LinearRegionConverter.Status.FAILED,
                    "interrupted while joining explicit worker: " + String.valueOf(e.getMessage())));
                continue;
            } catch (Exception e) {
                CandidateScanner.ExplicitCandidate lost = candidates.get(i);
                terminal.add(new LinearRegionConverter.FileResult(lost.source(), lost.target(),
                    LinearRegionConverter.Status.FAILED,
                    "explicit worker future failed: " + e.getClass().getSimpleName() + ": "
                        + String.valueOf(e.getMessage())));
                continue;
            }
            terminal.add(out.result());
            if (out.result().status() == LinearRegionConverter.Status.DELETED) {
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
        LinearFlushCoordinator.evict(CandidateScanner.resolveRecreateDirectory(regionFolder));

        List<LinearRegionConverter.FileResult> failures = new ArrayList<>();
        for (LinearRegionConverter.FileResult r : terminal) {
            if (r.status() == LinearRegionConverter.Status.FAILED) {
                failures.add(r);
            }
        }
        if (!failures.isEmpty()) {
            LinearRegionConverter.ConversionSummary summary = new LinearRegionConverter.ConversionSummary(converted,
                validated, deleted, failures.size(), List.copyOf(failures));
            ConversionAlerts.logProtectionAlert(regionFolder, summary, haltsServer,
                "Re-run explicitly per failed file, e.g. /linear convert --from pumpkin --to "
                    + to.name().toLowerCase() + " (adjust --from/--to to the failed pair).",
                LOGGER);
            throw new LinearRegionConverter.ConversionProtectionException(
                "Linear explicit conversion halted with " + failures.size() + " failed file(s) in " + regionFolder
                    + " (from=" + effectiveFrom + " to=" + to + ")",
                summary);
        }
        return new LinearRegionConverter.ConversionSummary(converted, validated, deleted, 0, List.of());
    }

    private static final class FileTask implements Callable<TaskOutcome> {
        private final Path source;
        private final Path target;
        private final int level;
        private final LinearRegionConverter.Listener events;
        private final RegionFileFormat targetFormat;

        FileTask(Path source, Path target, int level, LinearRegionConverter.Listener events,
                RegionFileFormat targetFormat) {
            this.source = source;
            this.target = target;
            this.level = level;
            this.events = events;
            this.targetFormat = targetFormat;
        }

        @Override
        public TaskOutcome call() {
            LinearRegionConverter.FileState state = LinearRegionConverter.FileState.CONVERTING;
            String lastError = "";
            int attempt = 0;
            while (shouldRetry(attempt)) {
                attempt++;
                try {
                    state = LinearRegionConverter.FileState.CONVERTING;
                    ChunkCopier.convertSingleFileToFormat(this.source, this.target, this.level, this.targetFormat);
                    state = transition(state, true);
                    safeOnFile(this.events, new LinearRegionConverter.FileResult(this.source, this.target,
                        LinearRegionConverter.Status.CONVERTED, "attempt " + attempt + " conversion ok"));

                    state = LinearRegionConverter.FileState.VALIDATING;
                    String validation = ConversionValidator.validatePair(this.source, this.target);
                    state = transition(state, true);
                    safeOnFile(this.events, new LinearRegionConverter.FileResult(this.source, this.target,
                        LinearRegionConverter.Status.VALIDATED,
                        "attempt " + attempt + " validation ok: " + validation));

                    state = LinearRegionConverter.FileState.DELETING;
                    // TOCTOU adjacency: this recheck immediately precedes the
                    // source delete below with no intervening IO on the
                    // proceed path (see preDeleteSkip for the residual window
                    // and the shadow-pair backstop).
                    String deferred = preDeleteSkip(this.source, LinearRegionConverter.FORWARD_SKIP);
                    if (deferred != null) {
                        // Defer, do not delete: drop this attempt's uncommitted
                        // target so the source stands alone again. Leaving a
                        // validated target beside a live source would escalate
                        // to a shadow-pair protection halt on the next run;
                        // removing it restores the exact pre-run state and the
                        // re-run reconverts cleanly.
                        ConversionCleanup.cleanAttemptArtifacts(this.source, this.target);
                        LinearRegionConverter.FileResult skipped = new LinearRegionConverter.FileResult(this.source,
                            this.target, LinearRegionConverter.Status.SKIPPED,
                            "attempt " + attempt + " deferred before delete: " + deferred);
                        safeOnFile(this.events, skipped);
                        return new TaskOutcome(skipped);
                    }
                    Files.deleteIfExists(this.source);
                    if (this.source.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                        // Buffered sources carry scratch siblings that must not
                        // linger: nothing will ever open them again and the next
                        // boot would mistake the swap for live state.
                        ConversionCleanup.deleteBufferedSidecars(this.source);
                    }
                    // Finished B_LINEAR targets ship master-only (WAL pruned):
                    // validation reopened the target, recreating its sidecar.
                    ConversionCleanup.pruneFinishedBufferedTarget(this.target);
                    state = transition(state, true);
                    LinearRegionConverter.FileResult done = new LinearRegionConverter.FileResult(this.source,
                        this.target, LinearRegionConverter.Status.DELETED,
                        "converted+validated+deleted on attempt " + attempt);
                    safeOnFile(this.events, done);
                    return new TaskOutcome(done);
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                    LinearRegionConverter.FileState failureStage = state;
                    state = LinearRegionConverter.FileState.FAILED;
                    // DELETING means convert+validate already succeeded: the
                    // target is validated, so retry only the source delete.
                    // Wiping the validated target here would destroy the only
                    // good copy and force a full reconvert.
                    if (failureStage != LinearRegionConverter.FileState.DELETING) {
                        ConversionCleanup.cleanAttemptArtifacts(this.source, this.target);
                    }
                    if (shouldRetry(attempt)) {
                        safeOnRetry(this.events, this.source, attempt, lastError);
                    }
                }
            }
            LinearRegionConverter.FileResult failed = new LinearRegionConverter.FileResult(this.source, this.target,
                LinearRegionConverter.Status.FAILED,
                "3 attempts exhausted (" + state + "); last error: " + lastError);
            safeOnFile(this.events, failed);
            return new TaskOutcome(failed);
        }
    }

    private static final class ReverseFileTask implements Callable<TaskOutcome> {
        private final Path source;
        private final Path target;
        private final int level;
        private final LinearRegionConverter.Listener events;

        ReverseFileTask(Path source, Path target, int level, LinearRegionConverter.Listener events) {
            this.source = source;
            this.target = target;
            this.level = level;
            this.events = events;
        }

        @Override
        public TaskOutcome call() {
            LinearRegionConverter.FileState state = LinearRegionConverter.FileState.CONVERTING;
            String lastError = "";
            int attempt = 0;
            while (shouldRetry(attempt)) {
                attempt++;
                boolean skippedConvert = false;
                try {
                    state = LinearRegionConverter.FileState.CONVERTING;
                    if (Files.isRegularFile(this.target)) {
                        try {
                            ConversionValidator.validateReversePair(this.source, this.target);
                            skippedConvert = true;
                        } catch (Exception notYetValid) {
                            skippedConvert = false;
                        }
                    }
                    if (!skippedConvert) {
                        ChunkCopier.convertSingleFileReverse(this.source, this.target, this.level);
                    }
                    state = transition(state, true);
                    safeOnFile(this.events, new LinearRegionConverter.FileResult(this.source, this.target,
                        LinearRegionConverter.Status.CONVERTED,
                        "attempt " + attempt
                            + (skippedConvert ? " target already valid, skipped rewrite"
                                : " reverse conversion ok")));

                    state = LinearRegionConverter.FileState.VALIDATING;
                    String validation = ConversionValidator.validateReversePair(this.source, this.target);
                    state = transition(state, true);
                    safeOnFile(this.events, new LinearRegionConverter.FileResult(this.source, this.target,
                        LinearRegionConverter.Status.VALIDATED,
                        "attempt " + attempt + " reverse validation ok: " + validation));

                    state = LinearRegionConverter.FileState.DELETING;
                    // TOCTOU adjacency: this recheck immediately precedes the
                    // source delete below with no intervening IO on the
                    // proceed path (see preDeleteSkip for the residual window
                    // and the shadow-pair backstop).
                    String deferred = preDeleteSkip(this.source, LinearRegionConverter.REVERSE_SKIP);
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
                            ConversionCleanup.cleanAttemptArtifacts(this.source, this.target);
                        }
                        LinearRegionConverter.FileResult skipped = new LinearRegionConverter.FileResult(this.source,
                            this.target, LinearRegionConverter.Status.SKIPPED,
                            "attempt " + attempt + " deferred before delete: " + deferred);
                        safeOnFile(this.events, skipped);
                        return new TaskOutcome(skipped);
                    }
                    Files.deleteIfExists(this.source);
                    if (this.source.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                        // Buffered sources carry scratch siblings that must not
                        // linger: nothing will ever open them again and the next
                        // boot would mistake the swap for live state.
                        ConversionCleanup.deleteBufferedSidecars(this.source);
                    }
                    state = transition(state, true);
                    LinearRegionConverter.FileResult done = new LinearRegionConverter.FileResult(this.source,
                        this.target, LinearRegionConverter.Status.DELETED,
                        "reverse converted+validated+deleted on attempt " + attempt);
                    safeOnFile(this.events, done);
                    return new TaskOutcome(done);
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                    LinearRegionConverter.FileState reverseFailureStage = state;
                    state = LinearRegionConverter.FileState.FAILED;
                    // Skip the target wipe when the target was already valid:
                    // skipped rewrites (shadow-valid) and DELETING failures
                    // (convert+validate succeeded) retry only the source delete.
                    if (!skippedConvert
                            && reverseFailureStage != LinearRegionConverter.FileState.DELETING) {
                        ConversionCleanup.cleanAttemptArtifacts(this.source, this.target);
                    }
                    if (shouldRetry(attempt)) {
                        safeOnRetry(this.events, this.source, attempt, lastError);
                    }
                }
            }
            LinearRegionConverter.FileResult failed = new LinearRegionConverter.FileResult(this.source, this.target,
                LinearRegionConverter.Status.FAILED,
                "3 reverse attempts exhausted (" + state + "); last error: " + lastError);
            safeOnFile(this.events, failed);
            return new TaskOutcome(failed);
        }
    }

    private static final class ExplicitFileTask implements Callable<TaskOutcome> {
        private final Path source;
        private final Path target;
        private final int level;
        private final int grid;
        private final LinearRegionConverter.Listener events;
        private final LinearRegionConverter.ConvertFormat from;
        private final LinearRegionConverter.ConvertFormat to;
        private final boolean samePath;

        ExplicitFileTask(Path source, Path target, int level, int grid, LinearRegionConverter.Listener events,
                LinearRegionConverter.ConvertFormat from, LinearRegionConverter.ConvertFormat to, boolean samePath) {
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
            LinearRegionConverter.FileState state = LinearRegionConverter.FileState.CONVERTING;
            String lastError = "";
            int attempt = 0;
            while (shouldRetry(attempt)) {
                attempt++;
                try {
                    state = LinearRegionConverter.FileState.CONVERTING;
                    ExplicitConvertRouter.convertSingleFileExplicit(this.source, this.target, this.level, this.grid,
                        this.from, this.to);
                    state = transition(state, true);
                    String params = ExplicitConvertRouter.explicitParamsDetail(this.to, this.level, this.grid);
                    String emptyNote = "";
                    try {
                        if (Files.size(this.source) == 0L) {
                            emptyNote = ", 0-byte source, --from filter skipped (trivially empty)";
                        }
                    } catch (Exception ignored) {
                    }
                    safeOnFile(this.events, new LinearRegionConverter.FileResult(this.source, this.target,
                        LinearRegionConverter.Status.CONVERTED,
                        "attempt " + attempt + " explicit conversion ok (from=" + this.from + " to=" + this.to
                            + (this.samePath ? " same-path swap" : "") + ", " + params + emptyNote + ")"));

                    state = LinearRegionConverter.FileState.VALIDATING;
                    String validation =
                        ExplicitConvertRouter.validateExplicitPair(this.source, this.target, this.from, this.to);
                    state = transition(state, true);
                    safeOnFile(this.events, new LinearRegionConverter.FileResult(this.source, this.target,
                        LinearRegionConverter.Status.VALIDATED,
                        "attempt " + attempt + " explicit validation ok: " + validation));

                    state = LinearRegionConverter.FileState.DELETING;
                    if (this.samePath) {
                        // Layered dirty guards: collection-time skip is primary (never
                        // queues live files); the pre-swap recheck inside
                        // convertSingleFileExplicit is secondary (aborts a swap when
                        // the folder dirtied mid-conversion). This post-swap check
                        // is retrospective only: the swap already happened, so it
                        // never undoes anything and only reports; the pre-swap
                        // abort is the effective guard (DEFERRED, source untouched,
                        // staging cleaned).
                        String deferred = preDeleteSkip(this.source, LinearRegionConverter.FORWARD_SKIP);
                        if (deferred != null) {
                            LinearRegionConverter.FileResult skipped = new LinearRegionConverter.FileResult(
                                this.source, this.target, LinearRegionConverter.Status.SKIPPED,
                                "attempt " + attempt + " deferred before delete: " + deferred);
                            safeOnFile(this.events, skipped);
                            return new TaskOutcome(skipped);
                        }
                        state = transition(state, true);
                        LinearRegionConverter.FileResult done = new LinearRegionConverter.FileResult(this.source,
                            this.target, LinearRegionConverter.Status.DELETED,
                            "explicit converted+validated+swapped on attempt " + attempt);
                        safeOnFile(this.events, done);
                        return new TaskOutcome(done);
                    }
                    String deferred = preDeleteSkip(this.source, LinearRegionConverter.FORWARD_SKIP);
                    if (deferred != null) {
                        ConversionCleanup.cleanAttemptArtifacts(this.source, this.target);
                        // Pumpkin different-path targets also travel with a .tmp sibling
                        // that cleanAttemptArtifacts does not cover (it covers staging + .swp/.tmp/.swp.tmp).
                        try {
                            Path stagingTmp = CandidateScanner.resolveRecreateDirectory(this.source.getParent())
                                .resolve(this.target.getFileName().toString() + ".tmp");
                            Files.deleteIfExists(stagingTmp);
                            Files.deleteIfExists(this.target.resolveSibling(
                                this.target.getFileName() + ".tmp"));
                        } catch (Exception ignored) {
                        }
                        LinearRegionConverter.FileResult skipped = new LinearRegionConverter.FileResult(this.source,
                            this.target, LinearRegionConverter.Status.SKIPPED,
                            "attempt " + attempt + " deferred before delete: " + deferred);
                        safeOnFile(this.events, skipped);
                        return new TaskOutcome(skipped);
                    }
                    Files.deleteIfExists(this.source);
                    if (this.source.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                        ConversionCleanup.deleteBufferedSidecars(this.source);
                    }
                    // Finished B_LINEAR targets ship master-only (WAL pruned).
                    // Same-path swaps own their siblings internally; only
                    // diff-path targets are pruned here.
                    if (!this.samePath) {
                        ConversionCleanup.pruneFinishedBufferedTarget(this.target);
                    }
                    state = transition(state, true);
                    LinearRegionConverter.FileResult done = new LinearRegionConverter.FileResult(this.source,
                        this.target, LinearRegionConverter.Status.DELETED,
                        "explicit converted+validated+deleted on attempt " + attempt);
                    safeOnFile(this.events, done);
                    return new TaskOutcome(done);
                } catch (DeferredBeforeSwapException deferredAbort) {
                    // Pre-swap folder-dirty abort: DEFERRED, source untouched, staging
                    // already cleaned by the callee. Report SKIPPED immediately (no retry:
                    // re-running after quiesce reconverts cleanly).
                    LinearRegionConverter.FileResult skipped = new LinearRegionConverter.FileResult(this.source,
                        this.target, LinearRegionConverter.Status.SKIPPED,
                        "attempt " + attempt + " deferred before swap: "
                            + String.valueOf(deferredAbort.getMessage()));
                    safeOnFile(this.events, skipped);
                    return new TaskOutcome(skipped);
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                    LinearRegionConverter.FileState failureStage = state;
                    state = LinearRegionConverter.FileState.FAILED;
                    if (!this.samePath && failureStage != LinearRegionConverter.FileState.DELETING) {
                        ConversionCleanup.cleanAttemptArtifacts(this.source, this.target);
                        try {
                            Path stagingTmp = CandidateScanner.resolveRecreateDirectory(this.source.getParent())
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
                            Path staging = CandidateScanner.resolveRecreateDirectory(this.source.getParent())
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
            LinearRegionConverter.FileResult failed = new LinearRegionConverter.FileResult(this.source, this.target,
                LinearRegionConverter.Status.FAILED,
                "3 explicit attempts exhausted (" + state + "); last error: " + lastError);
            safeOnFile(this.events, failed);
            return new TaskOutcome(failed);
        }
    }
}
