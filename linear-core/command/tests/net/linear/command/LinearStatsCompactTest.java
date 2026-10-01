package net.linear.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.linear.LinearRegionTimings;
import org.junit.Test;

/** command: compact proposal-A view (health classifier + widths + line counts). */
public class LinearStatsCompactTest {

    private static LinearRegionTimings.LinearFolderSnapshot snapshot(
        int dirty, long failures, long p99Micros, long raw, long packed,
        long files, long ageMs) {
        LinearRegionTimings.LinearTimings empty = LinearRegionTimings.LinearTimings.EMPTY;
        return new LinearRegionTimings.LinearFolderSnapshot(
            empty, empty, empty, empty,
            0L, files, failures, 0L, 0L, 0L, 0L, dirty,
            raw, packed, 0L, p99Micros, ageMs);
    }

    @Test
    public void dirtyBoundaries() {
        assertEquals(LinearStatsHealth.OK, LinearStatsStatus.dirtyHealth(0));
        assertEquals(LinearStatsHealth.OK, LinearStatsStatus.dirtyHealth(50));
        assertEquals(LinearStatsHealth.WARN, LinearStatsStatus.dirtyHealth(51));
        assertEquals(LinearStatsHealth.WARN, LinearStatsStatus.dirtyHealth(256));
        assertEquals(LinearStatsHealth.CRIT, LinearStatsStatus.dirtyHealth(257));
    }

    @Test
    public void failureBoundaries() {
        assertEquals(LinearStatsHealth.OK, LinearStatsStatus.failuresHealth(0));
        assertEquals(LinearStatsHealth.WARN, LinearStatsStatus.failuresHealth(1));
        assertEquals(LinearStatsHealth.CRIT, LinearStatsStatus.failuresHealth(2));
    }

    @Test
    public void p99BoundariesMicros() {
        assertEquals(LinearStatsHealth.OK, LinearStatsStatus.p99Health(-1));
        assertEquals(LinearStatsHealth.OK, LinearStatsStatus.p99Health(99_999L));
        assertEquals(LinearStatsHealth.WARN, LinearStatsStatus.p99Health(100_000L));
        assertEquals(LinearStatsHealth.WARN, LinearStatsStatus.p99Health(500_000L));
        assertEquals(LinearStatsHealth.CRIT, LinearStatsStatus.p99Health(500_001L));
    }

    @Test
    public void savedBoundaries() {
        assertNull(LinearStatsStatus.savedHealth(0L, 0L));
        assertEquals(LinearStatsHealth.CRIT, LinearStatsStatus.savedHealth(1000L, 1000L));
        assertEquals(LinearStatsHealth.CRIT, LinearStatsStatus.savedHealth(1000L, 1500L));
        assertEquals(LinearStatsHealth.OK, LinearStatsStatus.savedHealth(1000L, 750L));
        assertEquals(LinearStatsHealth.OK, LinearStatsStatus.savedHealth(1000L, 300L));
        assertEquals(LinearStatsHealth.WARN, LinearStatsStatus.savedHealth(1000L, 760L));
        assertEquals(LinearStatsHealth.WARN, LinearStatsStatus.savedHealth(1000L, 290L));
    }

