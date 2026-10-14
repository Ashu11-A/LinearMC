package io.papermc.paper.event.linear;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Linear linearstats: flush-completed event, Paper-native package (ASYNC).
 *
 * <p>ASYNC contract: extends {@code Event} with {@code super(true)} and is
 * fired OFF IO threads via the async scheduler (Folia-safe: never on the
 * Moonrise IO thread). Listeners run async and MUST NOT touch world state
 * directly; schedule back via the region scheduler if world access is needed.
 * Fired once per coordinator {@code flushDirty()} that attempted {@code >=1}
 * file (clean-no-op flushes fire nothing). Carries only JDK primitives +
 * Strings (no NMS types; no NMS-typed totals accessor): per-flush
 * identity plus primitive count/avg totals.</p>
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
    private final long readCount;
    private final long readMicrosAvg;
    private final long writeCount;
    private final long writeMicrosAvg;
    private final long flushCount;
    private final long flushMicrosAvg;

    public LinearRegionFlushCompletedEvent(
        @NotNull String worldName,
        @NotNull String folderType,
        long filesFlushed,
        long failures,
        long elapsedMicros,
        long readCount,
        long readMicrosAvg,
        long writeCount,
        long writeMicrosAvg,
        long flushCount,
        long flushMicrosAvg
    ) {
        super(true); // Linear: ASYNC event (off-IO via async scheduler; see javadoc contract).
        this.worldName = worldName;
        this.folderType = folderType;
        this.filesFlushed = filesFlushed;
        this.failures = failures;
        this.elapsedMicros = elapsedMicros;
        this.readCount = readCount;
        this.readMicrosAvg = readMicrosAvg;
        this.writeCount = writeCount;
        this.writeMicrosAvg = writeMicrosAvg;
        this.flushCount = flushCount;
        this.flushMicrosAvg = flushMicrosAvg;
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

    public long getReadCount() {
        return this.readCount;
    }

    public long getReadMicrosAvg() {
        return this.readMicrosAvg;
    }

    public long getWriteCount() {
        return this.writeCount;
    }

    public long getWriteMicrosAvg() {
        return this.writeMicrosAvg;
    }

    public long getFlushCount() {
        return this.flushCount;
    }

    public long getFlushMicrosAvg() {
        return this.flushMicrosAvg;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
