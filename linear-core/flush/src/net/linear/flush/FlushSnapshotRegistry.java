package net.linear.flush;

/**
 * Pure requeue policy for flush snapshots (no coordinator state).
 *
 * <p>Mirrors the {@code flushOne} tail: a file flushed but re-dirtied
 * mid-flush rejoins the dirty set without a failure count (the flush itself
 * succeeded), and any requeue is bounded so a transient failure retries
 * instead of dropping durability. The facade adopts these helpers later;
 * behaviour is unchanged today.</p>
 */
public final class FlushSnapshotRegistry {

    private FlushSnapshotRegistry() {
    }

    /**
     * True when a file marked-to-save after its flush must rejoin the dirty
     * set: writes that landed after the snapshot are not in the persisted
     * image, so dropping them would lose data.
     */
    public static boolean shouldRequeue(boolean markedAfterFlush) {
        return markedAfterFlush;
    }

    /**
     * Bounded requeue gate: rejoins only while the dirty set stays below
     * {@code maxDirty * 2}. Overflow is lossless (the flag was never cleared,
     * so the file is still marked and retried next pass).
     */
    public static boolean boundedRequeue(int dirtySize, int maxDirty) {
        return dirtySize < maxDirty * 2;
    }
}