    @Test
    public void threeWorldsRenderElevenLines() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        String[] worlds = {"ow", "ne", "end"};
        String[] types = {"region", "poi", "entities"};
        for (String w : worlds) {
            for (String t : types) {
                snaps.put("/data/" + w + "/" + t,
                    snapshot(12, 0, 12_300L, 1000L, 580L, 3L, 3100L));
            }
        }
        List<LinearStatsCompact.CompactRow> rows = LinearStatsCompact.renderCompact(
            snaps, Optional.empty(), world -> 6);
        assertEquals(11, rows.size());
        assertEquals(LinearStatsCompact.RowKind.HEADER, rows.get(0).kind());
        assertEquals(LinearStatsCompact.RowKind.TOTALS,
            rows.get(rows.size() - 1).kind());
        for (LinearStatsCompact.CompactRow r : rows) {
            assertTrue("line too long (" + r.text().length() + "): " + r.text(),
                r.text().length() <= 55);
        }
        String folder = rows.get(1).text();
        assertTrue(folder.contains("L6"));
        assertTrue(folder.contains("d12/512"));
        assertTrue(folder.contains("f0"));
        assertTrue(folder.contains("p99"));
        assertTrue(folder.contains("%"));
        assertTrue(folder.contains("W"));
        String totals = rows.get(rows.size() - 1).text();
        assertTrue(totals.contains("files="));
        assertTrue(totals.contains("fail="));
        assertTrue(totals.contains("%"));
    }

    @Test
    public void specExampleFits48() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/data/ow/region", snapshot(12, 0, 12_300L, 1000L, 580L, 1L, 3100L));
        // World age newest across the world's folders; single folder here.
        List<LinearStatsCompact.CompactRow> rows = LinearStatsCompact.renderCompact(
            snaps, Optional.empty(), world -> 6);
        assertEquals(3, rows.size());
        String folder = rows.get(1).text();
        assertTrue(folder.length() <= 48);
        assertTrue(folder.startsWith("ow L6 reg "));
    }

    @Test
    public void savedNaWhenRawZero() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/data/world/region", snapshot(0, 0, 0L, 0L, 0L, 0L, -1L));
        List<LinearStatsCompact.CompactRow> rows = LinearStatsCompact.renderCompact(
            snaps, Optional.empty(), world -> -1);
        LinearStatsCompact.CompactRow folder = rows.get(1);
        assertTrue(folder.text().contains("na"));
        assertTrue(!folder.health().containsKey("saved"));
        assertTrue(folder.text().contains("L?"));
    }

    @Test
    public void emptyAndFilter() {
        List<LinearStatsCompact.CompactRow> empty = LinearStatsCompact.renderCompact(
            new LinkedHashMap<>(), Optional.empty(), world -> 6);
        assertEquals(1, empty.size());
        assertEquals(LinearStatsCompact.RowKind.EMPTY, empty.get(0).kind());

        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/data/world/region", snapshot(1, 0, 100L, 100L, 50L, 1L, 10L));
        snaps.put("/data/nether/region", snapshot(1, 0, 100L, 100L, 50L, 1L, 10L));
        List<LinearStatsCompact.CompactRow> filtered = LinearStatsCompact.renderCompact(
            snaps, Optional.of("world"), world -> 6);
        assertEquals(3, filtered.size());
        assertTrue(filtered.get(0).text().contains("filter=world"));
    }

    @Test
    public void longWorldNameTruncatedToWidth() {
        // Realistic overflow: an unbounded world tag once rendered a 62-char
        // row; the world segment is truncated to 8 chars so every row stays <=55.
        String longWorld = "world_the_end_with_a_very_long_dimension_suffix";
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/data/" + longWorld + "/region",
            snapshot(12, 0, 12_300L, 1000L, 580L, 3L, 3100L));
        List<LinearStatsCompact.CompactRow> rows = LinearStatsCompact.renderCompact(
            snaps, Optional.empty(), world -> 6);
        assertEquals(3, rows.size());
        LinearStatsCompact.CompactRow folder = rows.get(1);
        for (LinearStatsCompact.CompactRow r : rows) {
            assertTrue("line too long (" + r.text().length() + "): " + r.text(),
                r.text().length() <= 55);
        }
        assertEquals(longWorld.substring(0, 8), folder.parts().get("world"));
        assertTrue(folder.text().startsWith(longWorld.substring(0, 8) + " L6 reg "));
    }

    @Test
    public void folderPartsAndHealthKeys() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/data/world/region", snapshot(12, 0, 12_300L, 1000L, 580L, 3L, 3100L));
        List<LinearStatsCompact.CompactRow> rows = LinearStatsCompact.renderCompact(
            snaps, Optional.empty(), world -> 6);
        LinearStatsCompact.CompactRow folder = rows.get(1);
        assertEquals(LinearStatsCompact.RowKind.FOLDER, folder.kind());
        assertEquals(java.util.Set.of("world", "level", "type", "dirty", "fail", "p99", "saved", "age", "wage"),
            folder.parts().keySet());
        assertTrue(folder.health().containsKey("dirty"));
        assertTrue(folder.health().containsKey("fail"));
        assertTrue(folder.health().containsKey("p99"));
        assertTrue(folder.health().containsKey("saved"));
        assertEquals("world", folder.parts().get("world"));
        assertEquals("6", folder.parts().get("level"));
        assertEquals("reg", folder.parts().get("type"));
    }

    @Test
    public void totalsHealthAndParts() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/data/world/region", snapshot(12, 1, 12_300L, 1000L, 580L, 3L, 3100L));
        List<LinearStatsCompact.CompactRow> rows = LinearStatsCompact.renderCompact(
            snaps, Optional.empty(), world -> 6);
        LinearStatsCompact.CompactRow totals = rows.get(rows.size() - 1);
        assertEquals(LinearStatsCompact.RowKind.TOTALS, totals.kind());
        assertEquals(java.util.Set.of("files", "fail", "saved"), totals.parts().keySet());
        assertEquals(net.linear.command.LinearStatsHealth.WARN, totals.health().get("fail"));
        assertTrue(totals.health().containsKey("saved"));
        assertTrue(totals.text().contains("files=3"));
        assertTrue(totals.text().contains("fail=1"));
    }
}
