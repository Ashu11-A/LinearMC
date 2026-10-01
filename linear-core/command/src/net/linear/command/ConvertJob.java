package net.linear.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.linear.LinearRegionConverter.FileResult;
import net.linear.LinearRegionConverter.Status;

/**
 * One conversion job: immutable spec plus mutable progress.
 *
 * <p>Spec fields ({@code id}, {@code direction}, {@code world}, {@code level},
 * {@code threads}, {@code dryRun}, {@code totalFiles}, {@code startedAtMs}) are
 * fixed at construction. Progress ({@code state}, {@code doneFiles},
 * {@code failedFiles}, {@code updatedAtMs}, detail lines) mutates via
 * synchronized methods; {@code state} is additionally volatile for lock-free reads.
 *
 * <p>Per-file truth: the converter emits one {@code CONVERTED}, one
 * {@code VALIDATED} and one terminal outcome per file, so only the terminal
 * outcomes ({@code DELETED}, {@code FAILED}, {@code SKIPPED}) advance the
 * {@code done/failed} counters. {@code CONVERTED} and {@code VALIDATED} only
 * move the {@code currentStep} ("Copying files" / "Checking files") so one
 * file is never counted three times.
 *
 * <p>Legal transitions: {@code QUEUED→RUNNING} ({@link #start},
 * or implicitly on the first {@link #recordFile}), {@code RUNNING→PAUSED}
 * ({@link #pause}), {@code PAUSED→RUNNING} ({@link #resume}),
 * {@code QUEUED|RUNNING|PAUSED→CANCELLED} ({@link #cancel}),
 * {@code RUNNING|PAUSED→DONE} ({@link #complete}),
 * {@code RUNNING|PAUSED→FAILED} ({@link #fail}). Anything else throws
 * {@link IllegalStateException}, except a repeat {@link #fail} on an already
 * {@code FAILED} job, which appends the reason and keeps working. Terminal
 * states ({@code DONE}, {@code FAILED}, {@code CANCELLED}) accept no further
 * file records.
 */
public final class ConvertJob {

    /** Detail lines kept per job (ring-buffer: oldest lines drop off). */
    static final int MAX_DETAILS = 20;

    private final UUID id;
    private final ConvertDirection direction;
    private final String world;
    private final int level;
    private final int threads;
    private final boolean dryRun;
    private final int totalFiles;
    private final long startedAtMs;

    private volatile JobState state;
    private volatile long updatedAtMs;
    private volatile long runStartedAtMs;
    private int doneFiles;
    private int failedFiles;
    private int retryCount;
    private int currentTry = 1;
    private String currentStep = "";
    private String lastFileShort = "";
    private final List<String> details = new ArrayList<>();

    /**
     * Creates a {@code QUEUED} job with a random id and current timestamps.
     *
     * @param totalFiles expected file count (progress denominator; may be 0 when unknown)
     * @throws IllegalArgumentException when {@code totalFiles} is negative
     */
    public ConvertJob(ConvertDirection direction, String world, int level,
        int threads, boolean dryRun, int totalFiles) {
        this(UUID.randomUUID(), direction, world, level, threads, dryRun, totalFiles,
            System.currentTimeMillis());
    }

    ConvertJob(UUID id, ConvertDirection direction, String world, int level,
        int threads, boolean dryRun, int totalFiles, long startedAtMs) {
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (level < 1 || level > 22) {
            throw new IllegalArgumentException("level must be 1..22, got " + level);
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1, got " + threads);
        }
        if (totalFiles < 0) {
            throw new IllegalArgumentException("totalFiles must be >= 0, got " + totalFiles);
        }
        this.id = id;
        this.direction = direction;
        this.world = world;
        this.level = level;
        this.threads = threads;
        this.dryRun = dryRun;
        this.totalFiles = totalFiles;
        this.startedAtMs = startedAtMs;
        this.state = JobState.QUEUED;
        this.updatedAtMs = startedAtMs;
        this.runStartedAtMs = 0L;
    }

