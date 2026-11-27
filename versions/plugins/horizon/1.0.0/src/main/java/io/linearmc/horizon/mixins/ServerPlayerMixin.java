package io.linearmc.horizon.mixins;

import io.linearmc.horizon.HorizonCoupledSave;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Coupled save departure (fork parity: Folia {@code minecraft-0020} and the
 * Canvas {@code ServerPlayer} overlay): before a player leaves a region
 * (cross-region teleport or portal: {@code preChangeDimension}; respawn), the
 * source region saves its chunks and stages the player while it still owns
 * both. Both targets exist only on region-threaded hosts (Folia, Canvas), so
 * the injectors are {@code require = 0} (see MIXIN-CONTRACT.md); on Paper one
 * capture covers every chunk and no departure hook is needed.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "preChangeDimension", at = @At("HEAD"), require = 0)
    private void linear$coupledDepartureOnTeleport(final CallbackInfo ci) {
        HorizonCoupledSave.departure((ServerPlayer) (Object) this);
    }

    @Inject(
        method = "respawn(Ljava/util/function/Consumer;Lorg/bukkit/event/player/PlayerRespawnEvent$RespawnReason;Z)V",
        at = @At("HEAD"),
        require = 0
    )
    private void linear$coupledDepartureOnRespawn(final CallbackInfo ci) {
        HorizonCoupledSave.departure((ServerPlayer) (Object) this);
    }
}
