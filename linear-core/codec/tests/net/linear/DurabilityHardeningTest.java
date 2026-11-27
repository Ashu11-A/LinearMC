package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.linear.io.DirectoryFsync;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Durability hardening of the region file: directory fsync after the
 * rename, zero-length quarantine, and the conflict copy of a region that
 * fails to open.
 */
public class DurabilityHardeningTest {

    private static final long CHUNK = ChunkKey.of(0, 0);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void pin() {
        LinearFlushCoordinator.linear$setFlushFrequencyForTests(3600L);
        DirectoryFsync.resetWarningForTests();
    }

    @After
    public void reset() {
        DirectoryFsync.setSyncerForTests(null);
        DirectoryFsync.resetWarningForTests();
        LinearRegionFile.linear$setConflictsFolderResolver(null);
        LinearFlushCoordinator.evictAll();
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    private Path regionFolder(String dimension) throws IOException {
        return temporaryFolder.newFolder(dimension, "region").toPath();
    }

    private static long quarantines(Path folder) {
        return LinearFlushCoordinator.forFolder(folder).snapshot().quarantines();
    }

    private static long conflicts(Path folder) {
        return LinearFlushCoordinator.forFolder(folder).snapshot().conflicts();
    }

    private static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return new ArrayList<>(s.sorted().toList());
        }
    }

    @Test
    public void directoryIsSyncedAfterTheRename() throws Exception {
        Path folder = regionFolder("sync");
        Path file = folder.resolve("r.0.0.linear");
        Path tmp = folder.resolve("r.0.0.linear.tmp");
        List<String> seen = new ArrayList<>();
        DirectoryFsync.setSyncerForTests(dir -> {
            // At sync time the rename must be done: final file present and
            // readable, no staging tmp left.
            seen.add(dir.toAbsolutePath() + "|" + Files.isRegularFile(file) + "|" + Files.exists(tmp)
                + "|" + Files.size(file));
        });
        try (LinearRegionFile region = new LinearRegionFile(file, 3)) {
            region.write(CHUNK, ByteBuffer.wrap(new byte[] {1, 2, 3}));
            region.flush();
            assertEquals(1, seen.size());
            String[] parts = seen.get(0).split("\\|");
            assertEquals(folder.toAbsolutePath().toString(), parts[0]);
            assertEquals("final file exists at sync time", "true", parts[1]);
            assertEquals("tmp is gone at sync time", "false", parts[2]);
            assertEquals(Long.toString(Files.size(file)), parts[3]);

            region.write(CHUNK, ByteBuffer.wrap(new byte[] {4}));
            region.flush();
            assertEquals("one directory sync per flush", 2, seen.size());
        }
    }

    @Test
    public void refusedDirectorySyncNeverFailsTheFlush() throws Exception {
        // Fault between the rename and the directory fsync: the flush still
        // succeeds and the file is the new one, whole.
        Path folder = regionFolder("refuse");
        Path file = folder.resolve("r.0.0.linear");
        DirectoryFsync.setSyncerForTests(dir -> {
            throw new IOException("Is a directory: fsync not supported");
        });
        try (LinearRegionFile region = new LinearRegionFile(file, 3)) {
            region.write(CHUNK, ByteBuffer.wrap(new byte[] {9, 8, 7}));
            region.flush();
            assertTrue("one WARN logged", DirectoryFsync.warnedForTests());
            region.write(CHUNK, ByteBuffer.wrap(new byte[] {6, 5}));
            region.flush();
        }
        DirectoryFsync.setSyncerForTests(null);
        try (LinearRegionFile reopened = new LinearRegionFile(file, 3);
             DataInputStream in = reopened.getChunkDataInputStream(CHUNK)) {
            assertNotNull(in);
            assertArrayEquals(new byte[] {6, 5}, in.readAllBytes());
        }
    }

    @Test
    public void realDirectorySyncSucceedsOnThisFilesystem() throws Exception {
        Path folder = regionFolder("real");
        // Linux filesystems used in CI accept a directory fsync.
        assertTrue(DirectoryFsync.syncQuietly(folder));
        assertFalse(DirectoryFsync.syncQuietly(null));
    }

    @Test
    public void bufferedMasterRenameIsFollowedByDirectorySync() throws Exception {
        Path folder = regionFolder("buffered");
        Path master = folder.resolve("r.0.0.b_linear");
        List<Path> synced = new ArrayList<>();
        DirectoryFsync.setSyncerForTests(dir -> {
            if (Files.isRegularFile(master)) {
                synced.add(dir);
            }
        });
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(master, 3)) {
            region.write(CHUNK, ByteBuffer.wrap(new byte[] {1}));
            region.flush();
        }
        assertFalse(synced.isEmpty());
        assertEquals(folder.toAbsolutePath(), synced.get(0).toAbsolutePath());
    }

    @Test
    public void zeroLengthRegionIsQuarantinedNotOpenedAsEmpty() throws Exception {
        Path folder = regionFolder("zero");
        Path file = folder.resolve("r.0.0.linear");
        Files.createFile(file);
        try (LinearRegionFile region = new LinearRegionFile(file, 3)) {
            assertFalse("the empty file was moved aside", Files.exists(file));
            List<Path> corrupt = new ArrayList<>();
            for (Path p : list(folder)) {
                if (p.getFileName().toString().startsWith("r.0.0.linear.corrupt-")) {
                    corrupt.add(p);
                }
            }
            assertEquals(1, corrupt.size());
            assertEquals(0L, Files.size(corrupt.get(0)));
            assertEquals(1L, quarantines(folder));
            assertFalse(region.hasChunk(CHUNK));

            // The region keeps working: new data lands in a fresh file and
            // the quarantined one is never touched again.
            region.write(CHUNK, ByteBuffer.wrap(new byte[] {42}));
            region.flush();
            assertTrue(Files.size(file) > 0L);
            assertTrue(Files.exists(corrupt.get(0)));
        }
    }

    @Test
    public void zeroLengthBufferedMasterIsQuarantinedToo() throws Exception {
        Path folder = regionFolder("bzero");
        Path master = folder.resolve("r.0.0.b_linear");
        Files.createFile(master);
        try (BufferedLinearRegionFile region = new BufferedLinearRegionFile(master, 3)) {
            long corrupt = list(folder).stream()
                .filter(p -> p.getFileName().toString().startsWith("r.0.0.b_linear.corrupt-")).count();
            assertEquals("the empty master was moved aside", 1L, corrupt);
            assertEquals(1L, quarantines(folder));
            region.write(CHUNK, ByteBuffer.wrap(new byte[] {7}));
            region.flush();
        }
        assertTrue(Files.size(master) > 0L);
    }

    @Test
    public void unreadableBufferedMasterIsCopiedBeforeTheHealRewritesIt() throws Exception {
        Path folder = regionFolder("bbad");
        Path master = folder.resolve("r.0.0.b_linear");
        byte[] garbage = "not a b_linear master".getBytes(StandardCharsets.UTF_8);
        Files.write(master, garbage);
        try (BufferedLinearRegionFile ignored = new BufferedLinearRegionFile(master, 3)) {
            fail("no swap to serve: the open must fail, not start empty");
        } catch (IOException expected) {
            // the server sees the failure, as with LINEAR
        }
        assertArrayEquals("the master is untouched", garbage, Files.readAllBytes(master));
        Path conflicts = folder.getParent().resolve("linear-conflicts").resolve("region");
        assertTrue("conflict copy kept", Files.isDirectory(conflicts));
        List<Path> copies = list(conflicts);
        assertEquals(1, copies.size());
        assertArrayEquals(garbage, Files.readAllBytes(copies.get(0)));
        assertEquals(1L, conflicts(folder));
        assertEquals("a conflict copy is not a quarantine", 0L, quarantines(folder));
    }

    @Test
    public void nonEmptyRegionIsNotQuarantined() throws Exception {
        Path folder = regionFolder("ok");
        Path file = folder.resolve("r.0.0.linear");
        try (LinearRegionFile region = new LinearRegionFile(file, 3)) {
            region.write(CHUNK, ByteBuffer.wrap(new byte[] {1}));
            region.flush();
        }
        try (LinearRegionFile reopened = new LinearRegionFile(file, 3)) {
            assertTrue(reopened.hasChunk(CHUNK));
        }
        assertEquals(0L, quarantines(folder));
        assertEquals(1, list(folder).size());
    }

    @Test
    public void regionThatFailsToOpenIsCopiedToConflictsBeforeTheFailure() throws Exception {
        Path folder = regionFolder("broken");
        Path file = folder.resolve("r.0.0.linear");
        byte[] garbage = "this is not a linear region file, just some bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(file, garbage);
        try {
            new LinearRegionFile(file, 3).close();
            fail("a broken region must fail to open");
        } catch (IOException expected) {
            // The failure still reaches the caller.
        }
        assertArrayEquals("the original stays in place, untouched", garbage, Files.readAllBytes(file));
        Path conflicts = folder.getParent().resolve("linear-conflicts").resolve("region");
        List<Path> copies = list(conflicts);
        assertEquals(1, copies.size());
        assertTrue(copies.get(0).getFileName().toString().startsWith("r.0.0.linear.conflict-"));
        assertArrayEquals(garbage, Files.readAllBytes(copies.get(0)));
        assertEquals(1L, conflicts(folder));
        assertEquals("a conflict copy is not a quarantine", 0L, quarantines(folder));

        // A retried open of the same broken file makes no second copy.
        try {
            new LinearRegionFile(file, 3).close();
            fail("still broken");
        } catch (IOException expected) {
        }
        assertEquals(1, list(conflicts).size());
        assertEquals(1L, conflicts(folder));
        assertEquals("a conflict copy is not a quarantine", 0L, quarantines(folder));
    }

    @Test
    public void truncatedRegionIsCopiedToTheConfiguredFolder() throws Exception {
        Path folder = regionFolder("truncated");
        Path file = folder.resolve("r.0.0.linear");
        try (LinearRegionFile region = new LinearRegionFile(file, 3)) {
            region.write(CHUNK, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));
            region.flush();
        }
        byte[] whole = Files.readAllBytes(file);
        byte[] torn = java.util.Arrays.copyOf(whole, 20);
        Files.write(file, torn);
        Path configured = temporaryFolder.newFolder("ops-conflicts").toPath();
        LinearRegionFile.linear$setConflictsFolderResolver(region -> configured);
        try {
            new LinearRegionFile(file, 3).close();
            fail("a truncated region must fail to open");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("truncated"));
        }
        List<Path> copies = list(configured);
        assertEquals(1, copies.size());
        assertArrayEquals(torn, Files.readAllBytes(copies.get(0)));
        assertFalse("no tmp staging left behind",
            copies.get(0).getFileName().toString().endsWith(".tmp"));
    }

    @Test
    public void defaultConflictsFolderSitsBesideTheStorageFolder() {
        Path region = Path.of("/srv/world/dimensions/minecraft/overworld/entities/r.1.2.linear");
        assertEquals(Path.of("/srv/world/dimensions/minecraft/overworld/linear-conflicts/entities"),
            LinearRegionFile.defaultConflictsFolder(region));
        // Outside a storage folder the copies stay inside the file's own folder.
        assertEquals(Path.of("/tmp/staging/linear-conflicts"),
            LinearRegionFile.defaultConflictsFolder(Path.of("/tmp/staging/r.0.0.linear")));
    }

    @Test
    public void absentRegionOpensEmptyWithoutQuarantine() throws Exception {
        Path folder = regionFolder("absent");
        try (LinearRegionFile region = new LinearRegionFile(folder.resolve("r.5.5.linear"), 3)) {
            assertNull(region.getChunkDataInputStream(ChunkKey.of(5 * 32, 5 * 32)));
        }
        assertEquals(0L, quarantines(folder));
        assertTrue(list(folder).isEmpty());
    }
}
