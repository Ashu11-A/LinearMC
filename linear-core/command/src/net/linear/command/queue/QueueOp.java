package net.linear.command.queue;

/** Queue sub-operation for {@code /linear queue <op> [jobId]}. */
public enum QueueOp {
    LIST,
    STATUS,
    PAUSE,
    RESUME,
    CANCEL,
    CLEAR
}
