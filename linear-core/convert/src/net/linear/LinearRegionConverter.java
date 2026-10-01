package net.linear;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
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
    /** Latest linear version accepted on read. */
    static final byte VERSION_MAX = 2;
    /** Header + footer minimum: a valid file is larger than this. */
    static final long MIN_VALID_SIZE = 32L + 8L;
    /**
     * Minimum size of a valid {@code .mca} file: two 4 KiB sectors (offset
     * table + timestamp table). Vanilla pads every region file to at least
     * this on open, so a smaller file was never written by vanilla and is
     * treated as truncated/corrupt (FAILED path, never delete).
     */
    static final long MIN_ANVIL_SIZE = 8192L;

    private static final Pattern REGION_NAME =
        Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(mca|linear)$");
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
        LOGGER.info("Linear conversion: cleaned " + cleaned + " orphan file(s) from new_"
            + " staging beside " + regionFolder);

        List<FileResult> preFailures = new ArrayList<>();
        List<Path> candidates = collectCandidates(regionFolder, events, preFailures);

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
            Path target = targetForSource(source);
            futures.add(pool.submit(new FileTask(source, target, level, events)));
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
                terminal.add(new FileResult(lost, targetForSource(lost), Status.FAILED,
                    "interrupted while joining worker: " + String.valueOf(e.getMessage())));
                continue;
            } catch (Exception e) {
                // Should not happen (tasks catch everything), but never lose a file silently:
                // record it FAILED so it enters protection instead of vanishing.
                Path lost = candidates.get(i);
                terminal.add(new FileResult(lost, targetForSource(lost), Status.FAILED,
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
        if (coords == null || !source.getFileName().toString().endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            throw new IOException("Not a convertible .linear region file: " + source);
        }
        Path folder = source.getParent();
        Path stagingDir = resolveRecreateDirectory(folder);
        Files.createDirectories(stagingDir);
        Path stagingTarget = stagingDir.resolve(finalTarget.getFileName().toString());

        int level = RegionFileFormat.clampCompressionLevel(compressionLevel);

        // Truncated-source guard (mirror of the forward MIN_ANVIL_SIZE guard):
        // a valid .linear file is larger than the 40-byte header+footer
        // minimum. A nonzero file at or below that size was never validly
        // written (torn header) and fails closed (FAILED path, never delete).
        // A 0-byte file is trivially empty (created, never written) and
        // proceeds to the materialize-empty-target path. The header-magic
        // check runs BEFORE opening so a padding constructor cannot mask a
        // torn source that would otherwise count 0 and be deleted.
        long reverseSourceSize = Files.size(source);
        if (reverseSourceSize != 0L && reverseSourceSize <= MIN_VALID_SIZE) {
            throw new IOException("Reverse source too small to hold a valid .linear header ("
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
        Map<String, Path> lin = new HashMap<>();
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
                    lin.put(key, p);
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Linear reverse conversion: cannot list " + regionFolder + ": " + e.getMessage());
            return List.of();
        }

        List<Path> out = new ArrayList<>();
        for (Map.Entry<String, Path> e : lin.entrySet()) {
            Path source = e.getValue();
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
        sb.append("Direction: .linear -> .mca (reverse, downgrade to anvil)\n");
        sb.append("Converted=").append(summary.converted())
            .append(" Validated=").append(summary.validated())
            .append(" Deleted=").append(summary.deleted())
            .append(" Failed=").append(summary.failed()).append("\n");
        sb.append("Guarantee: linear sources are NEVER deleted before reverse validation passes.\n");
        sb.append("All failed files below still have their original .linear in place.\n");
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
        sb.append(" 3. Do NOT manually delete .linear files; re-run and reverse\n");
        sb.append("    conversion resumes automatically (unconverted sources remain).\n");
        sb.append(" 4. If a .mca shadow exists beside a failed .linear (reverse\n");
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

    private static final class FileTask implements Callable<TaskOutcome> {
        private final Path source;
        private final Path target;
        private final int level;
        private final Listener events;

        FileTask(Path source, Path target, int level, Listener events) {
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
                try {
                    state = FileState.CONVERTING;
                    convertSingleFile(this.source, this.target, this.level);
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
        if (format == null || format == RegionFileFormat.LINEAR) {
            return new LinearRegionFile(file, level);
        }
        throw new IOException("No RegionOpener registered for non-LINEAR open: " + file);
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
        RegionName coords = parseRegionName(source.getFileName().toString());
        if (coords == null || !source.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
            throw new IOException("Not a convertible .mca region file: " + source);
        }
        Path folder = source.getParent();
        Path stagingDir = resolveRecreateDirectory(folder);
        Files.createDirectories(stagingDir);
        Path stagingTarget = stagingDir.resolve(finalTarget.getFileName().toString());

        int level = RegionFileFormat.clampCompressionLevel(compressionLevel);

        // Truncated-source guard (HIGH-1b): vanilla pads every region file to
        // at least MIN_ANVIL_SIZE on first chunk write, so a nonzero file
        // below that size was never validly written (torn header) and is
        // treated as truncated/corrupt (FAILED path, never delete).
        // A 0-byte file is trivially empty (created, never written: the ctor
        // only CREATEs, header materializes on first write) and proceeds to
        // the materialize-empty-target path. hasChunk returns false on zeroed
        // offset tables without throwing, so without this check a truncated-
        // but-openable .mca would count 0 and be deleted. Throw here instead:
        // FAILED path, never delete.
        // Checked BEFORE opening so a padding constructor cannot mask it.
        long sourceSize = Files.size(source);
        if (sourceSize != 0L && sourceSize < MIN_ANVIL_SIZE) {
            throw new IOException("Source too small to hold a valid .mca header ("
                + sourceSize + " bytes): " + source);
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
                // Empty sources still materialize a valid empty .linear target
                // (HIGH-1a): validation requires the target to exist even for
                // 0-chunk sources, so the source is never deleted with nothing
                // preserved.
                materializeEmptyTarget(stagingTarget, level,
                    ChunkKey.of(coords.x() * 32, coords.z() * 32));
            } else {
                try (LinearRegionFile dst = new LinearRegionFile(stagingTarget, level)) {
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
            throw new IOException("Staging file missing after conversion: " + stagingTarget
                + " (copied " + copied + " of " + sourceCount + " chunks)");
        }
        Files.createDirectories(finalTarget.getParent());
        Files.move(stagingTarget, finalTarget, StandardCopyOption.REPLACE_EXISTING);
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
        if (size <= MIN_VALID_SIZE) {
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
            if (version < VERSION_MIN || version > VERSION_MAX) {
                throw new IOException("Unsupported linear version " + version + " in " + target);
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
            Files.deleteIfExists(target);
        } catch (Exception ignored) {
        }
    }

    /**
     * Crash-resume step (a): delete orphan {@code new_<folder>} content left by a
     * crashed run. Crash-before-move is safe to retry (source intact, fragment
     * ignored); without this cleanup a stale staged file could be mistaken for
     * fresh work on the next attempt.
     *
     * @return number of deleted entries
     */
    static int cleanRecreateDirectory(Path regionFolder) {
        Path staging = resolveRecreateDirectory(regionFolder);
        if (!Files.isDirectory(staging)) {
            return 0;
        }
        int removed = 0;
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
        Map<String, Path> mca = new HashMap<>();
        Map<String, Path> lin = new HashMap<>();
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
                    lin.put(key, p);
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Linear conversion: cannot list " + regionFolder + ": " + e.getMessage());
            return List.of();
        }

        List<Path> out = new ArrayList<>();
        for (Map.Entry<String, Path> e : mca.entrySet()) {
            Path source = e.getValue();
            Path shadow = lin.get(e.getKey());
            if (shadow == null) {
                // Soak parity (d-load, Oct 2026): forward used to queue every
                // .mca blindly while reverse deferred loaded files. A file the
                // server is actively writing must defer here too, or the copy
                // races live writes and the delete can orphan a live handle.
                boolean skip;
                try {
                    skip = FORWARD_SKIP.test(source);
                } catch (RuntimeException predicateFailed) {
                    // Fail-closed like reverse: a throwing skip check defers
                    // the file instead of converting a possibly loaded one.
                    safeOnFile(events, new FileResult(source, targetForSource(source), Status.SKIPPED,
                        "skip check failed, deferred: " + String.valueOf(predicateFailed.getMessage())));
                    continue;
                }
                if (skip) {
                    safeOnFile(events, new FileResult(source, targetForSource(source), Status.SKIPPED,
                        "loaded or recently written, re-run to finish"));
                    continue;
                }
                out.add(source);
                continue;
            }
            // Shadow pair: .mca wins the dual-read, so the .linear is suspect.
            Path target = targetForSource(source);
            boolean targetValid;
            try {
                validatePair(source, shadow);
                targetValid = true;
            } catch (Exception invalid) {
                targetValid = false;
            }
            if (targetValid) {
                // NEVER auto-delete a valid shadow. One-way-downgrade caveat:
                // a post-downgrade pair can hold UNIQUE chunks on both sides at
                // equal counts, so count parity cannot prove the shadow
                // redundant and deleting it could destroy the only copy of some
                // chunks. Escalate to protection with NEITHER side deleted; the
                // operator compares chunk contents before touching either file.
                preFailures.add(new FileResult(source, target, Status.FAILED,
                    "shadow pair: valid .linear shadow beside readable source; one-way downgrade may leave"
                        + " unique chunks on either side (count parity cannot prove redundancy);"
                        + " both files left untouched for operator comparison"));
                LOGGER.severe("Linear conversion: valid shadow pair left untouched for inspection: source="
                    + source + " shadow=" + shadow);
                continue;
            }
            // Orphan target is invalid: drop it and requeue the source.
            // If the source itself is corrupt, conversion attempts will fail
            // 3x and enter protection there (nothing deleted meanwhile).
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
                LOGGER.info("Linear conversion: removed invalid shadow " + shadow.getFileName()
                    + "; requeueing " + source.getFileName());
            } catch (IOException deleteFail) {
                preFailures.add(new FileResult(source, target, Status.FAILED,
                    "shadow pair: cannot remove invalid orphan target " + shadow + ": " + deleteFail.getMessage()));
                continue;
            }
            out.add(source);
        }
        // Already-converted (.linear-only) files are reported as SKIPPED for visibility.
        for (Map.Entry<String, Path> e : lin.entrySet()) {
            if (!mca.containsKey(e.getKey())) {
                Path t = e.getValue();
                Path pseudoSource = t.resolveSibling(
                    t.getFileName().toString().substring(0, t.getFileName().toString().length() - ".linear".length())
                        + RegionFileFormat.ANVIL_EXTENSION);
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
        String name = source.getFileName().toString();
        if (name.endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
            name = name.substring(0, name.length() - RegionFileFormat.ANVIL_EXTENSION.length())
                + RegionFileFormat.LINEAR_EXTENSION;
        } else {
            name = name + RegionFileFormat.LINEAR_EXTENSION;
        }
        return source.resolveSibling(name);
    }

    static Path resolveRecreateDirectory(Path directoryPath) {
        return directoryPath.resolveSibling(NEW_DIRECTORY_PREFIX + directoryPath.getFileName().toString());
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
        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("LINEAR CONVERSION PROTECTION MODE\n");
        sb.append("============================================================\n");
        sb.append("Folder: ").append(regionFolder.toAbsolutePath()).append("\n");
        sb.append("Converted=").append(summary.converted())
            .append(" Validated=").append(summary.validated())
            .append(" Deleted=").append(summary.deleted())
            .append(" Failed=").append(summary.failed()).append("\n");
        sb.append("Guarantee: sources are NEVER deleted before validation passes.\n");
        sb.append("All failed files below still have their original .mca in place.\n");
        sb.append("Partial .linear targets (if any) were left beside the source.\n");
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
        sb.append(" 3. Do NOT manually delete .mca files; re-run the server and\n");
        sb.append("    conversion resumes automatically (unconverted sources remain).\n");
        sb.append(" 4. If a .linear shadow exists beside a failed .mca (shadow-pair\n");
        sb.append("    escalation), compare chunk CONTENTS before touching either file:\n");
        sb.append("    equal counts do not prove redundancy after a one-way downgrade.\n");
        if (haltsServer) {
            sb.append("Server halts for inspection (caller halts on this exception).\n");
        } else {
            sb.append("The convert job recorded this failure and keeps running;"
                + " sources are untouched, re-run resumes automatically.\n");
        }
        sb.append("============================================================");
        LOGGER.severe(sb.toString());
    }
}
