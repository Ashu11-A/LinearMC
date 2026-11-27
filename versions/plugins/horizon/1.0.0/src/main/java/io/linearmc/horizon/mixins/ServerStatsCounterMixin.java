package io.linearmc.horizon.mixins;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import net.linear.io.PlayerFileDurability;
import net.minecraft.stats.ServerStatsCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code durable-player-data} for stats (fork parity: Folia
 * {@code minecraft-0019}, Canvas {@code ServerStatsCounter} overlay): the in-place writer of {@code save()}
 * becomes the core writer, which replaces the file atomically on close (the
 * vanilla writer with the flag off).
 */
@Mixin(ServerStatsCounter.class)
public abstract class ServerStatsCounterMixin {

    @Redirect(
        method = "save",
        at = @At(value = "INVOKE", target = "Ljava/nio/file/Files;newBufferedWriter(Ljava/nio/file/Path;Ljava/nio/charset/Charset;[Ljava/nio/file/OpenOption;)Ljava/io/BufferedWriter;")
    )
    private BufferedWriter linear$atomicWriter(final Path target, final Charset charset, final OpenOption[] options)
        throws IOException {
        return PlayerFileDurability.newWriter(target, charset);
    }

    /** Coupled save (fork parity: Folia {@code minecraft-0020}): a staged save is newer than the file. */
    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Ljava/nio/file/Files;isRegularFile(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z")
    )
    private boolean linear$stagedOrFileExists(final Path file, final LinkOption[] options) {
        return PlayerFileDurability.isReadable(file);
    }

    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Ljava/nio/file/Files;newBufferedReader(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/io/BufferedReader;")
    )
    private BufferedReader linear$stagedOrFileReader(final Path file, final Charset charset) throws IOException {
        return PlayerFileDurability.newReader(file, charset);
    }
}
