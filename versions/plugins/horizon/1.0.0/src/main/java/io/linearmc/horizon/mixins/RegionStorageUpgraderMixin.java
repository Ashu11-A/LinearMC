package io.linearmc.horizon.mixins;

import io.linearmc.horizon.HorizonSeams;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import net.linear.probe.LinearStorageProbe;
import net.minecraft.util.worldupdate.RegionStorageUpgrader;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code --forceUpgrade} / {@code --recreateRegionFiles} see Linear regions
 * (fork parity: Folia {@code minecraft-0009} RegionStorageUpgrader, Canvas
 * overlay). Vanilla enumerates only {@code r.x.z.mca} and probes each file
 * with a raw {@code new RegionFile}, which would parse a {@code .linear}
 * file as Anvil; the chunk upgrade itself already flows through the mixed
 * {@code RegionFileStorage}. Recreate mode keeps vanilla's same-name swap:
 * a region rewritten under another extension is not swapped (vanilla logs it
 * and keeps the old file), because a Linear rewrite may still sit unflushed
 * in the {@code new_} storage at that point.
 */
@Mixin(RegionStorageUpgrader.class)
public abstract class RegionStorageUpgraderMixin {

    @Redirect(
        method = "getAllChunkPositions",
        at = @At(value = "INVOKE", target = "Ljava/io/File;listFiles(Ljava/io/FilenameFilter;)[Ljava/io/File;")
    )
    private static File[] linear$listAllRegionFiles(final File folder, final FilenameFilter vanilla) {
        return folder.listFiles((dir, name) -> LinearStorageProbe.REGION_NAME.matcher(name).matches());
    }

    @Redirect(
        method = "getAllChunkPositions",
        at = @At(value = "FIELD", target = "Lnet/minecraft/util/worldupdate/RegionStorageUpgrader;REGEX:Ljava/util/regex/Pattern;")
    )
    private static Pattern linear$allExtensions() {
        return LinearStorageProbe.REGION_NAME;
    }

    @Redirect(
        method = "getAllChunkPositions",
        at = @At(value = "NEW", target = "net/minecraft/world/level/chunk/storage/RegionFile")
    )
    private static RegionFile linear$openForProbe(final RegionStorageInfo info, final Path path,
        final Path folder, final boolean sync) throws IOException {
        return HorizonSeams.openRegion(info, path, folder, sync);
    }
}
