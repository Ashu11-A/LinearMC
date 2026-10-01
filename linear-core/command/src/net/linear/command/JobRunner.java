package net.linear.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionConverter;
import net.linear.LinearRegionTimings;

/**
 * Runs {@link ConvertJob}s against {@link LinearRegionConverter}.
 * Pure JDK plus core modules (no Bukkit): legs resolve folders, gate
 * quiesce via {@link #quiesceCheck()}, and run this off the main thread.
 */
public final class JobRunner {

    private JobRunner() {
    }

    /**
     * Returns the folder keys with unflushed writes ({@code dirtyDepth > 0}).
     * Empty means quiesced (clean); non-empty names the folders blocking
     * conversion.
     */
    public static List<String> quiesceCheck() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps =
            LinearFlushCoordinator.snapshots();
        Map<String, LinearRegionTimings.LinearFolderSnapshot> sorted = new TreeMap<>(snaps);
        List<String> dirty = new ArrayList<>();
        for (Map.Entry<String, LinearRegionTimings.LinearFolderSnapshot> e : sorted.entrySet()) {
            if (e.getValue().dirtyDepth() > 0) {
                dirty.add(e.getKey());
            }
        }
        return dirty;
    }

    /**
     * Converts every folder in order on the calling thread, recording each
     * per-file outcome on {@code job} and completing (or failing) it at the
     * end. Direction comes from the job: {@code MCA_TO_LINEAR} converts
     * forward, {@code LINEAR_TO_MCA} converts back (both validate before
     * deleting sources). {@code threads} is the recorded request; pool width
     * stays converter-owned.
     */
    public static void run(ConvertJob job, List<Path> folders, int level, int threads) {
        if (job == null) {
            throw new IllegalArgumentException("job must not be null");
        }
        if (folders == null) {
            throw new IllegalArgumentException("folders must not be null");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1, got " + threads);
        }
        try {
            job.start();
        } catch (IllegalStateException cancelledBeforeStart) {
            return;
        }
        final LinearRegionConverter.Listener listener = new LinearRegionConverter.Listener() {
            @Override
            public void onFile(LinearRegionConverter.FileResult r) {
                // Pause takes effect between files: sleep-poll (bounded, no
                // hot spin) so no progress is recorded while paused.
                while (job.state() == JobState.PAUSED) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted while paused", interrupted);
                    }
                }
                if (job.state() == JobState.CANCELLED) {
                    throw new RuntimeException("job " + job.id() + " cancelled");
                }
                job.recordFile(r);
            }

            @Override
            public void onRetry(Path source, int attempt, String reason) {
                job.recordRetry(attempt);
            }
        };
        for (Path folder : folders) {
            if (job.state() == JobState.CANCELLED) {
                return;
            }
            try {
                if (job.direction() == ConvertDirection.LINEAR_TO_MCA) {
                    LinearRegionConverter.convertRegionFolderReverse(folder, level, listener, false);
                } else {
                    LinearRegionConverter.convertRegionFolder(folder, level, listener, false);
                }
            } catch (LinearRegionConverter.ConversionProtectionException failed) {
                if (job.state() == JobState.CANCELLED) {
                    return;
                }
                try {
                    job.fail(failed.getMessage());
                } catch (IllegalStateException alreadyTerminal) {
                    // Lost a cancel race; the CANCELLED state already tells the story.
                }
                return;
            } catch (RuntimeException failed) {
                if (job.state() == JobState.CANCELLED) {
                    return;
                }
                try {
                    job.fail(String.valueOf(failed.getMessage()));
                } catch (IllegalStateException alreadyTerminal) {
                    // Lost a cancel race; keep the CANCELLED state.
                }
                return;
            }
            if (job.state() == JobState.CANCELLED) {
                return;
            }
        }
        if (job.state() == JobState.RUNNING || job.state() == JobState.PAUSED) {
            // PAUSED at the very end still completed: every file settled.
            if (job.state() == JobState.PAUSED) {
                job.resume();
            }
            job.complete();
        } else if (job.state() == JobState.QUEUED) {
            // Empty folder set: no file record ever started the job.
            job.start();
            job.complete();
        }
        // CANCELLED stays CANCELLED (complete() would lie about it).
    }
}
