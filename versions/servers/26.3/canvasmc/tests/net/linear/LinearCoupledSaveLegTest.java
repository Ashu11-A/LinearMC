package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import io.canvasmc.canvas.GlobalConfiguration;
import io.papermc.paper.linear.LinearCoupledSave;
import net.linear.io.PlayerFileDurability;
import net.linear.save.CoupledSave;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.players.NameAndId;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Canvas leg of the coupled save order (overlays {@code MoonriseRegionFileIO},
 * {@code MinecraftServer}, {@code PlayerList}, {@code PlayerDataStorage},
 * {@code ServerStatsCounter}, {@code PlayerAdvancements}, {@code SaveAllCommand},
 * {@code RegionShutdownThread}, {@code CraftPlayer}, the global config, and
 * the leg file {@code LinearCoupledSave}; same hunks as Folia minecraft-0020
 * / paper-0013): the two keys default off / 20 s like the other legs (Canvas
 * publishes them from its config load, covered by the core resolve test);
 * inside a capture the
 * real {@code ServerStatsCounter.save} stages instead of writing and the
 * file lands only after the barrier; {@code PlayerDataStorage.load} reads a
 * staged save before the file; the adapter and the I/O fence hooks exist.
 */
public class LinearCoupledSaveLegTest {

    private static final String PLAYER = "85a1efec-0000-4000-8000-00000000abcd";

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() {
        CoupledSave.resetForTests();
        PlayerFileDurability.setEnabled(true);
    }

    /** No fences; the flush waits on a gate the test opens. */
    private static final class GatedHost implements CoupledSave.Host {
        final CountDownLatch gate = new CountDownLatch(1);

        @Override
        public void snapshotInFlight(final List<Object> out) {
        }

        @Override
        public int fenceState(final Object task) {
            return CoupledSave.DONE;
        }

