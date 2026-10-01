package net.linear.command;

/** Lifecycle state of a {@link ConvertJob}. */
public enum JobState {
    QUEUED,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
    CANCELLED
}
