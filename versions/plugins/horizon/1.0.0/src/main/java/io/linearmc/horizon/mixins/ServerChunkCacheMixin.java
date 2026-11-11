package io.linearmc.horizon.mixins;

import net.linear.flush.FlushReports;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Save drain, fork parity with Folia {@code minecraft-0017} / Canvas
 * {@code ServerChunkCache.java.patch}: after the chunk map saved, an explicit
 * save ({@code /save-all flush}, {@code flushStorage = true}) forces every
 * dirty Linear file to disk; periodic/autosave saves submit an async,
 * age-gated drain and never block the tick.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

    @Inject(method = "save(Z)V", at = @At("TAIL"))
    private void linear$drainAfterSave(final boolean flushStorage, final CallbackInfo ci) {
        try {
            FlushReports.FolderDrain.onSave(flushStorage);
        } catch (RuntimeException ignored) {
            // Durability never depends on this hook: the heartbeat and the
            // stop barrier still drain everything.
        }
    }
}
