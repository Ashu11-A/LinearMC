package net.linear;

import java.nio.file.Path;

/**
 * Quiet per-folder reporting bridges for storage legs.
 *
 * <p>Mirrors the leg boilerplate: null-safe folder, {@code RuntimeException}
 * swallowed (tracking is best-effort; bytes are already staged). Keeps the
 * try/catch noise in one NMS-free home so legs stay one-liners.
 */
public final class FlushReports {

    private FlushReports() {
    }

    /** Reports {@code file} dirty for {@code folder}; no-op on nulls, never throws. */
    public static void markDirtyForFile(Path folder, AbstractRegionFile file) {
        if (folder == null || file == null) {
            return;
        }
        try {
            LinearFlushCoordinator.forFolder(folder).markDirty(file);
        } catch (RuntimeException ignored) {
            // Coordinator tracking is best-effort; the bytes are already staged.
        }
    }

    /** Quiet cache-hit report; no-op on null folder, never throws. */
    public static void reportCacheHitQuietly(Path folder) {
        if (folder == null) {
            return;
        }
        try {
            LinearFlushCoordinator.forFolder(folder).reportCacheHit();
        } catch (RuntimeException ignored) {
        }
    }

    /** Quiet cache-miss report; no-op on null folder, never throws. */
    public static void reportCacheMissQuietly(Path folder) {
        if (folder == null) {
            return;
        }
        try {
            LinearFlushCoordinator.forFolder(folder).reportCacheMiss();
        } catch (RuntimeException ignored) {
        }
    }

    /** Quiet oversize-reject report; no-op on null folder, never throws. */
    public static void reportOversizeQuietly(Path folder) {
        if (folder == null) {
            return;
        }
        try {
            LinearFlushCoordinator.forFolder(folder).reportOversizeReject();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Server-lifecycle drains. Thin delegates over the coordinator's static
     * methods so legs never touch pool/force semantics directly.
     */
    public static final class FolderDrain {

        private FolderDrain() {
        }

        /** Stop barrier: force-drain every folder, then shut the flush pool down. */
        public static void onServerStop() {
            LinearFlushCoordinator.evictAll();
            LinearFlushCoordinator.shutdownFlushPool();
        }

        /**
         * Save drain: explicit saves ({@code flushStorage=true}) force every
         * dirty file to disk; periodic/autosave drains submit async and return
         * without joining so the tick never blocks.
         */
        public static void onSave(boolean flushStorage) {
            if (flushStorage) {
                LinearFlushCoordinator.flushAllDirty(true);
            } else {
                LinearFlushCoordinator.flushDirtyAsync(false);
            }
        }
    }
}
