package io.linearmc.horizon;

import java.util.concurrent.atomic.AtomicBoolean;
import net.linear.LinearFlushCoordinator;
import net.linear.flush.FlushReports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stop drains. {@link #pluginDisableDrain()} runs from {@code onDisable},
 * before the host's final world save, so it keeps the flush pool alive;
 * {@link #finalDrain(String)} is the real barrier, run by
 * {@code MinecraftServerMixin} after that save. Idempotent, never throw.
 */
public final class LinearShutdownBarrier {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    private static final AtomicBoolean FINAL_DRAIN_DONE = new AtomicBoolean();

    private LinearShutdownBarrier() {
    }

    /** Pre-save drain from {@code onDisable}: force current dirty files, keep the pool. */
    public static void pluginDisableDrain() {
        try {
            LinearFlushCoordinator.flushAllDirty(true);
        } catch (RuntimeException e) {
            LOGGER.warn("LinearMC: plugin-disable drain failed: {}", String.valueOf(e.getMessage()));
        }
    }

    /** Post-save stop barrier (exactly once per process). */
    public static void finalDrain(final String site) {
        if (!FINAL_DRAIN_DONE.compareAndSet(false, true)) {
            return;
        }
        try {
            // Evictions still waiting for their closing thread (flush-unlock)
            // close first, so every Linear file is persisted by its own instance.
            DeferredRegionCloses.closeAllPending();
            FlushReports.FolderDrain.onServerStop();
            LOGGER.info("LinearMC: final stop drain complete ({})", site);
        } catch (RuntimeException e) {
            LOGGER.error("LinearMC: final stop drain failed ({})", site, e);
        }
    }
}
