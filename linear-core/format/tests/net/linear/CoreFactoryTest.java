package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Factory dispatch contract: defaults fail loud, legs inject the seams,
 * Linear clamps, Anvil passes folder/sync through, 3-arg derives parent.
 */
public class CoreFactoryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void reset() {
        AbstractRegionFileFactory.linear$setAnvilOpener((info, file, externalDir, sync) -> {
            throw new IOException("no AnvilOpener registered");
        });
        AbstractRegionFileFactory.linear$setLinearOpener((file, level) -> {
            throw new IOException("no LinearOpener registered");
        });
    }

    private static AbstractRegionFile stub() {
        return new AbstractRegionFile() {
            @Override
            public DataInputStream getChunkDataInputStream(long chunk) {
                return null;
            }

            @Override
            public void write(long chunk, ByteBuffer data) {
            }

            @Override
            public boolean hasChunk(long chunk) {
                return false;
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isMarkedToSave() {
                return false;
            }

            @Override
            public void clearMarkedToSave() {
            }
        };
    }

    @Test
    public void defaultsThrow() {
        reset();
        Path linear = tmp.getRoot().toPath().resolve("r.0.0.linear");
        try {
            AbstractRegionFileFactory.get(linear, new Object(), linear.getParent(), false, 6);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no LinearOpener registered"));
        }
        Path mca = tmp.getRoot().toPath().resolve("r.0.0.mca");
        try {
            AbstractRegionFileFactory.get(mca, new Object(), mca.getParent(), false, 6);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no AnvilOpener registered"));
        }
    }

    @Test
    public void linearRoutesWithClampedLevel() throws Exception {
        AbstractRegionFile stub = stub();
        AtomicReference<Path> seenFile = new AtomicReference<>();
        AtomicInteger seenLevel = new AtomicInteger(-1);
        AbstractRegionFileFactory.linear$setLinearOpener((file, level) -> {
            seenFile.set(file);
            seenLevel.set(level);
            return stub;
        });
        AbstractRegionFileFactory.linear$setAnvilOpener((info, file, externalDir, sync) -> {
            throw new AssertionError("anvil must not be called for .linear");
        });
        Path file = tmp.getRoot().toPath().resolve("r.0.0.linear");
        AbstractRegionFile got =
            AbstractRegionFileFactory.get(file, new Object(), file.getParent(), true, 99);
        assertSame(stub, got);
        assertEquals(file, seenFile.get());
        assertEquals(6, seenLevel.get());
    }

    @Test
    public void anvilRoutesWithFolderSyncPassthrough() throws Exception {
        AbstractRegionFile stub = stub();
        AtomicReference<Object> seenInfo = new AtomicReference<>();
        AtomicReference<Path> seenFile = new AtomicReference<>();
        AtomicReference<Path> seenDir = new AtomicReference<>();
        AtomicReference<Boolean> seenSync = new AtomicReference<>();
        Object info = new Object();
        Path file = tmp.getRoot().toPath().resolve("r.0.0.mca");
        Path folder = tmp.getRoot().toPath();
        AbstractRegionFileFactory.linear$setAnvilOpener((i, f, dir, sync) -> {
            seenInfo.set(i);
            seenFile.set(f);
            seenDir.set(dir);
            seenSync.set(sync);
            return stub;
        });
        AbstractRegionFileFactory.linear$setLinearOpener((f, level) -> {
            throw new AssertionError("linear must not be called for .mca");
        });
        AbstractRegionFile got = AbstractRegionFileFactory.get(file, info, folder, true, 6);
        assertSame(stub, got);
        assertSame(info, seenInfo.get());
        assertEquals(file, seenFile.get());
        assertEquals(folder, seenDir.get());
        assertEquals(Boolean.TRUE, seenSync.get());
    }

    @Test(expected = NullPointerException.class)
    public void setAnvilOpenerNullThrowsNpe() {
        AbstractRegionFileFactory.linear$setAnvilOpener(null);
    }

    @Test(expected = NullPointerException.class)
    public void setLinearOpenerNullThrowsNpe() {
        AbstractRegionFileFactory.linear$setLinearOpener(null);
    }

    @Test
    public void threeArgDerivesParentAndSyncFalse() throws Exception {
        AbstractRegionFile stub = stub();
        AtomicReference<Path> seenDir = new AtomicReference<>();
        AtomicReference<Boolean> seenSync = new AtomicReference<>();
        AbstractRegionFileFactory.linear$setAnvilOpener((info, file, dir, sync) -> {
            seenDir.set(dir);
            seenSync.set(sync);
            return stub;
        });
        AbstractRegionFileFactory.linear$setLinearOpener((file, level) -> stub);
        Path file = tmp.getRoot().toPath().resolve("r.0.0.mca");
        AbstractRegionFileFactory.get(file, new Object(), 6);
        assertEquals(file.toAbsolutePath().getParent(), seenDir.get());
        assertEquals(Boolean.FALSE, seenSync.get());
    }

    @Test
    public void threeArgLinearStillClamps() throws Exception {
        AbstractRegionFile stub = stub();
        AtomicInteger seenLevel = new AtomicInteger(-1);
        AbstractRegionFileFactory.linear$setLinearOpener((file, level) -> {
            seenLevel.set(level);
            return stub;
        });
        Path file = tmp.getRoot().toPath().resolve("r.0.0.linear");
        AbstractRegionFileFactory.get(file, new Object(), 99);
        assertEquals(6, seenLevel.get());
    }
}
