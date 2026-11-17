package net.linear.flush;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background durability heartbeat shared by every leg.
 *
 * <p>One daemon thread ({@code linearmc-flush-heartbeat}) runs an age-gated,
 * fire-and-forget drain every {@link #PERIOD_MILLIS} ms, so a dirty Linear
 * region reaches disk within {@code flush-frequency + 1s} (plus write time)
 * even when no save, unload eviction or stop happens. Without it a crash or
 * SIGKILL after chunk unload loses data that only lived in RAM.</p>
 *
 * <p>Started lazily by {@code LinearFlushCoordinator.forFolder} the first time a
 * Linear folder is tracked (explicit-ANVIL worlds never start it). Stopped
 * permanently by the stop barrier ({@code linear$beginShutdown}); a later
 * {@link #ensureStarted} is a no-op until {@link #resetForTests}. The tick
 * never joins, so it never blocks a tick or region thread; per-folder
 * locking is the coordinator's existing monitor.</p>
 */
public final class FlushHeartbeat {

    private static final Logger LOGGER = LoggerFactory.getLogger(FlushHeartbeat.class);

    public static final String THREAD_NAME = "linearmc-flush-heartbeat";
    public static final long PERIOD_MILLIS = 1000L;

    private static ScheduledExecutorService service;
    private static boolean stopped;

    private FlushHeartbeat() {
    }

    /** Idempotent: starts the single heartbeat thread running {@code tick}. */
    public static synchronized void ensureStarted(Runnable tick) {
        ensureStarted(tick, PERIOD_MILLIS);
    }

    static synchronized void ensureStarted(final Runnable tick, long periodMillis) {
        if (service != null || stopped) {
            return;
        }
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        created.scheduleWithFixedDelay(() -> {
            try {
                tick.run();
            } catch (Throwable t) {
                LOGGER.warn("LinearMC flush heartbeat tick failed", t);
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        service = created;
        LOGGER.info("LinearMC: flush heartbeat started (every {} ms, age-gated by flush-frequency)", periodMillis);
    }

    /** Stops the heartbeat for good (stop barrier). Safe to call repeatedly. */
    public static synchronized void stop() {
        stopped = true;
        if (service != null) {
            service.shutdownNow();
            service = null;
        }
    }

    public static synchronized boolean isRunning() {
        return service != null;
    }

    /** Test-only: stops and re-arms so the next {@link #ensureStarted} starts again. */
    public static synchronized void resetForTests() {
        stop();
        stopped = false;
    }
}
