package net.linear;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

/**
 * Minimal zero-copy bridges between direct {@link ByteBuffer}s and blocking streams.
 *
 * <p>Used by {@link LinearRegionFile} so the region hot paths (whole-region zstd
 * blob assembly, per-chunk LZ4 staging, file IO) never allocate per-chunk heap
 * byte arrays: file data lives in direct buffers and is pumped through
 * zstd-jni streaming ({@code ZstdInputStream}/{@code ZstdOutputStream}) and
 * {@link FileChannel} without intermediate copies.
 */
final class LinearDirectStreams {

    private LinearDirectStreams() {
    }

    /**
     * Returns an {@link InputStream} view over the remaining bytes of {@code buf}.
     * Reads advance {@code buf}'s position; no bytes are copied. The stream is
     * confined to the calling thread and is not thread-safe.
     */
    static InputStream wrap(final ByteBuffer buf) {
        Objects.requireNonNull(buf, "buf");
        return new ByteBufferInputStream(buf);
    }

    /**
     * Wraps {@code out} so {@link OutputStream#close()} only flushes and never
     * closes the underlying stream. Needed when a {@code ZstdOutputStream} (whose
     * {@code close()} finalises the frame <em>and</em> closes its sink) targets a
     * {@link FileChannel} that must stay open for footer/patch writes afterwards.
     */
    static OutputStream uncloseable(final OutputStream out) {
        Objects.requireNonNull(out, "out");
        return new java.io.FilterOutputStream(out) {
            @Override
            public void close() throws IOException {
                this.out.flush();
            }
        };
    }

    /** Reads until {@code dst} has no remaining space; throws on early EOF. */
    static void readFully(final FileChannel channel, final ByteBuffer dst) throws IOException {
        while (dst.hasRemaining()) {
            final int n = channel.read(dst);
            if (n < 0) {
                throw new java.io.EOFException("unexpected EOF reading " + dst.remaining() + " remaining bytes");
            }
        }
    }

    /** Writes until {@code src} has no remaining bytes. */
    static void writeFully(final FileChannel channel, final ByteBuffer src) throws IOException {
        while (src.hasRemaining()) {
            channel.write(src);
        }
    }

    private static final class ByteBufferInputStream extends InputStream {

        private final ByteBuffer buf;

        ByteBufferInputStream(final ByteBuffer buf) {
            this.buf = buf;
        }

        @Override
        public int read() {
            return this.buf.hasRemaining() ? this.buf.get() & 0xFF : -1;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            if (!this.buf.hasRemaining()) {
                return -1;
            }
            final int n = Math.min(len, this.buf.remaining());
            this.buf.get(b, off, n);
            return n;
        }

        @Override
        public int available() {
            return this.buf.remaining();
        }

        @Override
        public long skip(final long n) {
            if (n <= 0L) {
                return 0L;
            }
            final int k = (int) Math.min(n, this.buf.remaining());
            if (k > 0) {
                this.buf.position(this.buf.position() + k);
            }
            return k;
        }
    }
}