    public UUID id() {
        return this.id;
    }

    /** First 8 chars of the id, used wherever a short id is shown. */
    public String shortId() {
        String full = this.id.toString();
        return full.length() <= 8 ? full : full.substring(0, 8);
    }

    public ConvertDirection direction() {
        return this.direction;
    }

    public String world() {
        return this.world;
    }

    public int level() {
        return this.level;
    }

    public int threads() {
        return this.threads;
    }

    public boolean dryRun() {
        return this.dryRun;
    }

    public int totalFiles() {
        return this.totalFiles;
    }

    public long startedAtMs() {
        return this.startedAtMs;
    }

    /** When the job left QUEUED ({@code start} or first record); 0 when never run. */
    public long runStartedAtMs() {
        return this.runStartedAtMs;
    }

    public JobState state() {
        return this.state;
    }

    /** Plain-words state: Waiting/Working/Paused/Finished/Stopped/Cancelled. */
    public String plainState() {
        switch (this.state) {
            case QUEUED:
                return "Waiting";
            case RUNNING:
                return "Working";
            case PAUSED:
                return "Paused";
            case DONE:
                return "Finished";
            case FAILED:
                return "Stopped";
            case CANCELLED:
                return "Cancelled";
            default:
                return this.state.name();
        }
    }

    public long updatedAtMs() {
        return this.updatedAtMs;
    }

    public synchronized int doneFiles() {
        return this.doneFiles;
    }

    public synchronized int failedFiles() {
        return this.failedFiles;
    }

    /** Completed files (done + failed). */
    public synchronized int completedFiles() {
        return this.doneFiles + this.failedFiles;
    }

    /**
     * Whole percent done ({@code 0..100}), or {@code -1} when the total is
     * unknown ({@code totalFiles <= 0}).
     */
    public synchronized int percent() {
        if (this.totalFiles <= 0) {
            return -1;
        }
        int completed = this.doneFiles + this.failedFiles;
        int pct = completed * 100 / this.totalFiles;
        if (pct < 0) {
            return 0;
        }
        return Math.min(pct, 100);
    }

    /**
     * Plain-words ETA in {@link LinearStatsFormat#humanMillisSince} style:
     * {@code "<age> left"} while working, {@code "calculating"} before the
     * first file settles, {@code "finished"} once nothing remains or the job
     * reached a terminal state.
     *
     * @param nowMs current wall time in millis (same clock as {@link #runStartedAtMs()})
     */
    public synchronized String humanEta(long nowMs) {
        if (this.state == JobState.DONE || this.state == JobState.FAILED
            || this.state == JobState.CANCELLED) {
            return "finished";
        }
        int completed = this.doneFiles + this.failedFiles;
        int remaining = this.totalFiles - completed;
        if (completed > 0 && remaining <= 0) {
            return "finished";
        }
        if (completed == 0 || this.runStartedAtMs <= 0L) {
            return "calculating";
        }
        long elapsed = Math.max(0L, nowMs - this.runStartedAtMs);
        long etaMs = elapsed * remaining / completed;
        return LinearStatsFormat.humanMillisSince(etaMs) + " left";
    }

    /** Current plain-words step ({@code ""} before the first file outcome). */
    public synchronized String currentStep() {
        return this.currentStep;
    }

    /** Short file name of the last recorded file ({@code r.0.0} form, never a full path). */
    public synchronized String lastFileShort() {
        return this.lastFileShort;
    }

    public synchronized int retryCount() {
        return this.retryCount;
    }

    /**
     * Current per-file try ({@code 1..3}): the converter reports the failed
     * attempt number on every retry, so the view tracks the file in flight
     * instead of a global retry sum across files.
     */
    public synchronized int currentTry() {
        if (this.currentTry < 1) {
            return 1;
        }
        return Math.min(this.currentTry, 3);
    }

