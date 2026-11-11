package io.linearmc.horizon.mixins;

import io.linearmc.horizon.DeferredRegionCloses;
import io.linearmc.horizon.HorizonSeams;
import io.linearmc.horizon.LinearFormatPolicy;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.linear.LinearFlushCoordinator;
import net.linear.RegionFileFormat;
import net.linear.config.LinearFileConfig;
import net.linear.flush.FlushReports;
import net.linear.probe.LinearStorageProbe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Linear dispatch for vanilla region storage. Every selector is checked per
 * dev bundle and per host method by {@code MixinDescriptorsTest}: 26.1.2 has
 * its own Moonrise exists body, 26.3 delegates it to {@code getRegionFile}.
 * Format and level resolve per storage folder through core
 * {@link LinearFileConfig} (sysprop, runtime reverse override, per-world, global).
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {

    private static final Logger LINEAR$LOG = LoggerFactory.getLogger("LinearMC");

    @Shadow
    private Path folder;

    @Shadow
    private static int REGION_SHIFT;

    /**
     * @author LinearMC
     * @reason Configured extension for host call sites not redirected below.
     */
    @Overwrite
    private static String getRegionFileName(final int chunkX, final int chunkZ) {
        return LinearStorageProbe.fileNameFor(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT, LinearFileConfig.resolveFormat());
    }

    private RegionFileFormat linear$format() {
        final String scope = this.folder.toString();
        return LinearFormatPolicy.resolveEffectiveFormat(LinearFileConfig.fileFormatFor(scope), scope);
    }

    /**
     * Existing-first region name: the host checks {@code Files.exists} on this
     * name before any open hook, so an existing region in another format must
     * keep its name or it reads as absent and is regenerated. A dangling
     * linear symlink halts here for the same reason.
     */
    @Redirect(
        method = {
            "getRegionFile(Lnet/minecraft/world/level/ChunkPos;Z)Lnet/minecraft/world/level/chunk/storage/RegionFile;",
            "moonrise$getRegionFileIfExists(II)Lnet/minecraft/world/level/chunk/storage/RegionFile;"
        },
        require = 1,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;getRegionFileName(II)Ljava/lang/String;")
    )
    private String linear$existingFirstName(final int chunkX, final int chunkZ) throws IOException {
        final RegionFileFormat configured = this.linear$format();
        // Recorded before the host's exists check: a live convert never deletes
        // this .mca, and a region converted away resolves to its new file.
        final String name = LinearStorageProbe.hostOpening(this.folder.resolve(LinearStorageProbe.resolveFileName(
            this.folder, chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT, configured))).getFileName().toString();
        this.linear$guardSymlink(this.folder.resolve(name), configured);
        return name;
    }

    /** Both {@code new RegionFile} sites: dispatch by extension. */
    @Redirect(
        method = {
            "getRegionFile(Lnet/minecraft/world/level/ChunkPos;Z)Lnet/minecraft/world/level/chunk/storage/RegionFile;",
            "moonrise$getRegionFileIfExists(II)Lnet/minecraft/world/level/chunk/storage/RegionFile;"
        },
        require = 1,
        at = @At(value = "NEW", target = "net/minecraft/world/level/chunk/storage/RegionFile")
    )
    private RegionFile linear$open(final RegionStorageInfo info, final Path regionPath, final Path openFolder,
        final boolean sync) throws IOException {
        DeferredRegionCloses.awaitPending(regionPath);
        Path path = regionPath;
        if (!Files.exists(path)) {
            // A new region, or a call site that bypassed the name redirect:
            // existing-first again (a dangling symlink also lands here).
            final RegionFileFormat configured = this.linear$format();
            final int[] c = LinearStorageProbe.parseRegionCoords(path.getFileName().toString());
            if (c != null) {
                path = this.folder.resolve(LinearStorageProbe.resolveFileName(this.folder, c[0], c[1], configured));
            }
            this.linear$guardSymlink(path, configured);
        }
        // Host open of the final path (fork factory parity).
        return HorizonSeams.openRegion(info, LinearStorageProbe.hostOpening(path), openFolder, sync);
    }

    /** Broken linear symlink: halt the server, then fail (fork parity). */
    private void linear$guardSymlink(final Path path, final RegionFileFormat configured) throws IOException {
        if (LinearFileConfig.resolveCrashOnBrokenSymlink() && LinearStorageProbe.shouldRefuseSymlink(configured, path)) {
            LINEAR$LOG.error("Linear region file {} is a broken symbolic link, crashing to prevent data loss", path);
            final MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                server.halt(false);
            }
            throw new IOException("LinearMC: refusing broken region symlink: " + path);
        }
    }

    /** Region-cache hit/miss counters (fork minecraft-0010 parity). */
    @Redirect(
        method = {
            "getRegionFile(Lnet/minecraft/world/level/ChunkPos;Z)Lnet/minecraft/world/level/chunk/storage/RegionFile;",
            "moonrise$getRegionFileIfExists(II)Lnet/minecraft/world/level/chunk/storage/RegionFile;"
        },
        require = 1,
        at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;getAndMoveToFirst(J)Ljava/lang/Object;")
    )
    private Object linear$countedLookup(final Long2ObjectLinkedOpenHashMap<?> cache, final long key) {
        final Object cached = cache.getAndMoveToFirst(key);
        if (cached == null) {
            FlushReports.reportCacheMissQuietly(this.folder);
        } else if (!(cached instanceof Optional<?> o) || o.isPresent()) {
            FlushReports.reportCacheHitQuietly(this.folder);
        }
        return cached;
    }

    /** Flush-unlock: an evicted region closes outside the monitor (fork minecraft-0011 parity). */
    @Redirect(
        method = {
            "getRegionFile(Lnet/minecraft/world/level/ChunkPos;Z)Lnet/minecraft/world/level/chunk/storage/RegionFile;",
            "moonrise$getRegionFileIfExists(II)Lnet/minecraft/world/level/chunk/storage/RegionFile;"
        },
        require = 1,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/RegionFile;close()V")
    )
    private void linear$deferEvictionClose(final RegionFile evicted) {
        DeferredRegionCloses.defer(evicted);
    }

    // Drain only outside the monitor: getRegionFile holds it in a block (its
    // RETURN is outside), but 26.3 also reaches it re-entrantly from the
    // synchronized exists path, whose callers drain instead.
    @Inject(
        method = {"getRegionFile(Lnet/minecraft/world/level/ChunkPos;Z)Lnet/minecraft/world/level/chunk/storage/RegionFile;", "moonrise$readData", "moonrise$finishRead"},
        at = @At("RETURN"),
        require = 1
    )
    private void linear$drainAfterRead(final CallbackInfoReturnable<?> cir) {
        if (!Thread.holdsLock(this)) {
            DeferredRegionCloses.drain();
        }
    }

    @Inject(
        method = {"moonrise$finishWrite", "write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V"},
        at = @At("RETURN"),
        require = 1
    )
    private void linear$drainAfterWrite(final CallbackInfo ci) {
        if (!Thread.holdsLock(this)) {
            DeferredRegionCloses.drain();
        }
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void linear$evictOnClose(final CallbackInfo ci) {
        LinearFlushCoordinator.evict(this.folder);
    }

    @Inject(method = "flush", at = @At("HEAD"))
    private void linear$flushDirtyFirst(final CallbackInfo ci) {
        LinearFlushCoordinator.forFolder(this.folder).flushDirty();
    }
}
