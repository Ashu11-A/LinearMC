package io.linearmc.horizon.mixins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import net.linear.io.PlayerFileDurability;
import net.linear.save.CoupledSave;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.TagValueOutput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code durable-player-data} for player {@code .dat} files (fork parity:
 * Folia {@code minecraft-0019}, Canvas {@code PlayerDataStorage} overlay):
 * the vanilla three-step replace becomes temp + {@code .dat_old} hard
 * link + one {@code ATOMIC_MOVE}, with no fsync on the tick thread (coupled-save
 * writes are forced and directory-synced); orphan temp files are
 * quarantined when the storage is built (boot); every {@code .dat_old}
 * fallback logs a WARN.
 *
 * <p>Coupled save (fork parity: Folia {@code minecraft-0020}, Canvas
 * {@code PlayerDataStorage} overlay): inside a capture the {@code .dat} is
 * serialized in memory and staged instead of written (the host body is
 * mirrored up to the serialization, then cancelled), and a load reads a
 * staged save before the file.</p>
 */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageMixin {

    @Shadow
    @Final
    private File playerDir;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void linear$quarantineOrphans(final CallbackInfo ci) {
        PlayerFileDurability.quarantineOrphans(this.playerDir.toPath());
    }

    @Inject(method = "save(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"), cancellable = true)
    private void linear$stageInCoupledCapture(final Player player, final CallbackInfo ci) {
        if (!CoupledSave.capturing()) {
            return;
        }
        ci.cancel();
        if (org.spigotmc.SpigotConfig.disablePlayerDataSaving) {
            return;
        }
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), org.slf4j.LoggerFactory.getLogger("LinearMC"))) {
            final TagValueOutput output = TagValueOutput.createWithContext(reporter, player.registryAccess());
            player.saveWithoutId(output);
            final ByteArrayOutputStream staged = new ByteArrayOutputStream(8192);
            NbtIo.writeCompressed(output.buildResult(), staged);
            final Path dir = this.playerDir.toPath();
            CoupledSave.stageDat(dir.resolve(player.getStringUUID() + ".dat"), dir.resolve(player.getStringUUID() + ".dat_old"),
                staged.toByteArray());
        } catch (final Exception e) {
            org.slf4j.LoggerFactory.getLogger("LinearMC").warn("Failed to save player data for {}", player.getPlainTextName(), e);
        }
    }

    @Inject(method = "load(Lnet/minecraft/server/players/NameAndId;Ljava/lang/String;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private void linear$readStagedSave(final NameAndId nameAndId, final String suffix,
                                       final CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        if (!".dat".equals(suffix)) {
            return;
        }
        final byte[] staged = CoupledSave.stagedBytes(new File(this.playerDir, nameAndId.id() + suffix).toPath());
        if (staged == null) {
            return;
        }
        try {
            cir.setReturnValue(Optional.of(NbtIo.readCompressed(new ByteArrayInputStream(staged), NbtAccounter.unlimitedHeap())));
        } catch (final IOException e) {
            org.slf4j.LoggerFactory.getLogger("LinearMC").warn("Failed to read the staged player data of {}, reading the file", nameAndId.name(), e);
        }
    }

    @Redirect(
        method = "save",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;safeReplaceFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V")
    )
    private void linear$durableReplace(final Path realFile, final Path tmpFile, final Path oldFile) throws IOException {
        if (PlayerFileDurability.enabled()) {
            PlayerFileDurability.replace(realFile, tmpFile, oldFile);
        } else {
            Util.safeReplaceFile(realFile, tmpFile, oldFile);
        }
    }

    @Inject(method = "load(Lnet/minecraft/server/players/NameAndId;Ljava/lang/String;)Ljava/util/Optional;", at = @At("RETURN"))
    private void linear$warnDatOldFallback(final NameAndId nameAndId, final String suffix,
                                           final CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        if (".dat_old".equals(suffix) && cir.getReturnValue().isPresent()) {
            PlayerFileDurability.warnDatOldFallback(nameAndId.name(), nameAndId.id());
        }
    }
}
