package net.linear.command;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory convert-job registry. The queue is never persisted: jobs vanish on
 * restart (report via {@link #droppedByRestartLine} so operators know to re-issue).
 */
public final class JobQueue {

    /** Immutable submit spec: everything {@link ConvertJob} needs besides runtime progress. */
    public record Spec(ConvertDirection direction, String world, int level,
        int threads, boolean dryRun, int totalFiles) {
        public Spec {
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
        }
    }

    private final ConcurrentHashMap<UUID, ConvertJob> jobs = new ConcurrentHashMap<>();

    /**
     * Minimum id-prefix length accepted by {@link #findByPrefix}. Shorter
     * prefixes (1-3 chars) collide too easily across random UUIDs to ever
     * identify a job, so they are rejected (fail-closed {@code null}; the
     * caller reports 'ambiguous, use full id').
     */
    public static final int MIN_PREFIX_LENGTH = 4;

    /** Enqueues a {@code QUEUED} job from the spec and returns it. */
    public ConvertJob submit(Spec spec) {
        ConvertJob job = new ConvertJob(
            spec.direction(), spec.world(), spec.level(), spec.threads(), spec.dryRun(), spec.totalFiles());
        this.jobs.put(job.id(), job);
        return job;
    }

    /** Returns the job, or {@code null} when the id is unknown. */
    public ConvertJob get(UUID id) {
        return this.jobs.get(id);
    }

    /**
     * Returns the job whose id starts with {@code prefix}, or {@code null}
     * when nothing matches, more than one job matches (ambiguous), or the
     * prefix is shorter than {@link #MIN_PREFIX_LENGTH}.
     *
     * <p>Matching is case-insensitive (UUID hex prints lowercase but
     * operators paste mixed case), after trimming surrounding whitespace, so
     * an 8-char short id works verbatim. A {@code null} return never guesses:
     * the caller distinguishes 'no job' from 'ambiguous, use full id' via
     * {@link #countByPrefix}.</p>
     */
    public ConvertJob findByPrefix(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return null;
        }
        String want = prefix.trim().toLowerCase(java.util.Locale.ROOT);
        if (want.length() < MIN_PREFIX_LENGTH) {
            return null;
        }
        ConvertJob match = null;
        for (ConvertJob job : this.jobs.values()) {
            if (job == null || job.id() == null) {
                continue;
            }
            if (!job.id().toString().toLowerCase(java.util.Locale.ROOT).startsWith(want)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = job;
        }
        return match;
    }

    /**
     * Counts jobs whose id starts with {@code prefix} (case-insensitive).
     * Used by the status caller to distinguish 'no job' from
     * 'ambiguous, use full id'.
     */
    public int countByPrefix(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return 0;
        }
        String want = prefix.trim().toLowerCase(java.util.Locale.ROOT);
        int matches = 0;
        for (ConvertJob job : this.jobs.values()) {
            if (job == null || job.id() == null) {
                continue;
            }
            if (job.id().toString().toLowerCase(java.util.Locale.ROOT).startsWith(want)) {
                matches++;
            }
        }
        return matches;
    }

    /** Snapshot copy of the jobs, oldest first (by start time). */
    public List<ConvertJob> list() {
        List<ConvertJob> out = new ArrayList<>(this.jobs.values());
        out.sort((a, b) -> {
            int byTime = Long.compare(a.startedAtMs(), b.startedAtMs());
            if (byTime != 0) {
                return byTime;
            }
            return a.id().compareTo(b.id());
        });
        return out;
    }

    public void pause(UUID id) {
        require(id).pause();
    }

    public void resume(UUID id) {
        require(id).resume();
    }

    public void cancel(UUID id) {
        require(id).cancel();
    }

    /**
     * Removes terminal jobs ({@code DONE}, {@code FAILED}, {@code CANCELLED})
     * only; queued/running/paused jobs are kept.
     *
     * @return number of removed jobs
     */
    public int clearTerminal() {
        int removed = 0;
        for (UUID id : this.jobs.keySet()) {
            ConvertJob job = this.jobs.get(id);
            if (job == null) {
                continue;
            }
            JobState state = job.state();
            if (state == JobState.DONE || state == JobState.FAILED || state == JobState.CANCELLED) {
                if (this.jobs.remove(id, job)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Restart notice: the queue is not kept, so start the convert again.
     * Two short lines (each at most 55 chars), plain words only.
     */
    public static String droppedByRestartLine(int n) {
        return "Jobs were lost on restart. Start them again (" + n + ").\n"
            + "Your old files are still there. Nothing changed.";
    }

    private ConvertJob require(UUID id) {
        ConvertJob job = this.jobs.get(id);
        if (job == null) {
            throw new NoSuchElementException("No convert job: " + id);
        }
        return job;
    }
}
