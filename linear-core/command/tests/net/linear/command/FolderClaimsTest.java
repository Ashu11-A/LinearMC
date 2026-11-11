package net.linear.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.file.Path;
import java.util.List;
import net.linear.command.queue.FolderClaims;
import org.junit.Test;

/** All-or-none folder claims shared by every leg's /linear convert --execute. */
public class FolderClaimsTest {

    @Test
    public void claimsAllOrNoneAndReportsTheBusyFolder() {
        Path region = Path.of("/w/region");
        Path entities = Path.of("/w/entities");
        Path poi = Path.of("/w/poi");
        List<Path> world = List.of(region, entities, poi);
        assertNull(FolderClaims.claimAll(List.of(entities)));
        try {
            assertEquals(entities, FolderClaims.claimAll(world));
            // region was released by the failed attempt: it can be claimed alone
            assertNull(FolderClaims.claimAll(List.of(region)));
            FolderClaims.releaseAll(List.of(region));
        } finally {
            FolderClaims.releaseAll(List.of(entities));
        }
        assertNull(FolderClaims.claimAll(world));
        FolderClaims.releaseAll(world);
        assertEquals("conversion already running for /w/poi", FolderClaims.busyMessage(poi));
    }
}
