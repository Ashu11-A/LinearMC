package net.linear.io;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import net.linear.save.CoupledSave;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Player data durability ({@code durable-player-data}, default on).
 *
 * <p>Vanilla replaces {@code <uuid>.dat} in three steps (move the current
 * file to {@code .dat_old}, delete, move the temp file in). Between the
 * first and the last step no {@code .dat} exists, and the renames are never
 * synced, so a host crash can leave the server reading {@code .dat_old}
 * (an older save) without saying so. Stats and advancements are rewritten in
 * place with no fsync, so a crash can leave a torn JSON file.</p>
 *
 * <p>With the flag on: {@code .dat_old} becomes a hard link to the current
 * file (same "previous save" meaning as vanilla), and the temp file replaces
 * {@code .dat} with one {@code ATOMIC_MOVE}, so {@code .dat} always exists
 * and is whole. Stats and advancements go through {@link AtomicFiles}. The
 * file bytes are the vanilla bytes; only the replace changes, which is why
 * this is format-independent and has its own flag instead of riding on the
 * region format.</p>
 *
 * <p>No fsync on the saving thread. Player saves run on the tick thread
 * (periodic autosave, logout, {@code save-all}); forcing the temp file and
 * the directory there would put several synchronous disk flushes per player
 * into a tick, which nobody has measured yet. Those replaces are atomic and
 * become durable on the filesystem's own commit (ext4: 5 s). The writes of
 * the coupled-save thread ({@code coupled-save.enabled}) run off the tick
 * and are fully synced: temp forced, then one directory fsync per directory
 * per batch ({@link #moveIntoPlace}).</p>
 *
 * <p>Cold path: one call per player save.</p>
 */
public final class PlayerFileDurability {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    /** Folder (inside the player data folder) that receives orphan temp files at boot. */
    public static final String QUARANTINE_DIR = "linear-quarantine";

    /**
     * Temp files of {@code PlayerDataStorage.save}:
     * {@code Files.createTempFile(dir, uuid + "-", ".dat")}.
     */
    private static final Pattern ORPHAN_TMP = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}-[0-9]+\\.dat");

    private static volatile boolean enabled = true;

    private PlayerFileDurability() {
    }

    /** Published by each leg's config load ({@code durable-player-data}). */
    public static void setEnabled(final boolean value) {
        enabled = value;
    }

    /**
     * Whether player files use the durable replace. {@code coupled-save}
     * implies it: its staged files are always written atomically, and the
     * direct writes it orders against them must be atomic too.
     */
    public static boolean enabled() {
        return enabled || CoupledSave.enabled();
    }

    /**
     * Replaces {@code real} with the finished temp file {@code tmp}, keeping
     * the previous contents as {@code backup}. Throws without touching
     * {@code real} when the backup cannot be made, like vanilla, which keeps
     * the old file when its backup rename fails. Called on the saving (tick)
     * thread: atomic, no fsync (see the class comment).
     */
    public static void replace(final Path real, final Path tmp, final Path backup) throws IOException {
        CoupledSave.writeDirect(real, () -> moveIntoPlace(real, tmp, backup, false, false));
    }

    /** {@link #replace} without the coupled-save ordering, fully synced (temp forced, directory fsync). */
    public static void replaceUnlocked(final Path real, final Path tmp, final Path backup) throws IOException {
        moveIntoPlace(real, tmp, backup, true, true);
    }

    /**
     * The replace itself: optional {@code force} of the temp file,
     * {@code backup} as a hard link (copy when links are refused), one
     * {@code ATOMIC_MOVE}, optional directory fsync. A caller that moves
     * several files into one directory passes {@code syncDir=false} and
     * syncs that directory once.
     */
    public static void moveIntoPlace(final Path real, final Path tmp, final Path backup, final boolean forceData,
                                     final boolean syncDir) throws IOException {
        if (forceData) {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }
        if (Files.exists(real, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(backup);
            try {
                Files.createLink(backup, real);
            } catch (IOException | UnsupportedOperationException noLinks) {
                Files.copy(real, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        Files.move(tmp, real, StandardCopyOption.ATOMIC_MOVE);
        if (syncDir) {
            DirectoryFsync.syncQuietly(real.toAbsolutePath().getParent());
        }
    }

    /**
     * Writer for a stats or advancements file: staged inside a coupled-save
     * capture, atomic replace (no fsync, tick thread) when the flag is on,
     * the vanilla in-place writer otherwise.
     */
    public static BufferedWriter newWriter(final Path target, final Charset charset) throws IOException {
        if (CoupledSave.capturing()) {
            // Coupled save: staged in memory, written after the region's chunks are durable.
            return AtomicFiles.newBufferedWriter(target, charset, CoupledSave::stageText);
        }
        if (enabled()) {
            return AtomicFiles.newBufferedWriter(target, charset,
                (path, data) -> CoupledSave.writeDirect(path, () -> AtomicFiles.write(path, data, false, false)));
        }
        return Files.newBufferedWriter(target, charset);
    }

    /**
     * Read side of a stats or advancements file: {@code true} when the file
     * exists or a coupled save of it is staged (a login right after a
     * logout must see that save, not the older file).
     */
    public static boolean isReadable(final Path file) {
        return CoupledSave.stagedBytes(file) != null || Files.isRegularFile(file);
    }

    /** Reader over the staged coupled save of {@code file} when there is one, else over the file. */
    public static BufferedReader newReader(final Path file, final Charset charset) throws IOException {
        final byte[] staged = CoupledSave.stagedBytes(file);
        if (staged != null) {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(staged), charset));
        }
        return Files.newBufferedReader(file, charset);
    }

    /** One WARN per {@code .dat_old} fallback (vanilla falls back silently). */
    public static void warnDatOldFallback(final String player, final Object uuid) {
        LOGGER.warn("Player data for {} ({}) was read from {}.dat_old: {}.dat was missing or unreadable, so this"
            + " player resumes from the save before the last one", player, uuid, uuid, uuid);
    }

    /**
     * Boot-time cleanup: moves temp files left by an interrupted player save
     * ({@code <uuid>-<digits>.dat}) into {@value #QUARANTINE_DIR}. Never
     * deletes anything. When {@code <uuid>.dat} is missing, the quarantined
     * file may be that player's newest save, so each move is logged.
     *
     * @return how many files were moved (0 when the flag is off)
     */
    public static int quarantineOrphans(final Path playerDir) {
        if (!enabled || playerDir == null || !Files.isDirectory(playerDir)) {
            return 0;
        }
        int moved = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(playerDir)) {
            for (final Path file : files) {
                final String name = file.getFileName().toString();
                if (!ORPHAN_TMP.matcher(name).matches() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                final Path quarantine = playerDir.resolve(QUARANTINE_DIR);
                Files.createDirectories(quarantine);
                Path destination = quarantine.resolve(name);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    destination = quarantine.resolve(name + "." + System.currentTimeMillis());
                }
                Files.move(file, destination);
                moved++;
                final String uuid = name.substring(0, 36);
                final boolean currentMissing = !Files.exists(playerDir.resolve(uuid + ".dat"));
                LOGGER.warn("Quarantined orphan player data temp file {} to {}{}", name, destination,
                    currentMissing ? " (" + uuid + ".dat is missing: this file may hold that player's newest save)" : "");
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Player data orphan scan of {} stopped early: {}", playerDir, e.toString());
        }
        return moved;
    }
}
