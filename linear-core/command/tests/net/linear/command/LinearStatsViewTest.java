package net.linear.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.linear.LinearRegionTimings;
import org.junit.Test;

/** command: stats view-model rows (grouping, totals, filter, infer helpers). */
public class LinearStatsViewTest {

    private static LinearRegionTimings.LinearFolderSnapshot snapshot(
        long readCount, long writeCount, long flushCount, long loadCount,
        long files, long failures, int dirtyDepth) {
        LinearRegionTimings.LinearTimings read =
            new LinearRegionTimings.LinearTimings(readCount, readCount * 100L, 500L, 100L);
        LinearRegionTimings.LinearTimings write =
            new LinearRegionTimings.LinearTimings(writeCount, writeCount * 200L, 600L, 200L);
        LinearRegionTimings.LinearTimings flush =
            new LinearRegionTimings.LinearTimings(flushCount, flushCount * 300L, 700L, 300L);
        LinearRegionTimings.LinearTimings load =
            new LinearRegionTimings.LinearTimings(loadCount, loadCount * 400L, 800L, 400L);
        return new LinearRegionTimings.LinearFolderSnapshot(
            read, write, flush, load,
            1L, files, failures, 0L, 0L, 0L, 0L, dirtyDepth,
            0L, 0L, 0L, 0L, -1L);
    }

    @Test
    public void emptyStateWhenNoSnapshots() {
        List<String> rows =
            LinearStatsView.render(new LinkedHashMap<>(), Optional.empty());
        assertEquals(1, rows.size());
        assertEquals(LinearPermissions.EMPTY_STATE, rows.get(0));
    }

    @Test
    public void emptyStateWhenFilterMatchesNothing() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/data/world/region", snapshot(1, 1, 1, 1, 1, 0, 0));
        List<String> rows = LinearStatsView.render(snaps, Optional.of("nether"));
        assertEquals(1, rows.size());
        assertEquals(LinearPermissions.EMPTY_STATE, rows.get(0));
    }

    @Test
    public void groupsByWorldWithTypeRowsAndTotals() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/data/world/region", snapshot(2, 3, 4, 5, 6, 1, 2));
        snaps.put("/data/world/poi", snapshot(1, 1, 1, 1, 1, 0, 0));
        List<String> rows = LinearStatsView.render(snaps, Optional.empty());

        assertTrue(rows.get(0).contains("Linear stats (2 folders)"));
        assertTrue(rows.stream().anyMatch(r -> r.startsWith("world ")));
        assertTrue(rows.stream().anyMatch(r -> r.contains("[region]")));
        assertTrue(rows.stream().anyMatch(r -> r.contains("[poi]")));
        // r/w/f/l timings on the type rows.
        assertTrue(rows.stream().anyMatch(r -> r.contains("r=") && r.contains("w=")
            && r.contains("f=") && r.contains("l=")));
        // Dirty-depth bar on the detail rows.
        assertTrue(rows.stream().anyMatch(r -> r.contains("dirty [")));
        String totals = rows.get(rows.size() - 1);
        assertTrue(totals.startsWith("TOTALS:"));
        assertTrue(totals.contains("folders=2"));
        assertTrue(totals.contains("files=7"));
        assertTrue(totals.contains("fail=1"));
    }

    @Test
    public void worldFilterNarrowsToMatchingFolders() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/data/world/region", snapshot(1, 1, 1, 1, 1, 0, 0));
        snaps.put("/data/nether/region", snapshot(1, 1, 1, 1, 1, 0, 0));
        List<String> rows = LinearStatsView.render(snaps, Optional.of("world"));

        assertTrue(rows.get(0).contains("filter=world"));
        assertTrue(rows.stream().anyMatch(r -> r.startsWith("world ")));
        assertTrue(rows.stream().noneMatch(r -> r.startsWith("nether ")));
        assertTrue(rows.get(rows.size() - 1).contains("folders=1"));
    }

    @Test
    public void inferWorldTakesParentDirName() {
        assertEquals("world", LinearStatsView.inferWorld("/data/world/region"));
        assertEquals("world", LinearStatsView.inferWorld("C:\\data\\world\\poi"));
        assertEquals("plain", LinearStatsView.inferWorld("plain"));
    }

    @Test
    public void inferFolderTypePinsRegionPoiEntities() {
        assertEquals("region", LinearStatsView.inferFolderType("/data/world/region"));
        assertEquals("poi", LinearStatsView.inferFolderType("/data/world/poi"));
        assertEquals("entities", LinearStatsView.inferFolderType("/data/world/entities"));
        assertEquals("region", LinearStatsView.inferFolderType("/data/world/unknown"));
        assertEquals("entities", LinearStatsView.inferFolderType("C:\\data\\w\\entities"));
    }

    @Test
    public void snapshotsMapAveragesWithZeroGuard() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/data/world/region", snapshot(2, 4, 0, 0, 6, 1, 2));
        List<LinearStatsView.FolderSnapshot> out = LinearStatsView.snapshots(snaps);
        assertEquals(1, out.size());
        LinearStatsView.FolderSnapshot s = out.get(0);
        assertEquals("/data/world/region", s.folderKey());
        assertEquals("region", s.folderType());
        assertEquals("world", s.worldName());
        assertEquals(2L, s.reads());
        assertEquals(100L, s.readMicrosAvg());
        assertEquals(4L, s.writes());
        assertEquals(200L, s.writeMicrosAvg());
        // Zero flushes: avg guard yields 0, not a divide-by-zero.
        assertEquals(0L, s.flushes());
        assertEquals(0L, s.flushMicrosAvg());
        assertEquals(6L, s.filesFlushed());
        assertEquals(1L, s.failures());
        assertEquals(2, s.dirtyNow());
        // No per-folder compression source: 0=unknown.
        assertEquals(0, s.compressionLevel());
        assertEquals(-1L, s.millisSinceLastFlush());
    }

    @Test
    public void snapshotLookupEmptyWhenAbsent() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/data/world/region", snapshot(1, 1, 1, 1, 1, 0, 0));
        assertTrue(LinearStatsView.snapshot(snaps, "/data/world/region").isPresent());
        assertTrue(LinearStatsView.snapshot(snaps, "/data/world/poi").isEmpty());
        assertTrue(LinearStatsView.snapshots(new LinkedHashMap<>()).isEmpty());
    }
}
