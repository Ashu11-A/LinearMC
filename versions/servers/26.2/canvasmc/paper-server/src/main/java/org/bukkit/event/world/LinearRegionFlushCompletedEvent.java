package org.bukkit.event.world;

import net.linear.LinearRegionTimings;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Linear linearstats: flush-granularity completion event (ASYNC).
 *
 * <p>ASYNC contract (decided vs region-scheduler sync delivery): this event
 * extends {@code Event} with {@code super(true)} and is fired OFF IO threads
 * via the async scheduler (Folia-safe: never on the Moonrise IO thread).
 * Listeners run async and MUST NOT touch world state directly; schedule back
 * via the region scheduler if world access is needed. Fired once per
 * coordinator {@code flushDirty()} that attempted {@code >=1} file
 * (clean-no-op flushes fire nothing). Carries the world/folder identity
 * ({@code folderType} pinned to {@code region|poi|entities} via
 * {@code LinearFolderNames}, default {@code region}) plus the
 * post-flush totals snapshot. The bridge ({@code LinearFlushBridge}) fires
 * this; wire the {@code notifyFlush} call site into the save path after the
 * coordinator flush (no call site in this patch; the sync overload covers
 * the server-internal path).</p>
 *
 * <p>Listener sample (5 lines, async-safe: log only, no world touch):</p>
 * <pre>
 * &#64;EventHandler
 * public void onLinearFlush(LinearRegionFlushCompletedEvent e) {
 *     // Async: do not touch world state here; schedule back if needed.
 *     getLogger().info(e.getWorldName() + "[" + e.getFolderType() + "] flushed=" + e.getFilesFlushed());
 * }
 * </pre>
 */
public class LinearRegionFlushCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String worldName;
    private final String folderType;
    private final long filesFlushed;
    private final long failures;
    private final long elapsedMicros;
    private final LinearRegionTimings.LinearFolderSnapshot totals;

    public LinearRegionFlushCompletedEvent(
        @NotNull String worldName,
        @NotNull String folderType,
        long filesFlushed,
        long failures,
        long elapsedMicros,
        @NotNull LinearRegionTimings.LinearFolderSnapshot totals
    ) {
        super(true); // Linear: ASYNC event (off-IO via async scheduler; see javadoc contract).
        this.worldName = worldName;
        this.folderType = folderType;
        this.filesFlushed = filesFlushed;
        this.failures = failures;
        this.elapsedMicros = elapsedMicros;
        this.totals = totals;
    }

    public @NotNull String getWorldName() {
        return this.worldName;
    }

    public @NotNull String getFolderType() {
        return this.folderType;
    }

    public long getFilesFlushed() {
        return this.filesFlushed;
    }

    public long getFailures() {
        return this.failures;
    }

    public long getElapsedMicros() {
        return this.elapsedMicros;
    }

    public @NotNull LinearRegionTimings.LinearFolderSnapshot getTotals() {
        return this.totals;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
