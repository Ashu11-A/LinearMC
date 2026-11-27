package io.papermc.paper.linear;

import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.linear.save.CoupledSave;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Coupled save order on the region-threaded forks ({@code coupled-save.enabled},
 * default off). The ordering logic lives in {@link CoupledSave}; this adapter
 * supplies the region's chunk saves, the chunk I/O fences and the forced
 * storage flush. Same file on Folia ({@code paper-0013}) and Canvas (leg
 * plain file); Horizon has the same shape in {@code HorizonCoupledSave}.
 *
 * <p>A capture runs on the region's own thread: every chunk holder that
 * region owns is saved (no per-tick limit; the writes go to the chunk I/O
 * thread as usual), then the region's players are saved inside the staging
 * capture, so their {@code .dat}, stats and advancements are serialized in
 * memory instead of written. The coupled-save thread writes them only after
 * every chunk I/O task in flight at capture time has landed and the forced
 * storage flush has returned. Nothing here blocks the region thread on I/O.
 * The guarantee is per region: the player file is never newer than its
 * region's chunks; it is not an atomic cross-region cut (see
 * {@link CoupledSave}).</p>
 *
 * <p>A player who leaves a region (cross-region teleport, portal, respawn)
 * triggers a <em>departure</em> capture on the source region thread first
 * ({@link #departure}), while that thread still owns both the player and
 * the chunks the player changed: the next capture in the destination region
 * saves only the chunks it owns, and the source region, now without
 * players, skips its periodic capture.</p>
 */
public final class LinearCoupledSave implements CoupledSave.Host {

    /** Levels at capture time: the coupled-save thread never iterates the live level map. */
    private final List<ServerLevel> levels;

    private LinearCoupledSave(final List<ServerLevel> levels) {
        this.levels = levels;
    }

    private static LinearCoupledSave forAllLevels(final MinecraftServer server) {
        final List<ServerLevel> levels = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            levels.add(level);
        }
        return new LinearCoupledSave(levels);
    }

    /**
     * Chunk I/O fences and the storage flush over every level, for a
     * {@code save-all flush} ticket ({@code SaveAllBarrier.begin}): the
     * barrier thread waits for every chunk write still queued when the last
     * region task finished before it runs the forced flush.
     */
    public static CoupledSave.Host fences(final MinecraftServer server) {
        return forAllLevels(server);
    }

    /**
     * Periodic player autosave site (once per region tick, after the save-off
     * check). Returns {@code true} when coupled save owns player saving, so
     * the caller skips its periodic player save; a capture runs when this
     * region is due. The global tick ({@code null} region) owns no chunks
     * and no players.
     */
    public static boolean periodic(final MinecraftServer server) {
        if (!CoupledSave.enabled()) {
            return false;
        }
        final Object region = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion();
        if (region != null && CoupledSave.due(region) && ownsAPlayer(server)) {
            capture(server, () -> server.getPlayerList().saveAll());
        }
        return true;
    }

    /**
     * Logout and plugin {@code saveData}: a capture of the player's region
     * when coupled save is active on the thread that owns the player; the
     * direct save otherwise (stop, off-thread callers, save-off, where
     * vanilla writes the player file at once).
     */
    public static void save(final MinecraftServer server, final Entity player, final Runnable save) {
        if (shouldCapture(server, player)) {
            capture(server, save);
        } else {
            save.run();
        }
    }

    /**
     * The player is about to leave this region (cross-region teleport or
     * portal: {@code ServerPlayer.preChangeDimension}; respawn: before the
     * removal from the old level). Saves this region's chunks and stages its
     * players while this thread still owns both, so the player file never
     * lands before the chunks the player changed here. A later capture in
     * the destination region supersedes the staged save; its fences and
     * flush are global, so the order still holds.
     */
    public static void departure(final ServerPlayer player) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server != null && shouldCapture(server, player)) {
            capture(server, () -> server.getPlayerList().saveAll());
        }
    }

    private static boolean shouldCapture(final MinecraftServer server, final Entity player) {
        return CoupledSave.active() && server.isRunning() && !saveOff(server) && TickThread.isTickThreadFor(player);
    }

    /**
     * Saves every chunk holder this region owns, then runs
     * {@code stagePlayers} inside a staging capture and submits it.
     */
    public static void capture(final MinecraftServer server, final Runnable stagePlayers) {
        final LinearCoupledSave host = forAllLevels(server);
        try (CoupledSave.Capture capture = CoupledSave.begin(host)) {
            saveOwnedChunks();
            stagePlayers.run();
        }
    }

    /** A region without players has no player file to order: its capture is skipped. */
    private static boolean ownsAPlayer(final MinecraftServer server) {
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (TickThread.isTickThreadFor(player)) {
                return true;
            }
        }
        return false;
    }

    private static boolean saveOff(final MinecraftServer server) {
        for (final ServerLevel level : server.getAllLevels()) {
            if (level.noSave) {
                return true;
            }
        }
        return false;
    }

    /**
     * Folia's own region save: walks only the sections this region owns
     * (not every holder of the level), saves each holder that is not saved
     * yet and logs one that fails. No flush: the writes go to the chunk I/O
     * thread and the capture fences them.
     */
    private static void saveOwnedChunks() {
        final io.papermc.paper.threadedregions.ThreadedRegionizer.ThreadedRegion<io.papermc.paper.threadedregions.TickRegions.TickRegionData, io.papermc.paper.threadedregions.TickRegions.TickRegionSectionData> region =
            io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            return;
        }
        ((ChunkSystemServerLevel)region.getData().world).moonrise$getChunkTaskScheduler().chunkHolderManager.saveAllChunksRegionised(
            false, false, false, false, false, false, false
        );
    }

    @Override
    public void snapshotInFlight(final List<Object> out) {
        for (final ServerLevel level : this.levels) {
            MoonriseRegionFileIO.linear$snapshotInFlight(level, out);
        }
    }

    @Override
    public int fenceState(final Object task) {
        return MoonriseRegionFileIO.linear$fenceState(task);
    }

    @Override
    public void flushStorages() throws IOException {
        for (final ServerLevel level : this.levels) {
            MoonriseRegionFileIO.flushRegionStorages(level);
        }
    }
}
