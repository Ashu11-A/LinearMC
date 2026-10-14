package net.linear;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Format selector contract (docs/configuration.md). */
public class CoreFormatTest {

    @Test
    public void parsesKnownFormats() {
        assertEquals(RegionFileFormat.LINEAR, RegionFileFormat.fromString("LINEAR"));
        assertEquals(RegionFileFormat.ANVIL, RegionFileFormat.fromString("ANVIL"));
    }

    @Test
    public void parseIsCaseInsensitive() {
        assertEquals(RegionFileFormat.LINEAR, RegionFileFormat.fromString("linear"));
        assertEquals(RegionFileFormat.ANVIL, RegionFileFormat.fromString("anvil"));
    }

    @Test
    public void unknownOrNullYieldsInvalid() {
        assertEquals(RegionFileFormat.INVALID, RegionFileFormat.fromString("mcc"));
        assertEquals(RegionFileFormat.INVALID, RegionFileFormat.fromString(""));
        assertEquals(RegionFileFormat.INVALID, RegionFileFormat.fromString(null));
    }

    @Test
    public void parsesBufferedLinear() {
        assertEquals(RegionFileFormat.B_LINEAR, RegionFileFormat.fromString("B_LINEAR"));
        assertEquals(RegionFileFormat.B_LINEAR, RegionFileFormat.fromString("b_linear"));
        assertEquals(".b_linear", RegionFileFormat.B_LINEAR_EXTENSION);
    }

    @Test
    public void linearFamilyCoversBoth() {
        assertEquals(true, RegionFileFormat.isLinearFamily(RegionFileFormat.LINEAR));
        assertEquals(true, RegionFileFormat.isLinearFamily(RegionFileFormat.B_LINEAR));
        assertEquals(false, RegionFileFormat.isLinearFamily(RegionFileFormat.ANVIL));
        assertEquals(false, RegionFileFormat.isLinearFamily(RegionFileFormat.INVALID));
    }

    @Test
    public void bucketGridWhitelist() {
        for (int g : new int[] {1, 2, 4, 8, 16, 32}) {
            assertEquals(true, LinearBucketGrid.isValid(g));
        }
        for (int g : new int[] {0, 3, 5, 7, 33, -8}) {
            assertEquals(false, LinearBucketGrid.isValid(g));
        }
        assertEquals(4, LinearBucketGrid.cellCount(8));
        assertEquals(64, LinearBucketGrid.bucketCount(8));
    }
}
