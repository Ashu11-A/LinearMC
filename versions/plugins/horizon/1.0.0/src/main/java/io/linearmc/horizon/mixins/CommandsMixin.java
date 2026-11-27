package io.linearmc.horizon.mixins;

import com.mojang.brigadier.CommandDispatcher;
import io.linearmc.horizon.HorizonRegionSaveAll;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.commands.SaveAllCommand;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers {@code save-all} on a region host that left it out (Folia keeps
 * it commented out as a region-threading TODO; fork parity: Folia
 * {@code minecraft-0019} registers it again). Paper and CanvasMC already
 * register it, so this is a no-op there (the node exists). The command body
 * is the region-scheduled barrier of {@code SaveAllCommandMixin}. The
 * constructor is the Paper Brigadier overload every host has; host-optional
 * all the same ({@code require = 0}, see MIXIN-CONTRACT.md).
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Shadow
    @Final
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(
        method = "<init>(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;Z)V",
        at = @At("RETURN"),
        require = 0
    )
    private void linear$registerSaveAllOnRegionHosts(final Commands.CommandSelection selection,
                                                     final CommandBuildContext context, final boolean modern,
                                                     final CallbackInfo ci) {
        if (selection != Commands.CommandSelection.INTEGRATED && HorizonRegionSaveAll.applies()
            && this.dispatcher.getRoot().getChild("save-all") == null) {
            SaveAllCommand.register(this.dispatcher);
        }
    }
}
