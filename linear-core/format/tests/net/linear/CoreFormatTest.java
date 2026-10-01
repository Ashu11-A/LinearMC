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
}
