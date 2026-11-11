package io.linearmc.horizon;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import net.linear.probe.LinearStorageProbe;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flush-unlock (fork {@code minecraft-0011} parity): a region evicted from the
 * host cache is closed by its thread after it leaves the storage monitor.
 * Pending closes are registered per region, and a reopen first finishes the
 * pending close ({@link #awaitPending}), so one region file never has two live
 * instances whose flushes would overwrite each other.
 */
public final class DeferredRegionCloses {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    private static final ConcurrentHashMap<String, Pending> BY_REGION = new ConcurrentHashMap<>();

    private static final ThreadLocal<ArrayList<Pending>> MINE = ThreadLocal.withInitial(ArrayList::new);

    private DeferredRegionCloses() {
    }

    /** One pending close; idempotent, and concurrent callers wait until it is done. */
    private static final class Pending {
        private final RegionFile file;
        private final String key;
        private boolean closed;

        private Pending(final RegionFile file, final String key) {
            this.file = file;
            this.key = key;
        }

        synchronized void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            try {
                this.file.close();
            } catch (final IOException | RuntimeException e) {
                LOGGER.error("LinearMC: failed to close evicted region file {}", this.key, e);
            } finally {
                // Only after the close: a reopen that finds this entry waits on the monitor.
                BY_REGION.remove(this.key, this);
            }
        }
    }

    /** Called instead of {@code evicted.close()} while the storage monitor is held. */
    public static void defer(final RegionFile evicted) {
        final Pending pending = new Pending(evicted, LinearStorageProbe.regionKey(evicted.getPath()));
        final Pending previous = BY_REGION.put(pending.key, pending);
        if (previous != null) {
            previous.close();
        }
        MINE.get().add(pending);
    }

    /** Closes every region this thread evicted; call only outside the storage monitor. */
    public static void drain() {
        final ArrayList<Pending> mine = MINE.get();
        for (final Pending pending : mine) {
            pending.close();
        }
        mine.clear();
    }

    /** Finishes a pending close of the same region (any extension) before it is reopened. */
    public static void awaitPending(final Path regionFile) {
        if (!BY_REGION.isEmpty()) {
            final Pending pending = BY_REGION.get(LinearStorageProbe.regionKey(regionFile));
            if (pending != null) {
                pending.close();
            }
        }
    }

    /** Stop barrier: close every still-pending eviction (any thread). */
    public static void closeAllPending() {
        for (final Pending pending : BY_REGION.values()) {
            pending.close();
        }
    }
}
