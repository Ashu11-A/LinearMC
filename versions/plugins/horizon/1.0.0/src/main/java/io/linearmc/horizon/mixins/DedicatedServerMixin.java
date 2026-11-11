package io.linearmc.horizon.mixins;

import io.linearmc.horizon.LinearStartup;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Startup auto-conversion trigger at the same point as the Canvas fork
 * overlay ({@code DedicatedServer.java.patch}): in {@code initServer}, after
 * the global and world-defaults configuration initialize and right before
 * {@code SparksFly.enableEarlyIfRequested()}, i.e. before plugins load and
 * before any world storage opens. A protection trip halts the server and
 * makes {@code initServer} return {@code false} (boot aborts, no plugin code
 * runs), exactly like the forks.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {

    @Inject(
        method = "initServer",
        at = @At(value = "INVOKE", target = "Lio/papermc/paper/SparksFly;enableEarlyIfRequested()V"),
        cancellable = true
    )
    private void linear$startupConversion(final CallbackInfoReturnable<Boolean> cir) {
        if (!LinearStartup.runPrePluginConversion((DedicatedServer) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
