package io.linearmc.horizon.mixins;

import io.linearmc.horizon.HorizonSaveBarrier;
import io.linearmc.horizon.LinearShutdownBarrier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Final stop barrier. Plugins are disabled before the host's final world
 * save, so the drain runs right before {@code onServerExit()}: after
 * {@code saveAllChunks} and the last region I/O flush in Paper's
 * {@code stopServer} and in Folia/Canvas {@code stopPart2}. One site per host,
 * hence {@code require = 1}: a host that moves the call fails the boot loudly.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(
        method = {"stopServer", "stopPart2"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;onServerExit()V"),
        require = 1
    )
    private void linear$finalDrainBeforeExit(final CallbackInfo ci) {
        LinearShutdownBarrier.finalDrain("before onServerExit");
    }

    /**
     * Coupled save (fork parity: {@code CoupledSave.beginShutdown()} in the
     * forks' stop path): from the start of the stop, player saves are direct
     * (the stop saves every chunk and player itself); the final drain writes
     * whatever is still staged ({@code LinearShutdownBarrier.finalDrain}).
     * Paper's {@code stopServer} is Folia/Canvas stop part 1.
     */
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void linear$coupledSaveStops(final CallbackInfo ci) {
        net.linear.save.CoupledSave.beginShutdown();
    }

    /**
     * Save-off lease (fork parity: {@code MinecraftServer.setAutoSave} hunk):
     * every {@code save-off}/{@code save-on} (command or management API)
     * arms or disarms the core lease. Same method on Paper, Folia and Canvas.
     */
    @Inject(method = "setAutoSave(Z)Z", at = @At("RETURN"))
    private void linear$saveOffLease(final boolean enable, final CallbackInfoReturnable<Boolean> cir) {
        HorizonSaveBarrier.onAutoSaveChanged(enable, cir.getReturnValueZ());
    }

    /**
     * Periodic player autosave respects {@code save-off} (fork parity:
     * {@code && !region.world.noSave}). Every host calls
     * {@code PlayerList.saveAll(int)} from its {@code tickServer}.
     */
    @Redirect(
        method = "tickServer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;saveAll(I)V")
    )
    private void linear$periodicPlayerSaveRespectsSaveOff(final PlayerList players, final int interval) {
        HorizonSaveBarrier.periodicPlayerSave((MinecraftServer) (Object) this, players, interval);
    }
}
