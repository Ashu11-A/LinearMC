package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;

/** Zero-copy stream bridges (no per-chunk heap arrays). */
public class CoreStreamsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void wrapReadsWithoutCopying() throws Exception {
        ByteBuffer buf = ByteBuffer.allocateDirect(8);
        buf.put(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        buf.flip();
        try (InputStream in = LinearDirectStreams.wrap(buf)) {
            assertEquals(8, in.available());
            assertEquals(1, in.read());
            assertEquals(1, in.skip(1));
            byte[] rest = new byte[6];
            assertEquals(6, in.read(rest, 0, 6));
            assertArrayEquals(new byte[] {3, 4, 5, 6, 7, 8}, rest);
            assertEquals(-1, in.read());
        }
        assertEquals(8, buf.position());
    }

    @Test
    public void uncloseableCloseOnlyFlushes() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        boolean[] closed = {false};
        OutputStream tracked = new java.io.FilterOutputStream(sink) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        OutputStream guarded = LinearDirectStreams.uncloseable(tracked);
        guarded.write(42);
        guarded.close();
        assertEquals(42, sink.toByteArray()[0]);
        assertTrue("underlying stream must stay open", !closed[0]);
    }

    @Test
    public void channelRoundTrip() throws Exception {
        Path file = tmp.newFile("ch.bin").toPath();
        ByteBuffer src = ByteBuffer.allocateDirect(4);
        src.put(new byte[] {9, 8, 7, 6});
        src.flip();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            LinearDirectStreams.writeFully(ch, src);
        }
        assertTrue(!src.hasRemaining());
        ByteBuffer dst = ByteBuffer.allocateDirect(4);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            LinearDirectStreams.readFully(ch, dst);
        }
        dst.flip();
        assertArrayEquals(new byte[] {9, 8, 7, 6}, new byte[] {dst.get(), dst.get(), dst.get(), dst.get()});
    }

    @Test
    public void shortReadThrowsEof() throws Exception {
        Path file = tmp.newFile("short.bin").toPath();
        Files.write(file, new byte[] {1, 2});
        ByteBuffer dst = ByteBuffer.allocateDirect(4);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            try {
                LinearDirectStreams.readFully(ch, dst);
                fail("expected EOFException");
            } catch (EOFException expected) {
                assertTrue(expected.getMessage().contains("remaining"));
            }
        }
    }
}
