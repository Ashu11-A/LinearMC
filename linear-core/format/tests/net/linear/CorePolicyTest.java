package net.linear;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Format selector + factory alias contract (format only; supplier seams
 * live in the flush/codec suites).
 */
public class CorePolicyTest {

    @Test
    public void clampMatchesShippedFallback() {
        assertEquals(6, RegionFileFormat.clampCompressionLevel(6));
        assertEquals(1, RegionFileFormat.clampCompressionLevel(1));
        assertEquals(22, RegionFileFormat.clampCompressionLevel(22));
        assertEquals(6, RegionFileFormat.clampCompressionLevel(0));
        assertEquals(6, RegionFileFormat.clampCompressionLevel(23));
        assertEquals(".mca", RegionFileFormat.ANVIL_EXTENSION);
        assertEquals(".linear", RegionFileFormat.LINEAR_EXTENSION);
        assertEquals(6, RegionFileFormat.DEFAULT_COMPRESSION_LEVEL);
    }

    @Test
    public void factoryAliasesMatchFormat() {
        assertEquals(RegionFileFormat.LINEAR_EXTENSION, AbstractRegionFileFactory.LINEAR_EXTENSION);
        assertEquals(RegionFileFormat.ANVIL_EXTENSION, AbstractRegionFileFactory.ANVIL_EXTENSION);
        assertEquals(RegionFileFormat.DEFAULT_COMPRESSION_LEVEL,
            AbstractRegionFileFactory.DEFAULT_COMPRESSION_LEVEL);
        assertEquals(RegionFileFormat.MAX_COMPRESSION_LEVEL,
            AbstractRegionFileFactory.MAX_COMPRESSION_LEVEL);
        assertEquals(".linear", AbstractRegionFileFactory.extensionFor(RegionFileFormat.LINEAR));
        assertEquals(".mca", AbstractRegionFileFactory.extensionFor(RegionFileFormat.ANVIL));
        assertEquals(".mca", AbstractRegionFileFactory.extensionFor(RegionFileFormat.INVALID));
    }
}