    /** Records one file retry (attempt beyond the first try). */
    public synchronized void recordRetry() {
        this.retryCount++;
        this.currentTry = Math.min(this.retryCount + 1, 3);
        this.updatedAtMs = System.currentTimeMillis();
    }

    /**
     * Records one file retry for the converter-reported {@code attempt}
     * (1-based completed attempt). The next try shown is
     * {@code min(attempt + 1, 3)}; the global retry count still accumulates.
     */
    public synchronized void recordRetry(int attempt) {
        this.retryCount++;
        int next = attempt + 1;
        if (next < 1) {
            next = 1;
        }
        this.currentTry = Math.min(next, 3);
        this.updatedAtMs = System.currentTimeMillis();
    }

    /** Unmodifiable copy of the per-file detail lines (last {@value #MAX_DETAILS}). */
    public synchronized List<String> details() {
        return Collections.unmodifiableList(new ArrayList<>(this.details));
    }

    /**
     * Last failure reason in plain short form (short file name, never a full
     * path); {@code ""} when nothing has failed yet.
     */
    public synchronized String lastPlainReason() {
        for (int i = this.details.size() - 1; i >= 0; i--) {
            String line = this.details.get(i);
            if (line.contains("FAILED")) {
                return line;
            }
        }
        return "";
    }

    /** {@code QUEUED→RUNNING}. */
    public synchronized void start() {
        if (this.state != JobState.QUEUED) {
            throw new IllegalStateException("Cannot start convert job " + this.id + " from " + this.state);
        }
        this.state = JobState.RUNNING;
        if (this.runStartedAtMs <= 0L) {
            this.runStartedAtMs = System.currentTimeMillis();
        }
        this.updatedAtMs = System.currentTimeMillis();
    }

    /** {@code RUNNING→PAUSED}. */
    public synchronized void pause() {
        if (this.state != JobState.RUNNING) {
            throw new IllegalStateException("Cannot pause convert job " + this.id + " from " + this.state);
        }
        this.state = JobState.PAUSED;
        this.updatedAtMs = System.currentTimeMillis();
    }

    /** {@code PAUSED→RUNNING}. */
    public synchronized void resume() {
        if (this.state != JobState.PAUSED) {
            throw new IllegalStateException("Cannot resume convert job " + this.id + " from " + this.state);
        }
        this.state = JobState.RUNNING;
        this.updatedAtMs = System.currentTimeMillis();
    }

    /** {@code QUEUED|RUNNING|PAUSED→CANCELLED}. */
    public synchronized void cancel() {
        if (this.state != JobState.QUEUED && this.state != JobState.RUNNING && this.state != JobState.PAUSED) {
            throw new IllegalStateException("Cannot cancel convert job " + this.id + " from " + this.state);
        }
        this.state = JobState.CANCELLED;
        this.updatedAtMs = System.currentTimeMillis();
    }

    /** {@code RUNNING|PAUSED→DONE}. */
    public synchronized void complete() {
        if (this.state != JobState.RUNNING && this.state != JobState.PAUSED) {
            throw new IllegalStateException("Cannot complete convert job " + this.id + " from " + this.state);
        }
        this.state = JobState.DONE;
        this.updatedAtMs = System.currentTimeMillis();
    }

    /**
     * {@code RUNNING|PAUSED→FAILED}, appending the reason to the details.
     * A repeat call on an already {@code FAILED} job appends the reason and
     * keeps working instead of throwing.
     */
    public synchronized void fail(String reason) {
        if (this.state == JobState.FAILED) {
            addDetail("FAILED: " + reason);
            this.updatedAtMs = System.currentTimeMillis();
            return;
        }
        if (this.state != JobState.RUNNING && this.state != JobState.PAUSED) {
            throw new IllegalStateException("Cannot fail convert job " + this.id + " from " + this.state);
        }
        this.state = JobState.FAILED;
        addDetail("FAILED: " + reason);
        this.updatedAtMs = System.currentTimeMillis();
    }

