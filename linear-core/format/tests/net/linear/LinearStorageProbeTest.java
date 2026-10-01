package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Probe contract for the Phase D Canvas overlay / Horizon mixin. */
public class LinearStorageProbeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void fileNameForBothFormats() {
        assertEquals("r.1.2.mca", LinearStorageProbe.fileNameFor(1, 2, RegionFileFormat.ANVIL));
        assertEquals("r.1.2.linear", LinearStorageProbe.fileNameFor(1, 2, RegionFileFormat.LINEAR));
        assertEquals("r.-3.4.mca", LinearStorageProbe.fileNameFor(-3, 4, RegionFileFormat.ANVIL));
        assertEquals("r.-3.4.linear", LinearStorageProbe.fileNameFor(-3, 4, RegionFileFormat.LINEAR));
    }

    @Test
    public void probePrefersMcaOverLinear() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path mca = folder.resolve("r.0.0.mca");
        Path linear = folder.resolve("r.0.0.linear");
        Files.write(mca, new byte[] {1});
        Files.write(linear, new byte[] {2});
        assertEquals(mca, LinearStorageProbe.probeExisting(folder, 0, 0));
    }

    @Test
    public void probeFallsBackToLinear() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path linear = folder.resolve("r.5.-7.linear");
        Files.write(linear, new byte[] {2});
        assertEquals(linear, LinearStorageProbe.probeExisting(folder, 5, -7));
    }

    @Test
    public void probeNullWhenNeitherExists() {
        assertNull(LinearStorageProbe.probeExisting(tmp.getRoot().toPath(), 9, 9));
    }

    @Test
    public void refuseBrokenSymlinkOnlyForLinear() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path link = folder.resolve("r.0.0.linear");
        Files.createSymbolicLink(link, folder.resolve("nonexistent-target.linear"));
        assertTrue(LinearStorageProbe.shouldRefuseSymlink(RegionFileFormat.LINEAR, link));
        assertFalse(LinearStorageProbe.shouldRefuseSymlink(RegionFileFormat.ANVIL, link));
    }

    @Test
    public void noRefusalForRegularFile() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path regular = folder.resolve("r.1.1.linear");
        Files.write(regular, new byte[] {1});
        assertFalse(LinearStorageProbe.shouldRefuseSymlink(RegionFileFormat.LINEAR, regular));
        assertFalse(LinearStorageProbe.shouldRefuseSymlink(RegionFileFormat.ANVIL, regular));
    }

    @Test
    public void targetForSourceMapping() {
        Path mca = Path.of("region", "r.1.2.mca");
        assertEquals(Path.of("region", "r.1.2.linear"), LinearStorageProbe.targetForSource(mca));
        Path bare = Path.of("region", "r.1.2");
        assertEquals(Path.of("region", "r.1.2.linear"), LinearStorageProbe.targetForSource(bare));
    }

    @Test
    public void fileNameForInvalidFallsBackToMca() {
        assertEquals("r.1.2.mca", LinearStorageProbe.fileNameFor(1, 2, RegionFileFormat.INVALID));
    }

    @Test
    public void intactSymlinkNotRefused() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path target = folder.resolve("target.linear");
        Files.write(target, new byte[] {1});
        Path link = folder.resolve("r.2.2.linear");
        Files.createSymbolicLink(link, target);
        assertFalse(LinearStorageProbe.shouldRefuseSymlink(RegionFileFormat.LINEAR, link));
        assertFalse(LinearStorageProbe.shouldRefuseSymlink(RegionFileFormat.ANVIL, link));
    }

    @Test
    public void directoryNamedLinearIgnoredByProbe() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Files.createDirectory(folder.resolve("r.0.0.linear"));
        assertNull(LinearStorageProbe.probeExisting(folder, 0, 0));
    }

    @Test
    public void shouldUseLinearOnlyForLinear() {
        Path folder = tmp.getRoot().toPath();
        assertTrue(LinearStorageProbe.shouldUseLinear(folder, 0, 0, RegionFileFormat.LINEAR));
        assertFalse(LinearStorageProbe.shouldUseLinear(folder, 0, 0, RegionFileFormat.ANVIL));
        assertFalse(LinearStorageProbe.shouldUseLinear(folder, 0, 0, RegionFileFormat.INVALID));
    }

    @Test
    public void siblingIfProbedReturnsLinearWhenPresent() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path linear = folder.resolve("r.3.4.linear");
        Files.write(linear, new byte[] {2});
        assertEquals(linear, LinearStorageProbe.siblingIfProbed(folder, 3, 4));
    }

    @Test
    public void siblingIfProbedNullWhenAbsent() {
        Path folder = tmp.getRoot().toPath();
        assertNull(LinearStorageProbe.siblingIfProbed(folder, 8, 8));
    }

    @Test
    public void siblingIfProbedNullWhenOnlyMcaExists() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Files.write(folder.resolve("r.8.8.mca"), new byte[] {1});
        assertNull(LinearStorageProbe.siblingIfProbed(folder, 8, 8));
    }

    @Test
    public void siblingIfProbedIgnoresDirectory() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Files.createDirectory(folder.resolve("r.6.6.linear"));
        assertNull(LinearStorageProbe.siblingIfProbed(folder, 6, 6));
    }
}
