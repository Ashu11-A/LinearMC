package io.linearmc.horizon;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.linear.LinearFlushCoordinator;
import net.linear.save.SaveAllBarrier;
import net.linear.save.SaveOffLease;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Save barrier on Horizon (fork parity: Folia {@code minecraft-0019}, Canvas
 * {@code SaveAllCommand}/{@code MinecraftServer} overlays). The core owns the
 * ticket, the forced barrier, the marker and the lease; this class adapts the
 * host calls the mixins intercept.
 */
public final class HorizonSaveBarrier {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    /** Runs a task on the host's global (Folia/Canvas) or main (Paper) thread; set by the plugin. */
    private static volatile Consumer<Runnable> globalExecutor;

    private HorizonSaveBarrier() {
    }

    public static void setGlobalExecutor(final Consumer<Runnable> executor) {
        globalExecutor = executor;
    }

    /**
     * Wait budget of a flush save-all here: Paper waits on its main thread
     * (the tick is frozen meanwhile), so it stays well below the default
     * watchdog timeout (60 s). The forks wait off-thread with
     * {@link SaveAllBarrier#DEFAULT_WAIT_MILLIS}.
     */
    static final long MAIN_THREAD_WAIT_MILLIS = 20_000L;

    private static SaveAllBarrier.Ticket begin(final MinecraftServer server) {
        return SaveAllBarrier.begin(server.getWorldPath(LevelResource.ROOT), true, HorizonCoupledSave.fences(server),
            MAIN_THREAD_WAIT_MILLIS);
    }

    /**
     * Paper-shape {@code save-all} ({@code SaveAllCommand.saveAll} calls
     * {@code saveEverything} on the main thread). A plain save is untouched.
     * A flush save runs it, forces the coordinator barrier, saves the players
     * again (Paper saves them before the chunks; after this they follow
     * their now-durable chunks, the fork order), then seals the core ticket,
     * which runs the barrier once more and writes the marker. Blocking here
     * is the vanilla contract of {@code save-all flush} on Paper. Returns
     * false (the command reports failure) on any failed region or flush or a
     * marker that could not be written.
     */
    public static boolean saveEverything(final MinecraftServer server, final boolean silent, final boolean flush,
                                         final boolean force) {
        if (net.linear.save.CoupledSave.active()) {
            return saveEverythingCoupled(server, silent, flush, force);
        }
        if (!flush) {
            return server.saveEverything(silent, false, force);
        }
        final SaveAllBarrier.Ticket ticket = begin(server);
        ticket.taskQueued();
        boolean saved = false;
        RuntimeException thrown = null;
        try {
            ticket.claimRegion(null);
            saved = server.saveEverything(silent, true, force);
            LinearFlushCoordinator.flushAllDirty(true);
            server.getPlayerList().saveAll();
        } catch (final RuntimeException e) {
            thrown = e;
        } finally {
            if (!saved) {
                ticket.regionFailed();
            }
            ticket.taskDone();
        }
        final SaveAllBarrier.Result r = awaitSeal(ticket);
        if (thrown != null) {
            throw thrown;
        }
        return saved && r != null && r.failed() == 0 && r.markerWritten();
    }

    /**
     * Coupled save on ({@code coupled-save.enabled}): Paper's
     * {@code saveEverything} saves the players before the chunks, so it runs
     * inside a coupled capture. The player files are staged, the chunks
     * saved, and the staged files are written only after the chunk I/O and
     * the forced storage flush; a flush save-all waits for that (the core
     * ticket's barrier) before the marker, like the forks.
     */
    private static boolean saveEverythingCoupled(final MinecraftServer server, final boolean silent,
                                                 final boolean flush, final boolean force) {
        final boolean[] saved = new boolean[1];
        if (!flush) {
            HorizonCoupledSave.capture(server, () -> saved[0] = server.saveEverything(silent, false, force), false);
            return saved[0];
        }
        final SaveAllBarrier.Ticket ticket = begin(server);
        ticket.taskQueued();
        RuntimeException thrown = null;
        try {
            ticket.claimRegion(null);
            HorizonCoupledSave.capture(server, () -> saved[0] = server.saveEverything(silent, true, force), false);
        } catch (final RuntimeException e) {
            thrown = e;
        } finally {
            if (!saved[0]) {
                ticket.regionFailed();
            }
            ticket.taskDone();
        }
        final SaveAllBarrier.Result r = awaitSeal(ticket);
        if (thrown != null) {
            throw thrown;
        }
        return saved[0] && r != null && r.failed() == 0 && r.markerWritten();
    }

    private static SaveAllBarrier.Result awaitSeal(final SaveAllBarrier.Ticket ticket) {
        final AtomicReference<SaveAllBarrier.Result> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        ticket.seal(r -> {
            result.set(r);
            done.countDown();
        });
        boolean interrupted = false;
        while (true) {
            try {
                done.await();
                break;
            } catch (final InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    /** {@code setAutoSave} hook: arms or disarms the save-off lease. */
    public static void onAutoSaveChanged(final boolean enable, final boolean changed) {
        SaveOffLease.onAutoSaveChanged(enable, changed, HorizonSaveBarrier::releaseLease);
    }

    private static void releaseLease() {
        final Consumer<Runnable> executor = globalExecutor;
        if (executor == null) {
            LOGGER.warn("save-off lease expired but the LinearMC plugin is not enabled; run save-on");
            return;
        }
        executor.accept(() -> {
            final MinecraftServer server = MinecraftServer.getServer();
            if (server != null && server.setAutoSave(true)) {
                final String message = SaveOffLease.releaseMessage(SaveOffLease.maxDurationSeconds());
                LOGGER.warn(message);
                org.bukkit.Bukkit.broadcast(
                    net.kyori.adventure.text.Component.text(message, net.kyori.adventure.text.format.NamedTextColor.RED),
                    org.bukkit.Server.BROADCAST_CHANNEL_ADMINISTRATIVE);
            }
        });
    }

    /**
     * Periodic player autosave gate: skipped while save-off is in effect on
     * any level (the coupled-save rule; the forks check the ticking region's
     * own level, which is the same thing for the server-wide
     * {@code save-off} and differs only when a plugin turns autosave off for
     * a single world, see docs/limitations.md).
     */
    public static void periodicPlayerSave(final MinecraftServer server, final PlayerList players, final int interval) {
        if (HorizonCoupledSave.saveOff(server)) {
            return;
        }
        if (HorizonCoupledSave.periodic(server)) {
            return; // coupled save: region captures replace the periodic player save
        }
        players.saveAll(interval);
    }
}
