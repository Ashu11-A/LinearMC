package net.linear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.linear.io.AtomicFiles;
import net.linear.io.DirectoryFsync;
import net.linear.io.PlayerFileDurability;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Player .dat replace, stats/advancements writers and boot quarantine. */
public class PlayerFileDurabilityTest {

    private static final String UUID = "85a1efec-0000-4000-8000-00000000abcd";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void reset() {
        PlayerFileDurability.setEnabled(true);
        DirectoryFsync.setSyncerForTests(null);
    }

    @Test
    public void tickThreadReplaceIsAtomicWithoutAnyFsync() throws Exception {
        // Player saves run on the tick thread: no directory fsync there.
        final Path dir = this.temporaryFolder.newFolder("players").toPath();
        final Path real = dir.resolve(UUID + ".dat");
        final Path old = dir.resolve(UUID + ".dat_old");
        Files.write(real, new byte[] {1});
        final Path tmp = Files.createTempFile(dir, UUID + "-", ".dat");
        Files.write(tmp, new byte[] {2});
        final List<String> syncs = new ArrayList<>();
        DirectoryFsync.setSyncerForTests(d -> syncs.add(d.toString()));

        PlayerFileDurability.replace(real, tmp, old);

        assertArrayEquals(new byte[] {2}, Files.readAllBytes(real));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(old));
        assertFalse(Files.exists(tmp));
        assertEquals("no fsync on the saving thread", List.of(), syncs);
    }

    @Test
    public void syncedReplaceKeepsThePreviousSaveAsDatOldAndSyncsTheDirectoryAfterTheMove() throws Exception {
        final Path dir = this.temporaryFolder.newFolder("players").toPath();
        final Path real = dir.resolve(UUID + ".dat");
        final Path old = dir.resolve(UUID + ".dat_old");
        Files.write(real, new byte[] {1});
        Files.write(old, new byte[] {0});
        final Path tmp = Files.createTempFile(dir, UUID + "-", ".dat");
        Files.write(tmp, new byte[] {2});
        final List<String> order = new ArrayList<>();
        DirectoryFsync.setSyncerForTests(d -> order.add(Files.exists(tmp) ? "sync-before-move" : "sync-after-move"));

        PlayerFileDurability.replaceUnlocked(real, tmp, old);

        assertArrayEquals(new byte[] {2}, Files.readAllBytes(real));
        assertArrayEquals("dat_old holds the save before this one", new byte[] {1}, Files.readAllBytes(old));
        assertFalse(Files.exists(tmp));
        assertEquals(List.of("sync-after-move"), order);
    }

    @Test
    public void concurrentWritersOfOneTargetNeverRenameAMixedTemp() throws Exception {
        // Two save-all barrier threads writing the marker, or two direct stats
        // writes: same fixed temp name, so the writers must be serialized.
        final Path file = this.temporaryFolder.newFolder("marker").toPath().resolve("save-all.done");
        final byte[] a = new byte[64 * 1024];
        final byte[] b = new byte[48 * 1024];
        java.util.Arrays.fill(a, (byte) 'a');
        java.util.Arrays.fill(b, (byte) 'b');
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        final Thread[] writers = new Thread[4];
        for (int t = 0; t < writers.length; t++) {
            final byte[] data = (t & 1) == 0 ? a : b;
            writers[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        AtomicFiles.write(file, data, false, false);
                    }
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            writers[t].start();
        }
        for (Thread writer : writers) {
            writer.join(30_000);
        }
        assertEquals(null, failure.get());
        final byte[] last = Files.readAllBytes(file);
        assertTrue("whole contents of one writer", java.util.Arrays.equals(last, a) || java.util.Arrays.equals(last, b));
    }

    @Test
    public void firstSaveHasNoBackup() throws Exception {
        final Path dir = this.temporaryFolder.newFolder("players").toPath();
        final Path tmp = Files.createTempFile(dir, UUID + "-", ".dat");
        Files.write(tmp, new byte[] {5});
        PlayerFileDurability.replace(dir.resolve(UUID + ".dat"), tmp, dir.resolve(UUID + ".dat_old"));
        assertArrayEquals(new byte[] {5}, Files.readAllBytes(dir.resolve(UUID + ".dat")));
        assertFalse(Files.exists(dir.resolve(UUID + ".dat_old")));
    }

    @Test
    public void statsWriterReplacesOnCloseOnly() throws Exception {
        final Path file = this.temporaryFolder.newFolder("stats").toPath().resolve(UUID + ".json");
        Files.writeString(file, "{\"old\":1}");
        try (BufferedWriter writer = PlayerFileDurability.newWriter(file, StandardCharsets.UTF_8)) {
            writer.write("{\"new\":2}");
            writer.flush();
            assertEquals("nothing reaches the target before close", "{\"old\":1}", Files.readString(file));
        }
        assertEquals("{\"new\":2}", Files.readString(file));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + AtomicFiles.TMP_SUFFIX)));
    }

    @Test
    public void writerClosedWithNothingWrittenKeepsTheOldFile() throws Exception {
        final Path file = this.temporaryFolder.newFolder("adv").toPath().resolve(UUID + ".json");
        Files.writeString(file, "{\"old\":1}");
        try (BufferedWriter ignored = PlayerFileDurability.newWriter(file, StandardCharsets.UTF_8)) {
            // the producer threw before writing
        }
        assertEquals("{\"old\":1}", Files.readString(file));
    }

    @Test
    public void disabledFlagUsesTheVanillaInPlaceWriter() throws Exception {
        PlayerFileDurability.setEnabled(false);
        final Path file = this.temporaryFolder.newFolder("stats").toPath().resolve(UUID + ".json");
        Files.writeString(file, "{\"old\":1}");
        try (BufferedWriter writer = PlayerFileDurability.newWriter(file, StandardCharsets.UTF_8)) {
            writer.write("x");
            writer.flush();
            assertEquals("in-place writer truncates at open", "x", Files.readString(file));
        }
    }

    @Test
    public void bootQuarantineMovesOrphanTempFilesAndNeverDeletes() throws Exception {
        final Path dir = this.temporaryFolder.newFolder("players").toPath();
        Files.write(dir.resolve(UUID + ".dat"), new byte[] {1});
        Files.write(dir.resolve(UUID + ".dat_old"), new byte[] {0});
        Files.write(dir.resolve(UUID + "-123456789.dat"), new byte[] {9});
        Files.write(dir.resolve("notes.dat"), new byte[] {3});

        assertEquals(1, PlayerFileDurability.quarantineOrphans(dir));

        final Path moved = dir.resolve(PlayerFileDurability.QUARANTINE_DIR).resolve(UUID + "-123456789.dat");
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(moved));
        assertTrue(Files.exists(dir.resolve(UUID + ".dat")));
        assertTrue(Files.exists(dir.resolve(UUID + ".dat_old")));
        assertTrue("unrelated files stay", Files.exists(dir.resolve("notes.dat")));
        assertFalse(Files.exists(dir.resolve(UUID + "-123456789.dat")));
        assertEquals("second boot finds nothing", 0, PlayerFileDurability.quarantineOrphans(dir));
    }

    @Test
    public void quarantineIsOffWithTheFlag() throws IOException {
        PlayerFileDurability.setEnabled(false);
        final Path dir = this.temporaryFolder.newFolder("players").toPath();
        Files.write(dir.resolve(UUID + "-1.dat"), new byte[] {9});
        assertEquals(0, PlayerFileDurability.quarantineOrphans(dir));
        assertTrue(Files.exists(dir.resolve(UUID + "-1.dat")));
    }
}
