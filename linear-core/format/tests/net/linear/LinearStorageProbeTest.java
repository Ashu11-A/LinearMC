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
import net.linear.probe.LinearStorageProbe;

/** Probe contract for the Canvas overlay / Horizon mixin storage open paths. */
public class LinearStorageProbeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void fileNameForBothFormats() {
        assertEquals("r.1.2.mca", LinearStorageProbe.fileNameFor(1, 2, RegionFileFormat.ANVIL));
        assertEquals("r.1.2.linear", LinearStorageProbe.fileNameFor(1, 2, RegionFileFormat.LINEAR));
        assertEquals("r.-3.4.mca", LinearStorageProbe.fileNameFor(-3, 4, RegionFileFormat.ANVIL));
        assertEquals("r.-3.4.linear", LinearStorageProbe.fileNameFor(-3, 4, RegionFileFormat.LINEAR));
        assertEquals("r.1.2.b_linear", LinearStorageProbe.fileNameFor(1, 2, RegionFileFormat.B_LINEAR));
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
    public void resolveFileNameKeepsExistingMcaUnderLinearConfig() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Files.write(folder.resolve("r.2.-1.mca"), new byte[] {1});
        assertEquals("r.2.-1.mca",
            LinearStorageProbe.resolveFileName(folder, 2, -1, RegionFileFormat.LINEAR));
        assertEquals("r.2.-1.mca",
            LinearStorageProbe.resolveFileName(folder, 2, -1, RegionFileFormat.B_LINEAR));
    }

    @Test
    public void resolveFileNameKeepsExistingLinearUnderAnvilConfig() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Files.write(folder.resolve("r.0.3.linear"), new byte[] {2});
        Files.write(folder.resolve("r.0.4.b_linear"), new byte[] {3});
        assertEquals("r.0.3.linear",
            LinearStorageProbe.resolveFileName(folder, 0, 3, RegionFileFormat.ANVIL));
        assertEquals("r.0.4.b_linear",
            LinearStorageProbe.resolveFileName(folder, 0, 4, RegionFileFormat.ANVIL));
    }

    @Test
    public void resolveFileNamePrecedenceWhenDuplicatesExist() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Files.write(folder.resolve("r.1.1.mca"), new byte[] {1});
        Files.write(folder.resolve("r.1.1.linear"), new byte[] {2});
        Files.write(folder.resolve("r.2.2.linear"), new byte[] {2});
        Files.write(folder.resolve("r.2.2.b_linear"), new byte[] {3});
        // .mca wins over .linear, .linear over .b_linear, whatever is configured
        assertEquals("r.1.1.mca",
            LinearStorageProbe.resolveFileName(folder, 1, 1, RegionFileFormat.LINEAR));
        assertEquals("r.2.2.linear",
            LinearStorageProbe.resolveFileName(folder, 2, 2, RegionFileFormat.B_LINEAR));
    }

    @Test
    public void regionMidConversionResolvesToItsSource() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path source = folder.resolve("r.3.3.linear");
        Path target = folder.resolve("r.3.3.mca");
        Files.write(source, new byte[] {1});
        Files.write(target, new byte[] {2});
        assertEquals(target, LinearStorageProbe.probeExisting(folder, 3, 3));
        LinearStorageProbe.markConverting(source);
        try {
            assertEquals(source, LinearStorageProbe.probeExisting(folder, 3, 3));
            assertEquals("r.3.3.linear",
                LinearStorageProbe.resolveFileName(folder, 3, 3, RegionFileFormat.ANVIL));
        } finally {
            LinearStorageProbe.unmarkConverting(source);
        }
        assertEquals(target, LinearStorageProbe.probeExisting(folder, 3, 3));
    }

    @Test
    public void unmarkKeepsALaterSourceOfTheSameRegion() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path mca = folder.resolve("r.4.4.mca");
        Path buffered = folder.resolve("r.4.4.b_linear");
        Files.write(mca, new byte[] {1});
        Files.write(buffered, new byte[] {2});
        LinearStorageProbe.markConverting(mca);
        LinearStorageProbe.markConverting(buffered);
        try {
            LinearStorageProbe.unmarkConverting(mca);
            assertEquals(buffered, LinearStorageProbe.probeExisting(folder, 4, 4));
        } finally {
            LinearStorageProbe.unmarkConverting(buffered);
        }
        assertEquals(mca, LinearStorageProbe.probeExisting(folder, 4, 4));
    }

    @Test
    public void hostOpenedAnvilFileIsNeverDeleted() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path opened = folder.resolve("r.5.5.mca");
        Path linear = folder.resolve("r.5.5.linear");
        Files.write(opened, new byte[] {1});
        Files.write(linear, new byte[] {2});
        assertEquals(opened, LinearStorageProbe.hostOpening(opened));
        assertEquals("only Anvil files are recorded", linear, LinearStorageProbe.hostOpening(linear));
        assertFalse(LinearStorageProbe.deleteUnlessHostOpened(opened));
        assertTrue(Files.isRegularFile(opened));
        assertTrue(LinearStorageProbe.deleteUnlessHostOpened(linear));
        assertFalse(Files.exists(linear));
    }

    @Test
    public void converterOpensAreNotHostOpens() throws Exception {
        Path source = tmp.getRoot().toPath().resolve("r.6.6.mca");
        Files.write(source, new byte[] {1});
        assertEquals(source, LinearStorageProbe.converterOpen(() -> LinearStorageProbe.hostOpening(source)));
        assertTrue(LinearStorageProbe.deleteUnlessHostOpened(source));
        assertFalse(Files.exists(source));
    }

    @Test
    public void hostOpeningFollowsARegionConvertedAway() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path linear = folder.resolve("r.7.8.linear");
        Files.write(linear, new byte[] {1});
        assertEquals(linear, LinearStorageProbe.hostOpening(folder.resolve("r.7.8.mca")));
        Path fresh = folder.resolve("r.9.9.mca");
        assertEquals("a new Anvil region opens as asked", fresh, LinearStorageProbe.hostOpening(fresh));
    }

    @Test
    public void resolveFileNameUsesConfiguredFormatForNewRegions() {
        Path folder = tmp.getRoot().toPath();
        assertEquals("r.7.7.linear",
            LinearStorageProbe.resolveFileName(folder, 7, 7, RegionFileFormat.LINEAR));
        assertEquals("r.7.7.b_linear",
            LinearStorageProbe.resolveFileName(folder, 7, 7, RegionFileFormat.B_LINEAR));
        assertEquals("r.7.7.mca",
            LinearStorageProbe.resolveFileName(folder, 7, 7, RegionFileFormat.ANVIL));
        assertEquals("r.7.7.linear",
            LinearStorageProbe.resolveFileName(null, 7, 7, RegionFileFormat.LINEAR));
    }

    @Test
    public void probeNullWhenNeitherExists() {
        assertNull(LinearStorageProbe.probeExisting(tmp.getRoot().toPath(), 9, 9));
    }

    @Test
    public void probeOrderMcaLinearBuffered() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path mca = folder.resolve("r.0.0.mca");
        Path linear = folder.resolve("r.0.0.linear");
        Path buffered = folder.resolve("r.0.0.b_linear");
        Files.write(buffered, new byte[] {3});
        assertEquals(buffered, LinearStorageProbe.probeExisting(folder, 0, 0));
        Files.write(linear, new byte[] {2});
        assertEquals(linear, LinearStorageProbe.probeExisting(folder, 0, 0));
        Files.write(mca, new byte[] {1});
        assertEquals(mca, LinearStorageProbe.probeExisting(folder, 0, 0));
        assertEquals(buffered, LinearStorageProbe.bufferedSiblingIfProbed(folder, 0, 0));
    }

    @Test
    public void bufferedTargetMapping() {
        Path mca = Path.of("region", "r.1.2.mca");
        assertEquals(Path.of("region", "r.1.2.b_linear"), LinearStorageProbe.bufferedTargetForSource(mca));
        Path lin = Path.of("region", "r.1.2.linear");
        assertEquals(Path.of("region", "r.1.2.b_linear"), LinearStorageProbe.bufferedTargetForSource(lin));
    }

    @Test
    public void symlinkGuardCoversFamily() throws Exception {
        Path folder = tmp.getRoot().toPath();
        Path link = folder.resolve("r.0.0.b_linear");
        Files.createSymbolicLink(link, folder.resolve("nonexistent-target.b_linear"));
        assertTrue(LinearStorageProbe.shouldRefuseSymlink(RegionFileFormat.B_LINEAR, link));
        assertFalse(LinearStorageProbe.shouldRefuseSymlink(RegionFileFormat.ANVIL, link));
    }

    @Test
    public void shouldUseBufferedOnlyForBuffered() {
        Path folder = tmp.getRoot().toPath();
        assertTrue(LinearStorageProbe.shouldUseBuffered(folder, 0, 0, RegionFileFormat.B_LINEAR));
        assertFalse(LinearStorageProbe.shouldUseBuffered(folder, 0, 0, RegionFileFormat.LINEAR));
        assertFalse(LinearStorageProbe.shouldUseBuffered(folder, 0, 0, RegionFileFormat.ANVIL));
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