        @Override
        public void flushStorages() {
            try {
                this.gate.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    public void coupledSaveKeysDefaultLikeTheOtherLegs() {
        GlobalConfiguration.Linear.CoupledSave coupled = new GlobalConfiguration.Linear().coupledSave;
        assertTrue(coupled.enabled, "coupled-save.enabled default true");
        assertEquals(20L, coupled.interval, "coupled-save.interval default 20 s");
        assertEquals(20L, CoupledSave.resolveIntervalSeconds(0, message -> { }), "below 1 s falls back to 20");
        CoupledSave.configure(true, 20L);
        assertTrue(PlayerFileDurability.enabled(), "coupled save implies durable player data");
    }

    @Test
    public void statsSavedInACaptureLandOnlyAfterTheBarrier(@TempDir Path dir) throws Exception {
        CoupledSave.configure(true, 20L);
        Path file = dir.resolve(PLAYER + ".json");
        ServerStatsCounter stats = new ServerStatsCounter(null, file);
        GatedHost host = new GatedHost();

        try (CoupledSave.Capture capture = CoupledSave.begin(host)) {
            stats.save();
            assertEquals(1, capture.stagedCount(), "staged, not written");
        }
        assertFalse(Files.exists(file), "nothing on disk before the region barrier");
        byte[] staged = CoupledSave.stagedBytes(file);
        assertNotNull(staged);
        assertTrue(new String(staged, java.nio.charset.StandardCharsets.UTF_8).contains("DataVersion"));
        assertTrue(PlayerFileDurability.isReadable(file), "a login reads the staged stats");

        host.gate.countDown();
        CoupledSave.awaitSubmitted(10_000);
        assertTrue(Files.readString(file).contains("DataVersion"));
        assertNull(CoupledSave.stagedBytes(file));
    }

    @Test
    public void playerDataLoadReadsAStagedSaveBeforeTheFile(@TempDir Path root) throws Exception {
        CoupledSave.configure(true, 20L);
        try (LevelStorageSource.LevelStorageAccess access = LevelStorageSource.createDefault(root).createAccess("world")) {
            Path players = access.getLevelPath(LevelResource.PLAYER_DATA_DIR);
            PlayerDataStorage storage = new PlayerDataStorage(access, null);
            NameAndId id = new NameAndId(UUID.fromString(PLAYER), "yasmim");

            CompoundTag tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
            tag.putString("linear", "staged");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bytes);
            GatedHost host = new GatedHost();
            try (CoupledSave.Capture capture = CoupledSave.begin(host)) {
                CoupledSave.stageDat(players.resolve(PLAYER + ".dat"), players.resolve(PLAYER + ".dat_old"), bytes.toByteArray());
            }

            Optional<CompoundTag> loaded = storage.load(id);
            assertEquals("staged", loaded.orElseThrow().getStringOr("linear", ""), "a rejoin before the write sees the staged save");
            assertFalse(Files.exists(players.resolve(PLAYER + ".dat")));

            host.gate.countDown();
            CoupledSave.awaitSubmitted(10_000);
            assertTrue(Files.exists(players.resolve(PLAYER + ".dat")));
            assertEquals("staged", NbtIo.readCompressed(players.resolve(PLAYER + ".dat"), net.minecraft.nbt.NbtAccounter.unlimitedHeap())
                .getStringOr("linear", ""), "the file holds the staged save");
        }
    }

    @Test
    public void departureAndSaveAllFenceHooksExist() throws Exception {
        assertNotNull(LinearCoupledSave.class.getMethod("departure", net.minecraft.server.level.ServerPlayer.class));
        assertNotNull(LinearCoupledSave.class.getMethod("fences", net.minecraft.server.MinecraftServer.class));
        assertNotNull(net.minecraft.server.level.ServerPlayer.class.getMethod("preChangeDimension"));
        // No server in this JVM: the departure hook is a no-op, never a crash.
        CoupledSave.configure(true, 20L);
        LinearCoupledSave.departure(null);
    }

    /** One chunk write still queued: the fences a coupled save-all ticket waits for. */
    private static final class QueuedWriteHost implements CoupledSave.Host {
        volatile int state = CoupledSave.PENDING;

        @Override
        public void snapshotInFlight(final List<Object> out) {
            out.add(this);
        }

        @Override
        public int fenceState(final Object task) {
            return this.state;
        }

        @Override
        public void flushStorages() {
        }
    }

    @Test
    public void coupledSaveAllWithNoPlayersWaitsForAQueuedChunkWrite(@TempDir Path level) throws Exception {
        // No player anywhere: no capture stages anything, so no capture fences
        // the chunk saves the region tasks queued. The ticket itself must.
        CoupledSave.configure(true, 20L);
        net.linear.save.SaveAllBarrier.setBarrierForTests(() -> { });
        try {
            QueuedWriteHost fences = new QueuedWriteHost();
            net.linear.save.SaveAllBarrier.Ticket ticket = net.linear.save.SaveAllBarrier.begin(level, true, fences,
                net.linear.save.SaveAllBarrier.DEFAULT_WAIT_MILLIS);
            ticket.taskQueued();
            ticket.claimRegion(new Object());
            ticket.taskDone();
            CountDownLatch done = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<net.linear.save.SaveAllBarrier.Result> result =
                new java.util.concurrent.atomic.AtomicReference<>();
            ticket.seal(r -> {
                result.set(r);
                done.countDown();
            });
            assertFalse(done.await(200, TimeUnit.MILLISECONDS), "save-all done while a chunk write was still queued");
            assertFalse(Files.exists(net.linear.save.SaveAllBarrier.markerPath(level)), "no marker yet");
            fences.state = CoupledSave.DONE;
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(0, result.get().failed());
            assertTrue(result.get().markerWritten());
        } finally {
            net.linear.save.SaveAllBarrier.setBarrierForTests(null);
        }
    }

    @Test
    public void adapterAndFenceHooksExist() throws Exception {
        assertNotNull(MoonriseRegionFileIO.class.getMethod("linear$snapshotInFlight", net.minecraft.server.level.ServerLevel.class, List.class));
        assertNotNull(MoonriseRegionFileIO.class.getMethod("linear$fenceState", Object.class));
        assertFalse(LinearCoupledSave.periodic(null), "off: the periodic player save runs as before");
        CoupledSave.configure(true, 20L);
        assertTrue(LinearCoupledSave.periodic(null), "on: the periodic player save is skipped (no region on this thread)");
    }
}
