package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Leg-neutral startup conversion: every storage folder, linear-family targets only. */
public class StartupConversionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void restoreOpener() {
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (format == null || format == RegionFileFormat.LINEAR) {
                return new LinearRegionFile(file, level);
            }
            throw new IOException("No RegionOpener registered for non-linear open: " + file);
        });
    }

    private Path dimensionWithMca(String... folders) throws IOException {
        Path dim = tmp.newFolder("world", "dimensions", "minecraft", "overworld").toPath();
        for (String f : folders) {
            Path folder = Files.createDirectories(dim.resolve(f));
            Files.write(folder.resolve("r.0.0.mca"), new byte[(int) LinearRegionConverter.MIN_ANVIL_SIZE]);
        }
        return dim;
    }

    private static void wireFakeMca() {
        LinearRegionConverter.linear$setRegionOpener((file, folder, sync, format, level) -> {
            if (file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                return new ConverterMirrorTest.FakeMcaFile(Map.of(ChunkKey.of(1, 2), new byte[] {1, 2, 3, 4}));
            }
            return new LinearRegionFile(file, level);
        });
    }

    @Test
    public void detectsAnvilRegionsOnly() throws Exception {
        Path folder = tmp.newFolder("region").toPath();
        assertFalse(StartupConversion.needsConversion(folder));
        Files.write(folder.resolve("r.0.0.linear"), new byte[] {1});
        Files.write(folder.resolve("notes.mca.bak"), new byte[] {1});
        assertFalse(StartupConversion.needsConversion(folder));
        Files.write(folder.resolve("r.-3.7.mca"), new byte[] {1});
        assertTrue(StartupConversion.needsConversion(folder));
    }

    @Test
    public void anvilTargetNeverConverts() throws Exception {
        Path dim = dimensionWithMca("region");
        assertEquals(0, StartupConversion.convertDimension(dim,
            new StartupConversion.Target(RegionFileFormat.ANVIL, 6)));
        assertTrue(Files.isRegularFile(dim.resolve("region/r.0.0.mca")));
        assertEquals(0, StartupConversion.convertDimension(dim, null));
    }

    @Test
    public void linearTargetConvertsEveryStorageFolder() throws Exception {
        Path dim = dimensionWithMca("region", "entities", "poi");
        wireFakeMca();
        assertEquals(3, StartupConversion.convertDimension(dim,
            new StartupConversion.Target(RegionFileFormat.LINEAR, 6)));
        for (String f : List.of("region", "entities", "poi")) {
            assertFalse(f + " .mca left behind", Files.exists(dim.resolve(f + "/r.0.0.mca")));
            assertTrue(f + " .linear missing", Files.isRegularFile(dim.resolve(f + "/r.0.0.linear")));
        }
    }
}
