package net.linear.io;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Directory fsync after an atomic rename.
 *
 * <p>{@code tmp + force + ATOMIC_MOVE} makes the new file contents durable,
 * but the rename itself is a directory entry change: until the parent
 * directory is synced, a host crash can bring back the old name (ext4 commits
 * it on its own journal timer, 5 s by default). Syncing the directory closes
 * that window, so a flush that returned is on disk.</p>
 *
 * <p>Some filesystems and platforms (Windows, some network and FUSE mounts)
 * refuse to open or force a directory. That is not a flush failure: the file
 * contents are already forced and the rename already happened. The first
 * refusal logs one WARN and later ones stay quiet; the flush never fails
 * because of it.</p>
 *
 * <p>Cold path: one call per region flush (never per chunk).</p>
 */
public final class DirectoryFsync {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectoryFsync.class);

    /** Test seam: the operation that forces one directory. */
    @FunctionalInterface
    public interface DirSyncer {
        void sync(Path directory) throws IOException;
    }

    private static final DirSyncer DEFAULT = DirectoryFsync::forceDirectory;
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);
    private static volatile DirSyncer syncer = DEFAULT;

    private DirectoryFsync() {
    }

    /**
     * Forces {@code directory} to disk. Never throws: a refusal logs a single
     * WARN for the process lifetime and returns.
     *
     * @return true when the directory sync succeeded
     */
    public static boolean syncQuietly(final Path directory) {
        if (directory == null) {
            return false;
        }
        try {
            syncer.sync(directory);
            return true;
        } catch (IOException | RuntimeException refused) {
            if (WARNED.compareAndSet(false, true)) {
                LOGGER.warn("Directory fsync is not supported for {} ({}); renames still happen but become durable"
                    + " on the filesystem's own schedule. Logged once.", directory, refused.toString());
            }
            return false;
        }
    }

    private static void forceDirectory(final Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /** Test-only: replaces the sync operation ({@code null} restores the default). */
    public static void setSyncerForTests(final DirSyncer replacement) {
        syncer = replacement == null ? DEFAULT : Objects.requireNonNull(replacement);
    }

    /** Test-only: re-arms the one-time WARN. */
    public static void resetWarningForTests() {
        WARNED.set(false);
    }

    /** True once the one-time WARN was logged. */
    public static boolean warnedForTests() {
        return WARNED.get();
    }
}
