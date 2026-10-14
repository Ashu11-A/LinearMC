package net.linear.command.queue;
import net.linear.command.ConvertJob;

/** Lifecycle state of a {@link ConvertJob}. */
public enum JobState {
    QUEUED,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
    CANCELLED
}
