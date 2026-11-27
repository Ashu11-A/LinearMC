package net.linear.save;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side lease on {@code save-off} ({@code save-off-max-duration},
 * default 120 s, 0 disables).
 *
 * <p>A backup client that runs {@code save-off} and then dies would leave
 * the server never saving chunks again. Each leg reports every change made
 * through {@code MinecraftServer.setAutoSave} ({@code save-off},
 * {@code save-on}, the management API) with
 * {@link #onAutoSaveChanged(boolean, boolean, Runnable)}. A {@code save-off}
 * arms a timer; {@code save-on} disarms it. When the timer fires, the leg's
 * release callback runs (on the lease thread; the leg hops to its global or
 * main thread itself) and turns saving back on with a WARN and an ops
 * broadcast. The per-world Bukkit {@code World#setAutoSave} is not leased:
 * plugins use it for worlds that must never save.</p>
 */
public final class SaveOffLease {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    public static final long DEFAULT_MAX_SECONDS = 120L;

    private static volatile long maxSeconds = DEFAULT_MAX_SECONDS;

    private static ScheduledThreadPoolExecutor timer;
    private static ScheduledFuture<?> pending;
    private static long generation;
    private static long armedAtMs;

    private SaveOffLease() {
    }

    /**
     * Config resolution shared by the legs: negative values warn and use the
     * default, 0 disables, anything else is the lease in seconds.
     */
    public static long resolveMaxDurationSeconds(final long configured, final Consumer<String> warn) {
        if (configured < 0L) {
            if (warn != null) {
                warn.accept("[region-format] linear.save-off-max-duration must be >= 0 (0 disables), got "
                    + configured + ". Falling back to " + DEFAULT_MAX_SECONDS + ".");
            }
            return DEFAULT_MAX_SECONDS;
        }
        return configured;
    }

    /** Published by each leg's config load; applies to the next {@code save-off}. */
    public static void setMaxDurationSeconds(final long seconds) {
        maxSeconds = seconds < 0L ? DEFAULT_MAX_SECONDS : seconds;
    }

    public static long maxDurationSeconds() {
        return maxSeconds;
    }

    /**
     * Called after {@code setAutoSave(enabled)}; {@code changed} is its return
     * value (false when saving already was in that state).
     */
    public static synchronized void onAutoSaveChanged(final boolean enabled, final boolean changed, final Runnable release) {
        if (!changed) {
            return;
        }
        cancelLocked();
        if (enabled) {
            return;
        }
        final long seconds = maxSeconds;
        if (seconds <= 0L || release == null) {
            return;
        }
        final long armedGeneration = generation;
        armedAtMs = System.currentTimeMillis();
        pending = timer().schedule(() -> fire(armedGeneration, release), seconds, TimeUnit.SECONDS);
    }

    public static synchronized boolean armed() {
        return pending != null;
    }

    /** The WARN and ops broadcast text for an expired lease. */
    public static String releaseMessage(final long heldSeconds) {
        return "Saving was off for " + heldSeconds + " s, longer than linear.save-off-max-duration ("
            + maxSeconds + " s): saving re-enabled automatically. Run save-off again if you still need it.";
    }

    /** Seconds since the current lease was armed (0 when none is). */
    public static synchronized long heldSeconds() {
        return pending == null ? 0L : (System.currentTimeMillis() - armedAtMs) / 1000L;
    }

    private static void fire(final long armedGeneration, final Runnable release) {
        synchronized (SaveOffLease.class) {
            if (armedGeneration != generation || pending == null) {
                return; // save-on or a newer save-off came first
            }
            pending = null;
            generation++;
        }
        try {
            release.run();
        } catch (RuntimeException e) {
            LOGGER.error("save-off lease: could not re-enable saving", e);
        }
    }

    private static void cancelLocked() {
        generation++;
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private static ScheduledThreadPoolExecutor timer() {
        if (timer == null) {
            final ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(1, runnable -> {
                final Thread thread = new Thread(runnable, "LinearMC-save-off-lease");
                thread.setDaemon(true);
                return thread;
            });
            created.setRemoveOnCancelPolicy(true);
            timer = created;
        }
        return timer;
    }

    /** Test-only: drops any armed lease. */
    public static synchronized void resetForTests() {
        cancelLocked();
        maxSeconds = DEFAULT_MAX_SECONDS;
    }

    /** Test-only: arms with a lease in milliseconds. */
    public static synchronized void armForTests(final long millis, final Runnable release) {
        cancelLocked();
        final long armedGeneration = generation;
        armedAtMs = System.currentTimeMillis();
        pending = timer().schedule(() -> fire(armedGeneration, release), millis, TimeUnit.MILLISECONDS);
    }
}
