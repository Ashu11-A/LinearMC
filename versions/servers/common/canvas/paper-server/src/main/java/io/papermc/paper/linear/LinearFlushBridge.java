package io.papermc.paper.linear;

import net.linear.FlushEvent;
import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionTimings;
import org.bukkit.Bukkit;
import org.bukkit.event.world.LinearRegionFlushCompletedEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Linear linearstats: Paper-side flush-completion bridge.
 *
 * <p>Fires {@link LinearRegionFlushCompletedEvent} (ASYNC {@code super(true)},
 * see event javadoc) at flush granularity, OFF IO threads when a Plugin is
 * available, only when {@code >=1} file was attempted. The payload is the
 * core {@link FlushEvent} DTO (folder key, attempted/flushed/failure counts,
 * batch micros); only the Bukkit {@code callEvent} hop stays leg-side. Two
 * entry points:</p>
 * <ul>
 *   <li>{@link #notifyFlush(Plugin,String,long,long)}: async scheduler
 *       (Folia-safe, never on the Moonrise IO thread). The caller passes
 *       the owning {@code Plugin} explicitly (the old
 *       {@code getProvidingPlugin(Bridge.class)} misuse is fixed via param; do
 *       NOT use it — the bridge class lives in server code, not a plugin
 *       jar). Drops clean-no-ops, falls back to sync fire when the scheduler
 *       is absent.</li>
 *   <li>{@link #notifyFlushSync(String,long,long)}: sync fire with NO Plugin
 *       required, for the server-internal call site right after
 *       {@code LinearFlushCoordinator.flushDirty()} (NMS, no Plugin on that
 *       path; autosave + manual saves all funnel through the coordinator, so
 *       this is the one place that catches every flush). The event itself is
 *       async-contract ({@code super(true)}), so firing it sync from the save
 *       thread still delivers async semantics to listeners (they must not touch
 *       world state; schedule back via region scheduler if needed). This is the
 *       reserved server-internal call site: until wired, the command and
 *       delegate worked (pull) but the event stayed silent.</li>
 * </ul>
 */
public final class LinearFlushBridge {

    private LinearFlushBridge() {
    }

    /**
     * Core payload build: single snapshot read shaped into the
     * {@link FlushEvent} DTO. Counts narrow from coordinator longs to the
     * DTO ints with saturation (no new throw: lifetime counters could pass
     * {@code Integer.MAX_VALUE} on very long-lived busy servers; the Bukkit
     * event widens them back losslessly).
     */
    static @NotNull FlushEvent payload(
        final String folderKey,
        final LinearRegionTimings.LinearFolderSnapshot totals,
        final long filesAttempted,
        final long elapsedMicros) {
        return new FlushEvent(
            folderKey,
            saturateToInt(filesAttempted),
            saturateToInt(totals.filesFlushed()),
            saturateToInt(totals.failures()),
            elapsedMicros);
    }

    private static int saturateToInt(final long v) {
        if (v > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (v < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) v;
    }

    /**
     * Paper-side entry point: schedules the flush-completed event off-IO.
     *
     * @param owningPlugin owning plugin for the async scheduler (explicit;
     *     fixes the old {@code getProvidingPlugin} misuse — the bridge is
     *     server code, not a plugin; the caller passes the Paper plugin
     *     instance at the wired Paper-side save-path call site)
     * @param folderKey absolute folder-path key (as in {@code snapshots()})
     * @param filesAttempted number of files the flush attempted ({@code >=1} to fire)
     * @param elapsedMicros wall time for the flush batch
     */
    public static void notifyFlush(final @NotNull Plugin owningPlugin, final String folderKey, final long filesAttempted, final long elapsedMicros) {
        if (filesAttempted < 1) {
            return; // flush-granularity + clean-no-op suppression
        }
        final LinearRegionTimings.LinearFolderSnapshot totals;
        try {
            totals = LinearFlushCoordinator.snapshots().getOrDefault(folderKey, LinearRegionTimings.LinearFolderSnapshot.EMPTY);
        } catch (RuntimeException ignored) {
            return;
        }
        final FlushEvent event = payload(folderKey, totals, filesAttempted, elapsedMicros);
        final String folderType = net.linear.LinearFolderNames.inferFolderType(folderKey);
        final String worldName = net.linear.LinearFolderNames.inferWorld(folderKey);
        // OFF IO threads: async scheduler (falls back to sync fire when absent).
        try {
            Bukkit.getAsyncScheduler().runNow(
                owningPlugin,
                task -> Bukkit.getPluginManager().callEvent(new LinearRegionFlushCompletedEvent(
                    worldName, folderType, event.filesFlushed(), event.failures(), event.elapsedMicros(), totals)));
        } catch (RuntimeException fallback) {
            // Fallback (documented): if the async scheduler is absent, fire
            // synchronously so no completion is silently lost (still at flush
            // granularity, still only when filesAttempted >= 1).
            Bukkit.getPluginManager().callEvent(new LinearRegionFlushCompletedEvent(
                worldName, folderType, event.filesFlushed(), event.failures(), event.elapsedMicros(), totals));
        }
    }

    /**
     * Server-internal sync entry point (NO Plugin required).
     * Called after the coordinator drains a folder. Fires
     * synchronously on the save thread; the event remains async-contract, so
     * listeners still observe async semantics (must not touch world state).
     * No Plugin handle is needed on this path.
     *
     * @param folderKey absolute folder-path key (as in {@code snapshots()})
     * @param filesAttempted number of files the flush attempted ({@code >=1} to fire)
     * @param elapsedMicros wall time for the flush batch
     */
    public static void notifyFlushSync(final String folderKey, final long filesAttempted, final long elapsedMicros) {
        if (filesAttempted < 1) {
            return;
        }
        if (folderKey == null) {
            return;
        }
        final LinearRegionTimings.LinearFolderSnapshot totals;
        try {
            totals = LinearFlushCoordinator.snapshots().getOrDefault(folderKey, LinearRegionTimings.LinearFolderSnapshot.EMPTY);
        } catch (RuntimeException ignored) {
            return;
        }
        final FlushEvent event = payload(folderKey, totals, filesAttempted, elapsedMicros);
        final String folderType;
        final String worldName;
        try {
            folderType = net.linear.LinearFolderNames.inferFolderType(folderKey);
            worldName = net.linear.LinearFolderNames.inferWorld(folderKey);
        } catch (RuntimeException ignored) {
            return;
        }
        try {
            Bukkit.getPluginManager().callEvent(new LinearRegionFlushCompletedEvent(
                worldName, folderType, event.filesFlushed(), event.failures(), event.elapsedMicros(), totals));
        } catch (RuntimeException ignored) {
        }
    }
}
