package io.linearmc.horizon.mixins;

import com.mojang.brigadier.Command;
import io.linearmc.horizon.HorizonRegionSaveAll;
import io.linearmc.horizon.HorizonSaveBarrier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.SaveAllCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code save-all flush} as a barrier with a completion marker (fork parity:
 * Folia {@code minecraft-0019}, Canvas {@code SaveAllCommand} overlay).
 * Two host shapes, both entered through {@code saveAll(CommandSourceStack, boolean)}:
 * <ul>
 *   <li>Region hosts (Folia, CanvasMC): the HEAD injector cancels the host body
 *   (Canvas's own per-region tickets, or Folia's unsafe {@code saveEverything})
 *   and runs the region-scheduled barrier ({@link HorizonRegionSaveAll}); Folia
 *   gets the command registered by {@code CommandsMixin}.</li>
 *   <li>Paper: {@code saveAll} calls {@code saveEverything}; the redirect runs
 *   it plus the barrier on the main thread.</li>
 * </ul>
 * Canvas replaces the redirected call, so the redirect is host-optional
 * ({@code require = 0}, see MIXIN-CONTRACT.md); {@code HorizonSaveBarrierTest}
 * proves both sites on every Paper dev bundle.
 */
@Mixin(SaveAllCommand.class)
public abstract class SaveAllCommandMixin {

    @Inject(
        method = "saveAll(Lnet/minecraft/commands/CommandSourceStack;Z)I",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void linear$regionScheduledSaveAll(final CommandSourceStack source, final boolean flush,
                                                      final CallbackInfoReturnable<Integer> cir) {
        if (!HorizonRegionSaveAll.applies()) {
            return; // Paper: the redirect below
        }
        source.sendSuccess(() -> Component.translatable("commands.save.saving"), false);
        HorizonRegionSaveAll.saveAll(source, source.getServer(), flush);
        cir.setReturnValue(Command.SINGLE_SUCCESS);
    }

    @Redirect(
        method = "saveAll",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;saveEverything(ZZZ)Z"),
        require = 0
    )
    private static boolean linear$saveEverythingThenBarrier(final MinecraftServer server, final boolean silent,
                                                            final boolean flush, final boolean force) {
        return HorizonSaveBarrier.saveEverything(server, silent, flush, force);
    }
}
