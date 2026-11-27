package io.linearmc.horizon;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.linear.save.CoupledSave;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coupled save order on Horizon (fork parity: Folia {@code minecraft-0020} /
 * {@code paper-0013}, Canvas overlays and {@code LinearCoupledSave}). Same
 * shape as the fork adapter: a capture saves every chunk holder the current
 * thread owns, stages the players, and hands the core coordinator the chunk
 * I/O fences and the forced storage flush.
 *
 * <p>Host differences: on Paper the whole server is one "region" (the main
 * thread owns every chunk and player); on a Folia or Canvas host the current
 * region comes from {@code TickRegionScheduler.getCurrentRegion()}, read
 * reflectively because the Paper dev bundle does not have it. The chunk I/O
 * task map is private in Moonrise, so the fences read it reflectively too;
 * {@link #fencesResolved()} is checked per dev bundle by
 * {@code HorizonCoupledSaveTest}, and a host where it fails keeps coupled
 * save off with an ERROR instead of running it without fences.</p>
 */
public final class HorizonCoupledSave implements CoupledSave.Host {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    private static final Field CONTROLLER_TASKS;
    private static final Field TASK_CONTROLLER;
    private static final Field TASK_X;
    private static final Field TASK_Z;
    private static final Field TASK_FAILED_WRITE;
    private static final Method TABLE_GET;
    private static final Method TABLE_VALUES;
    private static final MethodHandle CURRENT_REGION;
    private static final String FENCE_ERROR;

    static {
        Field tasks = null;
        Field controller = null;
        Field x = null;
        Field z = null;
        Field failed = null;
        Method get = null;
        Method values = null;
        String error = null;
        try {
            tasks = MoonriseRegionFileIO.RegionDataController.class.getDeclaredField("chunkTasks");
            tasks.setAccessible(true);
            final Class<?> task = Class.forName(MoonriseRegionFileIO.class.getName() + "$ChunkIOTask", false,
                MoonriseRegionFileIO.class.getClassLoader());
            controller = accessible(task.getDeclaredField("regionDataController"));
            x = accessible(task.getDeclaredField("chunkX"));
            z = accessible(task.getDeclaredField("chunkZ"));
            failed = accessible(task.getDeclaredField("failedWrite"));
            get = tasks.getType().getMethod("get", long.class);
            values = tasks.getType().getMethod("valueIterator");
        } catch (final ReflectiveOperationException | RuntimeException e) {
            error = e.toString();
        }
        CONTROLLER_TASKS = tasks;
        TASK_CONTROLLER = controller;
        TASK_X = x;
        TASK_Z = z;
        TASK_FAILED_WRITE = failed;
        TABLE_GET = get;
        TABLE_VALUES = values;
        FENCE_ERROR = error;
        MethodHandle region = null;
        try {
            final Class<?> scheduler = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler", false,
                MinecraftServer.class.getClassLoader());
            region = MethodHandles.publicLookup().findStatic(scheduler, "getCurrentRegion",
                MethodType.methodType(scheduler.getMethod("getCurrentRegion").getReturnType()))
                .asType(MethodType.methodType(Object.class));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError paperHost) {
            // Paper: no regions, the main thread owns everything.
        }
        CURRENT_REGION = region;
    }

    private static Field accessible(final Field field) {
        field.setAccessible(true);
        return field;
    }

    /** Levels at capture time: the coupled-save thread never iterates the live level map. */
    private final List<ServerLevel> levels;

    private HorizonCoupledSave(final List<ServerLevel> levels) {
        this.levels = levels;
    }

    /** Whether the chunk I/O fences resolved on this host (else coupled save stays off). */
    public static boolean fencesResolved() {
        return FENCE_ERROR == null;
    }

    /** Whether this host has region threads (Folia, Canvas). */
    public static boolean hostHasRegions() {
        return CURRENT_REGION != null;
    }

    /**
     * Config load: publishes the keys, refusing to turn coupled save on when
     * the fences did not resolve (a capture without fences could write a
     * player file before its chunks).
     */
    public static void configure(final boolean enabled, final long intervalSeconds) {
        if (enabled && !fencesResolved()) {
            LOGGER.error("coupled-save.enabled is true but this host's chunk I/O tasks could not be read ({});"
                + " coupled save stays OFF", FENCE_ERROR);
            CoupledSave.configure(false, intervalSeconds);
            return;
        }
        CoupledSave.configure(enabled, intervalSeconds);
        if (enabled) {
            LOGGER.info("Coupled save: chunk I/O fences resolved; one capture per {}",
                hostHasRegions() ? "region (region-threaded host)" : "server (Paper host)");
        }
    }

    /** The ticking region on a region host ({@code null} on Paper or off a region thread); save-all claims. */
    static Object currentRegionForSaveAll() {
        return currentRegion();
    }

    private static Object currentRegion() {
        if (CURRENT_REGION == null) {
            return null;
        }
        try {
            return CURRENT_REGION.invokeExact();
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Periodic player autosave site (every tick, after the save-off check).
     * Returns {@code true} when coupled save owns player saving (the caller
     * skips the periodic player save); a capture runs when this region (the
     * server on Paper) is due. A region host's global tick has no region and
     * owns nothing.
     */
    public static boolean periodic(final MinecraftServer server) {
        if (!CoupledSave.enabled()) {
            return false;
        }
        final Object region = currentRegion();
        if (CURRENT_REGION != null && region == null) {
            return true;
        }
        if (CoupledSave.due(region) && ownsAPlayer(server)) {
            capture(server, () -> server.getPlayerList().saveAll(), true);
        }
        return true;
    }

    /**
     * Chunk I/O fences and the storage flush over every level, for a
     * {@code save-all flush} ticket; {@code null} when the fences did not
     * resolve on this host (the ticket then skips the fence wait).
     */
    public static CoupledSave.Host fences(final MinecraftServer server) {
        if (!fencesResolved()) {
            return null;
        }
        final List<ServerLevel> levels = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            levels.add(level);
        }
        return new HorizonCoupledSave(levels);
    }

    /**
     * A player is about to leave this region (fork parity:
     * {@code LinearCoupledSave.departure}; Folia/Canvas hosts only, through
     * {@code ServerPlayerMixin}): saves this region's chunks and stages its
     * players while this thread still owns both. On Paper one capture covers
     * every chunk, so the hook targets do not exist there.
     */
    public static void departure(final net.minecraft.server.level.ServerPlayer player) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server != null && player != null && shouldCapture(server, player)) {
            capture(server, () -> server.getPlayerList().saveAll(), true);
        }
    }

    /** Logout and {@code Player#saveData}: whether this save should run as a capture. */
    public static boolean shouldCapture(final MinecraftServer server, final Entity player) {
        return CoupledSave.active() && server.isRunning() && !saveOff(server) && TickThread.isTickThreadFor(player);
    }

    /** Logout and {@code Player#saveData}: a capture when {@link #shouldCapture}, the direct save otherwise. */
    public static void save(final MinecraftServer server, final Entity player, final Runnable save) {
        if (shouldCapture(server, player)) {
            capture(server, save, true);
        } else {
            save.run();
        }
    }

    /**
     * Opens a staging capture, saves every chunk holder this thread owns when
     * {@code saveChunks} (a Paper {@code save-all} saves them itself), runs
     * {@code stagePlayers}, then submits.
     */
    public static void capture(final MinecraftServer server, final Runnable stagePlayers, final boolean saveChunks) {
        final List<ServerLevel> levels = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            levels.add(level);
        }
        final HorizonCoupledSave host = new HorizonCoupledSave(levels);
        try (CoupledSave.Capture capture = CoupledSave.begin(host)) {
            if (saveChunks) {
                host.saveOwnedChunks();
            }
            stagePlayers.run();
        }
    }

    /** A region without players has no player file to order: its capture is skipped. */
    private static boolean ownsAPlayer(final MinecraftServer server) {
        for (final net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (TickThread.isTickThreadFor(player)) {
                return true;
            }
        }
        return false;
    }

    /** Any level with saving off ({@code save-off} flips every level; a plugin may flip one). */
    static boolean saveOff(final MinecraftServer server) {
        for (final ServerLevel level : server.getAllLevels()) {
            if (level.noSave) {
                return true;
            }
        }
        return false;
    }

    private void saveOwnedChunks() {
        for (final ServerLevel level : this.levels) {
            final List<NewChunkHolder> holders = ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolders();
            for (int i = 0, len = holders.size(); i < len; ++i) {
                final NewChunkHolder holder = holders.get(i);
                if (!TickThread.isTickThreadFor(level, holder.chunkX, holder.chunkZ)) {
                    continue;
                }
                try {
                    holder.save(false);
                } catch (final Throwable thrown) {
                    LOGGER.error("Coupled save: chunk ({},{}) in world '{}' failed to save", holder.chunkX, holder.chunkZ,
                        level.getWorld().getName(), thrown);
                }
            }
        }
    }

    @Override
    public void snapshotInFlight(final List<Object> out) {
        try {
            for (final ServerLevel level : this.levels) {
                for (final MoonriseRegionFileIO.RegionFileType type : MoonriseRegionFileIO.RegionFileType.values()) {
                    final Object table = CONTROLLER_TASKS.get(MoonriseRegionFileIO.getControllerFor(level, type));
                    for (final Iterator<?> iterator = (Iterator<?>) TABLE_VALUES.invoke(table); iterator.hasNext();) {
                        out.add(iterator.next());
                    }
                }
            }
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("chunk I/O tasks unreadable", e);
        }
    }

    @Override
    public int fenceState(final Object task) {
        try {
            final Object table = CONTROLLER_TASKS.get(TASK_CONTROLLER.get(task));
            final long key = CoordinateUtils.getChunkKey(TASK_X.getInt(task), TASK_Z.getInt(task));
            // Map read first, then failedWrite (fork parity: linear$fenceState). A failed
            // write stays FAILED even after the chunk system replaced its task, so the
            // barrier lists the I/O again instead of reading the replacement as done.
            final boolean inMap = TABLE_GET.invoke(table, key) == task;
            if (TASK_FAILED_WRITE.getBoolean(task)) {
                return CoupledSave.FAILED;
            }
            return inMap ? CoupledSave.PENDING : CoupledSave.DONE;
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("chunk I/O task unreadable", e);
        }
    }

    @Override
    public void flushStorages() throws IOException {
        for (final ServerLevel level : this.levels) {
            MoonriseRegionFileIO.flushRegionStorages(level);
        }
    }
}
