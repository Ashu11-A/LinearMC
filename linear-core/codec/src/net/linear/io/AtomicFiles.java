package net.linear.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Small-file atomic replace: temp file, {@code force(true)}, one
 * {@code ATOMIC_MOVE} over the target, then a directory fsync.
 *
 * <p>At every instant the target holds either the old or the new contents,
 * whole. Used for the save-all completion marker and, behind
 * {@code durable-player-data}, for player stats and advancements (vanilla
 * rewrites those in place, so a crash mid-write leaves a torn JSON file).
 * Writes from a tick thread skip the two fsyncs
 * ({@link #write(Path, byte[], boolean, boolean)}); off-thread writers
 * (the save-all marker, the coupled-save thread) keep them.</p>
 *
 * <p>Cold path: a few calls per player save or per save-all, never per
 * chunk.</p>
 */
public final class AtomicFiles {

    /** Suffix of the temp file written beside the target before the move. */
    public static final String TMP_SUFFIX = ".linear-tmp";

    private AtomicFiles() {
    }

    /**
     * Same-target writers serialize on one of these stripes (index by path
     * hash): the temp name is fixed per target, so two writers of one target
     * (two save-all barrier threads writing the marker, a direct stats write
     * racing another) must never interleave their temp writes. A fixed set,
     * so no per-call allocation.
     */
    private static final Object[] STRIPES = new Object[64];

    static {
        for (int i = 0; i < STRIPES.length; i++) {
            STRIPES[i] = new Object();
        }
    }

    private static Object stripe(final Path absolute) {
        return STRIPES[(absolute.hashCode() & 0x7fffffff) % STRIPES.length];
    }

    /**
     * Replaces {@code target} with {@code data}: forced temp, atomic move,
     * directory fsync. The temp file has a fixed name beside the target, so
     * a temp left by a crash is overwritten by the next write instead of
     * piling up; writers of the same target are serialized.
     */
    public static void write(final Path target, final byte[] data) throws IOException {
        write(target, data, true, true);
    }

    /**
     * Atomic replace with explicit sync steps. {@code forceData}: force the
     * temp file before the move; {@code syncDir}: fsync the directory after
     * it (callers that write several files in one directory sync it once).
     * Without both, the replace is still atomic (old or new contents, whole)
     * but becomes durable on the filesystem's own commit schedule.
     */
    public static void write(final Path target, final byte[] data, final boolean forceData, final boolean syncDir)
        throws IOException {
        final Path absolute = target.toAbsolutePath().normalize();
        final Path dir = absolute.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        final Path tmp = absolute.resolveSibling(absolute.getFileName() + TMP_SUFFIX);
        synchronized (stripe(absolute)) {
            try {
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    final ByteBuffer buffer = ByteBuffer.wrap(data);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    if (forceData) {
                        channel.force(true);
                    }
                }
                Files.move(tmp, absolute, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // The original failure is the one worth reporting.
                }
                throw e;
            }
        }
        if (syncDir) {
            DirectoryFsync.syncQuietly(dir);
        }
    }

    /**
     * A writer that buffers in memory and replaces {@code target} atomically
     * on {@link Writer#close()}. Nothing touches the target before close, and
     * a writer closed with nothing written (the producer failed before its
     * first write) leaves the old file alone instead of truncating it.
     */
    public static BufferedWriter newBufferedWriter(final Path target, final Charset charset) {
        return new BufferedWriter(new CommitOnClose(target, charset, AtomicFiles::write));
    }

    /** What a commit-on-close writer does with its bytes at close. */
    @FunctionalInterface
    public interface Committer {
        void commit(Path target, byte[] data) throws IOException;
    }

    /**
     * Same as {@link #newBufferedWriter(Path, Charset)}, but {@code committer}
     * receives the bytes at close (coupled save stages them, or orders the
     * atomic write against staged saves).
     */
    public static BufferedWriter newBufferedWriter(final Path target, final Charset charset, final Committer committer) {
        return new BufferedWriter(new CommitOnClose(target, charset, committer));
    }

    private static final class CommitOnClose extends Writer {
        private final Path target;
        private final Charset charset;
        private final Committer committer;
        private final StringBuilder content = new StringBuilder(1024);
        private boolean closed;

        CommitOnClose(final Path target, final Charset charset, final Committer committer) {
            this.target = target;
            this.charset = charset;
            this.committer = committer;
        }

        @Override
        public void write(final char[] buffer, final int offset, final int length) throws IOException {
            if (this.closed) {
                throw new IOException("writer closed");
            }
            this.content.append(buffer, offset, length);
        }

        @Override
        public void flush() {
            // Nothing reaches the disk before close: that is the point.
        }

        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            this.closed = true;
            if (this.content.length() == 0) {
                return;
            }
            this.committer.commit(this.target, this.content.toString().getBytes(this.charset));
        }
    }
}
