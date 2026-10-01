package net.linear;

/**
 * JDK-only flush-completion DTO for legs to translate into Bukkit events.
 *
 * @param folderKey      absolute normalized storage-folder key
 * @param filesAttempted files in the drain batch
 * @param filesFlushed   files flushed successfully
 * @param failures       files that failed and were re-queued
 * @param elapsedMicros  batch wall time in microseconds
 */
public record FlushEvent(
    String folderKey,
    int filesAttempted,
    int filesFlushed,
    int failures,
    long elapsedMicros) {
}
