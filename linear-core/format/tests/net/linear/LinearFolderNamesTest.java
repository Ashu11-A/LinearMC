package net.linear;

import static org.junit.Assert.assertEquals;

import net.linear.probe.LinearFolderNames;
import org.junit.Test;

/** Per-world configuration keys derived from storage folders. */
public class LinearFolderNamesTest {

    @Test
    public void dimensionLayoutYieldsNamespacedIds() {
        assertEquals("minecraft:overworld",
            LinearFolderNames.worldKey("/srv/world/dimensions/minecraft/overworld/region"));
        assertEquals("minecraft:the_nether",
            LinearFolderNames.worldKey("/srv/world/dimensions/minecraft/the_nether/poi"));
        assertEquals("myplugin:arena/one",
            LinearFolderNames.worldKey("C:\\srv\\world\\dimensions\\myplugin\\arena\\one\\entities"));
        assertEquals("minecraft:the_end",
            LinearFolderNames.worldKey("/srv/world/dimensions/minecraft/the_end"));
    }

    @Test
    public void legacyLayoutMapsVanillaDimensionFolders() {
        assertEquals("minecraft:the_nether", LinearFolderNames.worldKey("/srv/world_nether/DIM-1/region"));
        assertEquals("minecraft:the_end", LinearFolderNames.worldKey("/srv/world_the_end/DIM1/region"));
        assertEquals("world", LinearFolderNames.worldKey("/srv/world/region"));
    }

    @Test
    public void storageFoldersAreTheThreeVanillaOnes() {
        assertEquals(java.util.List.of("region", "entities", "poi"), LinearFolderNames.STORAGE_FOLDERS);
    }
}