    /**
     * Records one per-file outcome. Only the terminal outcomes advance the
     * counters: {@code DELETED} and {@code SKIPPED} increment done,
     * {@code FAILED} increments failed. {@code CONVERTED} and
     * {@code VALIDATED} only move the {@code currentStep} ("Copying files" /
     * "Checking files") and remember the short file name.
     *
     * <p>Only {@code RUNNING} jobs accept records; a {@code QUEUED} job
     * starts implicitly on the first record. {@code PAUSED} and terminal
     * jobs ({@code DONE}, {@code FAILED}, {@code CANCELLED}) throw
     * {@link IllegalStateException}.
     */
    public synchronized void recordFile(FileResult result) {
        if (this.state == JobState.QUEUED) {
            this.state = JobState.RUNNING;
            if (this.runStartedAtMs <= 0L) {
                this.runStartedAtMs = System.currentTimeMillis();
            }
        } else if (this.state != JobState.RUNNING) {
            throw new IllegalStateException("Cannot record file for convert job " + this.id
                + " from " + this.state);
        }
        Status status = result.status();
        String shortName = shortName(result.source());
        switch (status) {
            case CONVERTED:
                this.currentStep = "Copying files";
                this.lastFileShort = shortName;
                break;
            case VALIDATED:
                this.currentStep = "Checking files";
                this.lastFileShort = shortName;
                break;
            case DELETED:
                this.currentStep = "Tidying old files";
                this.lastFileShort = shortName;
                this.doneFiles++;
                break;
            case SKIPPED:
                this.lastFileShort = shortName;
                this.doneFiles++;
                break;
            case FAILED:
                this.lastFileShort = shortName;
                this.failedFiles++;
                break;
            default:
                this.lastFileShort = shortName;
                break;
        }
        addDetail(shortName + " " + result.status() + ": " + result.detail());
        this.updatedAtMs = System.currentTimeMillis();
    }

    /** Human-readable {@code completed/total}, with a failure suffix when any failed. */
    public synchronized String progressLine() {
        int completed = this.doneFiles + this.failedFiles;
        String line = completed + "/" + this.totalFiles + " files";
        if (this.failedFiles > 0) {
            line += " (" + this.failedFiles + " failed)";
        }
        return line;
    }

    /**
     * Linear ETA from the run start: {@code elapsed * remaining / completed}.
     * {@code no ETA} before the first file completes, {@code done} once
     * nothing remains.
     *
     * @param nowMs current wall time in millis (same clock as {@link #runStartedAtMs()})
     */
    public synchronized String etaLine(long nowMs) {
        int completed = this.doneFiles + this.failedFiles;
        if (completed == 0 || this.runStartedAtMs <= 0L) {
            return "no ETA";
        }
        int remaining = this.totalFiles - completed;
        if (remaining <= 0) {
            return "done";
        }
        long elapsed = Math.max(0L, nowMs - this.runStartedAtMs);
        long etaMs = elapsed * remaining / completed;
        long etaMicros = Math.min(etaMs, Long.MAX_VALUE / 1000L) * 1000L;
        return "ETA " + LinearStatsFormat.humanMicros(etaMicros);
    }

    private void addDetail(String line) {
        this.details.add(line);
        while (this.details.size() > MAX_DETAILS) {
            this.details.remove(0);
        }
    }

    /**
     * Short file name ({@code r.0.0} form): file name only, never a full
     * path, with a trailing {@code .mca} / {@code .linear} suffix stripped.
     */
    static String shortName(java.nio.file.Path path) {
        if (path == null) {
            return "?";
        }
        java.nio.file.Path file = path.getFileName();
        String name = file == null ? path.toString() : file.toString();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith(".mca") || name.endsWith(".linear")) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
        }
        return name.isEmpty() ? "?" : name;
    }
}
