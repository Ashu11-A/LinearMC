package io.linearmc.horizon.mixins;

import io.linearmc.horizon.HorizonCoupledSave;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Coupled save for plugin {@code Player#saveData} (fork parity: Folia
 * {@code paper-0013}, Canvas {@code CraftPlayer} overlay): on the player's
 * own thread with coupled save active, the save runs as a capture of the
 * player's region; otherwise the host method runs unchanged.
 */
@Mixin(CraftPlayer.class)
public abstract class CraftPlayerMixin {

    @Inject(method = "saveData()V", at = @At("HEAD"), cancellable = true)
    private void linear$coupledSaveData(final CallbackInfo ci) {
        final MinecraftServer server = MinecraftServer.getServer();
        final ServerPlayer handle = ((CraftPlayer) (Object) this).getHandle();
        if (server != null && HorizonCoupledSave.shouldCapture(server, handle)) {
            HorizonCoupledSave.capture(server, () -> server.getPlayerList().playerIo.save(handle), true);
            ci.cancel();
        }
    }
}
