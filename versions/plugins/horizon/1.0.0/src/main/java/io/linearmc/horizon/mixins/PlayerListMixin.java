package io.linearmc.horizon.mixins;

import io.linearmc.horizon.HorizonCoupledSave;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Coupled save on logout (fork parity: Folia {@code minecraft-0020}, Canvas
 * {@code PlayerList} overlay): the quit save runs as a coupled capture of
 * the player's region, so the player file is written only after that
 * region's chunks are durable. Same call site on Paper, Folia and Canvas.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Shadow
    protected abstract void save(ServerPlayer player);

    @Redirect(
        method = "remove(Lnet/minecraft/server/level/ServerPlayer;Lnet/kyori/adventure/text/Component;)Lnet/kyori/adventure/text/Component;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;save(Lnet/minecraft/server/level/ServerPlayer;)V")
    )
    private void linear$coupledLogoutSave(final PlayerList list, final ServerPlayer player) {
        HorizonCoupledSave.save(MinecraftServer.getServer(), player, () -> this.save(player));
    }
}
