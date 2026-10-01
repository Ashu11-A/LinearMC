package net.linear.command;

/** Queue sub-operation for {@code /linear queue <op> [jobId]}. */
public enum QueueOp {
    LIST,
    STATUS,
    PAUSE,
    RESUME,
    CANCEL,
    CLEAR
}
