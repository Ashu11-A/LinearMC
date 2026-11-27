package io.linearmc.horizon;

import it.unimi.dsi.fastutil.longs.LongList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.linear.save.CoupledSave;
import net.linear.save.SaveAllBarrier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code save-all} on a region-threaded host (Folia, CanvasMC) under Horizon:
 * the same shape as the fork legs (Folia {@code minecraft-0019}, Canvas
 * {@code SaveAllCommand} overlay). One tick task per owned region section
 * (tasks follow their section through merges and splits); the first task
 * that runs in a region saves that region's chunks (flush: the storages go
 * through the forced Linear barrier) and then only that region's players,
 * so a player file is never written before its region's chunks. A sealed
 * core ticket counts the tasks; a flush save-all then waits for the chunk
 * I/O fences and staged coupled saves, runs the forced coordinator barrier
 * off-thread and writes {@code <level>/linear-checkpoint/save-all.done}
 * before success is reported.
 *
 * <p>The region API is not in the Paper dev bundle, so it is resolved
 * reflectively once ({@link #resolveError()}). On a region host where it
 * does not resolve the command fails loudly instead of falling back to
 * {@code saveEverything} off its owning threads. CanvasMC's global and
 * world data save ({@code autosaveSafeGlobalData}/{@code autosaveSafeWorldData})
 * runs first when the host has it, as in the Canvas overlay; Folia has no
 * such step (its fork leg leaves global data to its own schedule).</p>
 */
public final class HorizonRegionSaveAll {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    private static final Method RS_INSTANCE;
    private static final Field RS_TASK_QUEUE;
    private static final Method CREATE_TICK_TASK;
    private static final Method TASK_QUEUE;
    private static final Field LEVEL_REGIONISER;
    private static final Method COMPUTE_FOR_ALL;
    private static final Field SECTION_SHIFT;
    private static final Method OWNED_SECTIONS;
    private static final Method SAVE_REGIONISED;
    /** Optional (CanvasMC): may be null on a host that resolved otherwise. */
    private static final Method HAS_NO_ALIVE;
    private static final Method IS_DEAD;
    private static final Method SCHEDULE_TO_OR_EXECUTE;
    private static final Method GLOBAL_SAVE;
    private static final Method WORLD_SAVE;
    private static final String ERROR;

    static {
        Method instance = null;
        Field taskQueue = null;
        Method createTick = null;
        Method queue = null;
        Field regioniser = null;
        Method computeAll = null;
        Field shift = null;
        Method owned = null;
        Method saveRegionised = null;
        Method noAlive = null;
        Method dead = null;
        Method schedule = null;
        Method global = null;
        Method world = null;
        String error = null;
        try {
            final ClassLoader loader = MinecraftServer.class.getClassLoader();
            final Class<?> server = Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, loader);
            instance = server.getMethod("getInstance");
            taskQueue = server.getField("taskQueue");
            createTick = taskQueue.getType().getMethod("createTickTaskQueue", ServerLevel.class, int.class, int.class,
                Runnable.class);
            queue = createTick.getReturnType().getMethod("queue");
            regioniser = ServerLevel.class.getField("regioniser");
            computeAll = regioniser.getType().getMethod("computeForAllRegions", Consumer.class);
            shift = regioniser.getType().getField("sectionChunkShift");
            final Class<?> region = Class.forName(regioniser.getType().getName() + "$ThreadedRegion", false, loader);
            owned = region.getMethod("getOwnedSections");
            final Class<?> holders = Class.forName(
                "ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager", false, loader);
            saveRegionised = holders.getMethod("saveAllChunksRegionised", boolean.class, boolean.class, boolean.class,
                boolean.class, boolean.class, boolean.class, boolean.class);
            noAlive = optional(region, "hasNoAliveSections");
            dead = optional(region, "isDead");
            schedule = optional(server, "scheduleToOrExecute", Runnable.class);
            global = optional(server, "autosaveSafeGlobalData", boolean.class, boolean.class);
            world = optional(server, "autosaveSafeWorldData", boolean.class, boolean.class, ServerLevel.class);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            error = e.toString();
        }
        RS_INSTANCE = instance;
        RS_TASK_QUEUE = taskQueue;
        CREATE_TICK_TASK = createTick;
        TASK_QUEUE = queue;
        LEVEL_REGIONISER = regioniser;
        COMPUTE_FOR_ALL = computeAll;
        SECTION_SHIFT = shift;
        OWNED_SECTIONS = owned;
        SAVE_REGIONISED = saveRegionised;
        HAS_NO_ALIVE = noAlive;
        IS_DEAD = dead;
        SCHEDULE_TO_OR_EXECUTE = schedule;
        GLOBAL_SAVE = global;
        WORLD_SAVE = world;
        ERROR = error;
    }

    private HorizonRegionSaveAll() {
    }

    private static Method optional(final Class<?> owner, final String name, final Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (final NoSuchMethodException absent) {
            return null;
        }
    }

    /** Null when the region API resolved on this host; otherwise why not. */
    public static String resolveError() {
        return ERROR;
    }

    /** Whether this host gets the region-scheduled save-all (region threads and a resolved API). */
    public static boolean applies() {
        return HorizonCoupledSave.hostHasRegions();
    }

    /**
     * Runs {@code save-all [flush]} on a region host. Called from the global
     * region thread (the command thread there); returns at once, the result
     * is reported to {@code source} when the ticket completes.
     */
    public static void saveAll(final CommandSourceStack source, final MinecraftServer server, final boolean flush) {
        if (ERROR != null) {
            LOGGER.error("save-all: the region API of this host could not be resolved ({}); nothing was saved", ERROR);
            source.sendFailure(Component.literal("LinearMC: save-all unavailable on this host (" + ERROR + ")"));
            return;
        }
        final Runnable run = () -> {
            try {
                saveGlobalData(source);
                scheduleRegions(source, server, flush);
            } catch (final Throwable thrown) {
                LOGGER.error("save-all failed to schedule", thrown);
                source.sendFailure(Component.literal("LinearMC: save-all failed: " + thrown));
            }
        };
        if (SCHEDULE_TO_OR_EXECUTE != null) {
            try {
                SCHEDULE_TO_OR_EXECUTE.invoke(RS_INSTANCE.invoke(null), run);
                return;
            } catch (final ReflectiveOperationException e) {
                LOGGER.warn("save-all: scheduleToOrExecute failed, running on the command thread", e);
            }
        }
        run.run();
    }

    /** CanvasMC only: the safe global and world data save its own save-all runs first. */
    private static void saveGlobalData(final CommandSourceStack source) throws ReflectiveOperationException {
        if (GLOBAL_SAVE == null || WORLD_SAVE == null) {
            return;
        }
        final Object regionized = RS_INSTANCE.invoke(null);
        GLOBAL_SAVE.invoke(regionized, true, true);
        for (final ServerLevel level : source.getServer().getAllLevels()) {
            WORLD_SAVE.invoke(regionized, true, true, level);
        }
        source.sendSuccess(() -> Component.literal("Saved global data"), true);
    }

    private static void scheduleRegions(final CommandSourceStack source, final MinecraftServer server,
                                        final boolean flush) throws ReflectiveOperationException {
        final SaveAllBarrier.Ticket ticket = SaveAllBarrier.begin(server.getWorldPath(LevelResource.ROOT), flush,
            HorizonCoupledSave.fences(server), SaveAllBarrier.DEFAULT_WAIT_MILLIS);
        final Object taskQueue = RS_TASK_QUEUE.get(RS_INSTANCE.invoke(null));
        int queued = 0;
        for (final ServerLevel level : server.getAllLevels()) {
            final Object regioniser = LEVEL_REGIONISER.get(level);
            final List<Object> regions = new ArrayList<>();
            COMPUTE_FOR_ALL.invoke(regioniser, (Consumer<Object>) regions::add);
            final int shift = SECTION_SHIFT.getInt(regioniser);
            for (final Object region : regions) {
                if (isTrue(HAS_NO_ALIVE, region) || isTrue(IS_DEAD, region)) {
                    continue;
                }
                final LongList sections = (LongList) OWNED_SECTIONS.invoke(region);
                for (int i = 0, len = sections.size(); i < len; ++i) {
                    final long section = sections.getLong(i);
                    final int sectionX = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(section);
                    final int sectionZ = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(section);
                    ticket.taskQueued();
                    ++queued;
                    boolean queuedOk = false;
                    try {
                        final Object task = CREATE_TICK_TASK.invoke(taskQueue, level, sectionX << shift,
                            sectionZ << shift, (Runnable) () -> saveCurrentRegion(server, level, ticket, flush));
                        queuedOk = (Boolean) TASK_QUEUE.invoke(task);
                    } finally {
                        if (!queuedOk) {
                            ticket.regionFailed(); // cancelled before it could run: never report a clean save-all
                            ticket.taskDone();
                        }
                    }
                }
            }
        }
        final int queuedSections = queued;
        if (queuedSections == 0) {
            source.sendSuccess(() -> Component.literal("No regions to save"), true);
        } else {
            source.sendSuccess(() -> Component.literal("Saving regions (" + queuedSections + " region sections)"), true);
        }
        final long startNanos = System.nanoTime();
        ticket.seal(result -> {
            LOGGER.info("Saved {} regions in {}s", result.regions(),
                String.format("%.2f", (System.nanoTime() - startNanos) / 1.0E9));
            if (result.failed() > 0 || (result.flush() && !result.markerWritten())) {
                source.sendFailure(Component.translatable("commands.save.failed")
                    .append(Component.literal(" (" + result.summary() + ")")));
            } else {
                source.sendSuccess(() -> Component.translatable("commands.save.success"), true);
            }
        });
    }

    private static boolean isTrue(final Method method, final Object region) throws ReflectiveOperationException {
        return method != null && (Boolean) method.invoke(region);
    }

    /** One region's save on its own tick thread: chunks first, then that region's players. */
    private static void saveCurrentRegion(final MinecraftServer server, final ServerLevel level,
                                          final SaveAllBarrier.Ticket ticket, final boolean flush) {
        try {
            if (ticket.claimRegion(HorizonCoupledSave.currentRegionForSaveAll())) {
                if (CoupledSave.active()) {
                    // Coupled save: chunks saved and players staged here, never blocking this
                    // thread on I/O; the ticket's barrier waits for the staged players first.
                    HorizonCoupledSave.capture(server, () -> server.getPlayerList().saveAll(), true);
                } else {
                    final Object holders = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level)
                        .moonrise$getChunkTaskScheduler().chunkHolderManager;
                    SAVE_REGIONISED.invoke(holders, flush, false, false, false, false, true, false);
                    server.getPlayerList().saveAll(); // only players owned by this region thread
                }
            }
        } catch (final Throwable thrown) {
            ticket.regionFailed();
            LOGGER.error("save-all {}: region save failed in world '{}'", ticket.id(), level.getWorld().getName(), thrown);
        } finally {
            ticket.taskDone();
        }
    }
}
