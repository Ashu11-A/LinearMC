package io.linearmc.horizon.mixins;

import io.linearmc.horizon.HorizonBufferedRegionFile;
import io.linearmc.horizon.HorizonLinearRegionFile;
import io.linearmc.horizon.LinearFormatPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.linear.LinearFlushCoordinator;
import net.linear.probe.LinearStorageProbe;
import net.linear.RegionFileFormat;
import net.linear.config.LinearFileConfig;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Linear-format dispatch for vanilla region storage.
 *
 * <p>Every selector below is verified against Mojang-mapped 26.1.x sources
 * (see {@code MAP-HORIZON.md}): no Moonrise-only targets, so this works on
 * plain Paper as well as region-threaded forks. The {@code Linear} files are
 * {@link HorizonLinearRegionFile} (IS-A {@code RegionFile}), hence no field
 * widening and no caller retargeting anywhere.
 *
 * <p>v1 scope: format/level resolve server-wide via core
 * {@link LinearFileConfig} (sysprop wins over the config.yml file values
 * pushed by {@code LinearPlugin#onEnable}, shipped ANVIL default fail-open).
 * Per-world Paper-config integration is a follow-up packet. The symlink guard
 * throws instead of halting the server (deliberate, documented divergence
 * from the fork).
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {

    private static final Pattern REGION_NAME =
        Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(mca|linear|b_linear)$");

    @Shadow
    private Path folder;

    @Shadow
    private static int REGION_SHIFT;

    /**
     * @author LinearMC
     * @reason Extension dispatch: LINEAR worlds create .linear files.
     */
    @Overwrite
    private static String getRegionFileName(final int chunkX, final int chunkZ) {
        // NOTE: static context has no storage instance, so the runtime
        // format override (reverse jobs) cannot be consulted here; the
        // configured extension comes from the same server-wide policy and
        // linear$openProbed retargets to the effective (override-aware)
        // extension when the computed name is missing. Per-world
        // granularity arrives with the config-integration packet.
        // Region coords are chunk coords shifted (REGION_SHIFT = 5);
        // naming itself is core-owned (LinearStorageProbe).
        return LinearStorageProbe.fileNameFor(
            chunkX >> REGION_SHIFT,
            chunkZ >> REGION_SHIFT,
            LinearFileConfig.resolveFormat());
    }

    /**
     * Shared open behind BOTH construction sites (audited by javap against
     * the real Canvas 26.1.2 {@code RegionFileStorage}, which has exactly
     * two {@code new RegionFile} occurrences):
     * <ul>
     *   <li>{@code getRegionFile(ChunkPos, boolean)} — create path;</li>
     *   <li>{@code moonrise$getRegionFileIfExists(int, int)} — Moonrise
     *   exists/read/write path (same 4-arg ctor shape
     *   {@code (RegionStorageInfo, Path, Path, boolean)}). An earlier
     *   revision covered only the first site (verified on the 26.3 bundle,
     *   whose class has a single site), so vanilla Anvil code parsed
     *   {@code .linear} files on the exists path: giant out-of-bounds
     *   sectors, {@code Negative position}, and header-rewriting data loss.
     *   Never assume one site again — re-audit on new server shapes.</li>
     * </ul>
     * Extension dispatch, triple-read probe (.mca, then .linear, then
     * .b_linear), linear-family symlink guard.
     */
    private RegionFile linear$openProbed(
        final RegionStorageInfo openInfo,
        final Path regionPath,
        final Path openFolder,
        final boolean openSync
    ) throws IOException {
        final Path scopeFolder = openFolder != null ? openFolder : regionPath.getParent();
        final RegionFileFormat configured = LinearFormatPolicy.resolveEffectiveFormat(
            LinearFormatPolicy.fileFormat(),
            scopeFolder != null ? scopeFolder.toString() : null,
            this.folder != null ? this.folder.toString() : null);
        final int level = LinearFileConfig.resolveLevel();
        final String name = regionPath.getFileName().toString();
        if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
            if (configured == RegionFileFormat.ANVIL && !Files.exists(regionPath)) {
                // Reverse override (buffered mirror of the .linear path
                // below): retarget the missing .b_linear name to its .mca
                // sibling so new writes land in .mca.
                final String mcaName = name.substring(0,
                    name.length() - RegionFileFormat.B_LINEAR_EXTENSION.length())
                    + RegionFileFormat.ANVIL_EXTENSION;
                final Path mcaPath = regionPath.resolveSibling(mcaName);
                this.linear$guardSymlink(mcaPath, configured);
                return new RegionFile(openInfo, mcaPath, openFolder, openSync);
            }
            if (!Files.exists(regionPath)) {
                // Family switch (LINEAR -> B_LINEAR): an existing .linear
                // sibling keeps serving instead of starting a fresh
                // .b_linear beside it.
                final int[] coords = linear$regionCoords(name);
                final Path folder = openFolder != null ? openFolder : regionPath.getParent();
                if (coords != null && folder != null) {
                    final Path sibling =
                        LinearStorageProbe.siblingIfProbed(folder, coords[0], coords[1]);
                    if (sibling != null) {
                        this.linear$guardSymlink(sibling, configured);
                        return new HorizonLinearRegionFile(sibling, level);
                    }
                }
            }
            this.linear$guardSymlink(regionPath, configured);
            return new HorizonBufferedRegionFile(regionPath, level);
        }
        if (name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            if (configured == RegionFileFormat.ANVIL && !Files.exists(regionPath)) {
                // Reverse override: the name was computed from the stale file
                // value, but the effective format is ANVIL — retarget the
                // missing .linear name to its .mca sibling so new writes
                // land in .mca. Existing .linear files below keep serving
                // reads through triple-read.
                final String mcaName = name.substring(0,
                    name.length() - RegionFileFormat.LINEAR_EXTENSION.length())
                    + RegionFileFormat.ANVIL_EXTENSION;
                final Path mcaPath = regionPath.resolveSibling(mcaName);
                this.linear$guardSymlink(mcaPath, configured);
                return new RegionFile(openInfo, mcaPath, openFolder, openSync);
            }
            if (!Files.exists(regionPath)) {
                // Family switch (B_LINEAR -> LINEAR): an existing .b_linear
                // sibling keeps serving instead of starting a fresh
                // .linear beside it.
                final int[] coords = linear$regionCoords(name);
                final Path folder = openFolder != null ? openFolder : regionPath.getParent();
                if (coords != null && folder != null) {
                    final Path bufferedSibling =
                        LinearStorageProbe.bufferedSiblingIfProbed(folder, coords[0], coords[1]);
                    if (bufferedSibling != null) {
                        this.linear$guardSymlink(bufferedSibling, configured);
                        return new HorizonBufferedRegionFile(bufferedSibling, level);
                    }
                }
            }
            this.linear$guardSymlink(regionPath, configured);
            return new HorizonLinearRegionFile(regionPath, level);
        }
        if (!Files.exists(regionPath)) {
            // Triple-read probe: an .mca that was never written falls back to
            // its .linear, then .b_linear sibling; a brand-new region honors
            // the configured format even if the requested name disagrees
            // (config changed since the name was computed). Both decisions
            // are core-owned.
            final int[] coords = linear$regionCoords(name);
            final Path folder = openFolder != null ? openFolder : regionPath.getParent();
            if (coords != null && folder != null) {
                final Path sibling =
                    LinearStorageProbe.siblingIfProbed(folder, coords[0], coords[1]);
                if (sibling != null) {
                    this.linear$guardSymlink(sibling, configured);
                    return new HorizonLinearRegionFile(sibling, level);
                }
                final Path bufferedSibling =
                    LinearStorageProbe.bufferedSiblingIfProbed(folder, coords[0], coords[1]);
                if (bufferedSibling != null) {
                    this.linear$guardSymlink(bufferedSibling, configured);
                    return new HorizonBufferedRegionFile(bufferedSibling, level);
                }
                if (LinearStorageProbe.shouldUseLinear(folder, coords[0], coords[1], configured)) {
                    final Path target = LinearStorageProbe.targetForSource(regionPath);
                    this.linear$guardSymlink(target, configured);
                    return new HorizonLinearRegionFile(target, level);
                }
                if (LinearStorageProbe.shouldUseBuffered(folder, coords[0], coords[1], configured)) {
                    final Path target = LinearStorageProbe.bufferedTargetForSource(regionPath);
                    this.linear$guardSymlink(target, configured);
                    return new HorizonBufferedRegionFile(target, level);
                }
            } else {
                // Unparseable name (never produced by getRegionFileName):
                // legacy extension-swap probe, fail-open to vanilla.
                final Path sibling = LinearStorageProbe.targetForSource(regionPath);
                if (Files.exists(sibling)) {
                    this.linear$guardSymlink(sibling, configured);
                    return new HorizonLinearRegionFile(sibling, level);
                }
            }
        } else {
            this.linear$guardSymlink(regionPath, configured);
        }
        return new RegionFile(openInfo, regionPath, openFolder, openSync);
    }

    /**
     * Linear-family broken-symlink guard (throws; never halts a shared
     * server). Takes the already-resolved effective format so the guard can
     * never disagree with the dispatch decision above (same scopes, same
     * value — a scope mismatch here once meant the guard and the opener
     * could pick different formats for one path). Honors
     * {@code region-format.linear.crash-on-broken-symlink} (default true):
     * false skips the guard, mirroring the Canvas early-return.
     */
    private void linear$guardSymlink(final Path path, final RegionFileFormat configured) throws IOException {
        if (!LinearFileConfig.resolveCrashOnBrokenSymlink()) {
            return;
        }
        if (LinearStorageProbe.shouldRefuseSymlink(configured, path)) {
            throw new IOException("LinearMC: refusing broken region symlink: " + path);
        }
    }

    /** Region coords from an {@code r.<x>.<z>.*} name, or {@code null}. */
    private static int[] linear$regionCoords(final String name) {
        final Matcher m = REGION_NAME.matcher(name);
        if (!m.matches()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (final NumberFormatException bad) {
            return null;
        }
    }

    @Redirect(
        method = "getRegionFile(Lnet/minecraft/world/level/ChunkPos;Z)Lnet/minecraft/world/level/chunk/storage/RegionFile;",
        at = @At(
            value = "NEW",
            // BeforeNew takes the CLASS, not the ctor descriptor:
            // ":<init>(...)" makes the owner unparseable
            // (InvalidMemberDescriptorException: Invalid owner).
            target = "net/minecraft/world/level/chunk/storage/RegionFile"
        )
    )
    private RegionFile linear$probeCreate(
        final RegionStorageInfo openInfo,
        final Path regionPath,
        final Path openFolder,
        final boolean openSync
    ) throws IOException {
        return this.linear$openProbed(openInfo, regionPath, openFolder, openSync);
    }

    // Exists/read/write path. Host-shape tolerant (require = 0 above): some
    // live hosts carry no Moonrise bridge method here (observed: the whole
    // mixin was rejected, killing the create path too). Absence of the
    // method means absence of the path, so skipping is correct — but a typo
    // would skip identically, hence the CI per-bundle reflection gate
    // (MixinDescriptorsTest#mixinTargetsExistInThisBundle) owns typo
    // protection, and LinearPlugin logs the live coverage at enable.
    @Redirect(
        method = "moonrise$getRegionFileIfExists(II)Lnet/minecraft/world/level/chunk/storage/RegionFile;",
        require = 0,
        at = @At(
            value = "NEW",
            // Same BeforeNew-takes-CLASS rule as above.
            target = "net/minecraft/world/level/chunk/storage/RegionFile"
        )
    )
    private RegionFile linear$probeExists(
        final RegionStorageInfo openInfo,
        final Path regionPath,
        final Path openFolder,
        final boolean openSync
    ) throws IOException {
        return this.linear$openProbed(openInfo, regionPath, openFolder, openSync);
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
