package net.linear.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Deterministic lifetime for direct buffers on the chunk I/O hot paths.
 *
 * <p>Direct memory is capped independently of the heap
 * ({@code -XX:MaxDirectMemorySize}, defaulting to {@code -Xmx}), and its
 * reclamation otherwise rides on GC-triggered {@code Cleaner} runs. Under
 * sustained generation load (~500 chunk I/O/s) allocation outruns that
 * side-channel until every chunk read and write throws
 * {@code OutOfMemoryError: Cannot reserve direct buffer memory}. Every
 * buffer this class hands out is therefore freed at a deterministic point
 * (stream close, end of load, region close) instead of relying on GC.
 *
 * <p>Release uses {@code Unsafe.invokeCleaner} ({@code jdk.unsupported},
 * no JVM flags needed). It is strictly best-effort: any failure falls back
 * to GC reclamation, so a missed free degrades to the old behaviour rather
 * than an error. Freeing is idempotent and safe on heap buffers and nulls.
 */
public final class LinearDirect {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(LinearDirect.class);

    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static final LongAdder ALLOCATED_BYTES = new LongAdder();
    private static final LongAdder FREED_BYTES = new LongAdder();

    private LinearDirect() {
    }

    private static sun.misc.Unsafe loadUnsafe() {
        try {
            final java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (sun.misc.Unsafe) field.get(null);
        } catch (final ReflectiveOperationException | RuntimeException failed) {
            // Silent fallback would revert to pre-fix OOM behaviour with no
            // signal: log once so a missing bridge is visible in the boot log.
            LOGGER.warn("LinearDirect: Unsafe.invokeCleaner unavailable ({}); "
                + "direct buffers fall back to GC reclamation", failed);
            return null;
        }
    }

    /** Records a deliberate direct allocation (pairs with {@link #free}). */
    public static void recordAlloc(final long bytes) {
        if (bytes > 0L) {
            ALLOCATED_BYTES.add(bytes);
        }
    }

    /**
     * Frees a direct buffer deterministically. Safe to call on {@code null},
     * heap buffers, and already-freed buffers; never throws (GC remains the
     * fallback).
     *
     * <p>Must be the <em>root</em> allocation: slices, duplicates and
     * read-only views are rejected by the Cleaner API and no-op here. Slot
     * tables therefore retain roots alongside their shared views.
     */
    public static void free(final ByteBuffer buf) {
        if (buf == null || !buf.isDirect() || UNSAFE == null) {
            return;
        }
        try {
            final int capacity = buf.capacity();
            UNSAFE.invokeCleaner(buf);
            FREED_BYTES.add(capacity);
        } catch (final RuntimeException ignored) {
            // GC Cleaner remains the fallback; pre-fix behaviour, no worse.
        }
    }

    /**
     * Ownership-transferring view over a caller-fresh direct buffer: the first
     * {@link InputStream#close()} frees the buffer deterministically. Reads
     * after close fail instead of touching freed memory. The stream is
     * confined to the calling thread (same contract as
     * {@link LinearDirectStreams#wrap}).
     */
    public static InputStream wrapOwned(final ByteBuffer buf) {
        Objects.requireNonNull(buf, "buf");
        if (!buf.isDirect()) {
            throw new IllegalArgumentException("wrapOwned requires a direct buffer");
        }
        return new OwnedBufferInputStream(buf);
    }

    /** Deliberate direct bytes allocated since process start (diagnostics). */
    public static long allocatedBytes() {
        return ALLOCATED_BYTES.sum();
    }

    /** Direct bytes deterministically freed since process start (diagnostics). */
    public static long freedBytes() {
        return FREED_BYTES.sum();
    }

    private static final class OwnedBufferInputStream extends InputStream {

        private final ByteBuffer buf;
        private boolean closed;

        OwnedBufferInputStream(final ByteBuffer buf) {
            this.buf = buf;
        }

        @Override
        public int read() throws IOException {
            if (this.closed) {
                throw new IOException("Stream closed");
            }
            return this.buf.hasRemaining() ? this.buf.get() & 0xFF : -1;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (this.closed) {
                throw new IOException("Stream closed");
            }
            if (!this.buf.hasRemaining()) {
                return -1;
            }
            final int n = Math.min(len, this.buf.remaining());
            this.buf.get(b, off, n);
            return n;
        }

        @Override
        public int available() throws IOException {
            if (this.closed) {
                throw new IOException("Stream closed");
            }
            return this.buf.remaining();
        }

        @Override
        public long skip(final long n) throws IOException {
            if (this.closed) {
                throw new IOException("Stream closed");
            }
            if (n <= 0L) {
                return 0L;
            }
            final int k = (int) Math.min(n, this.buf.remaining());
            if (k > 0) {
                this.buf.position(this.buf.position() + k);
            }
            return k;
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                LinearDirect.free(this.buf);
            }
        }
    }
}
