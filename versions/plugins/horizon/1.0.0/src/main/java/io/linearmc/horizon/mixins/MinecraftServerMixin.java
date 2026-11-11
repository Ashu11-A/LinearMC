package io.linearmc.horizon.mixins;

import io.linearmc.horizon.LinearShutdownBarrier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
}
