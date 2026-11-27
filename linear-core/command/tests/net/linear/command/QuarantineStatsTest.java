package net.linear.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.linear.LinearRegionTimings;
import net.linear.command.stats.LinearStatsCompact;
import net.linear.command.stats.LinearStatsView;
import net.linear.command.stats.health.LinearStatsHealth;
import org.junit.Test;

/** /linear stats shows quarantined region files (and stays unchanged when there are none). */
public class QuarantineStatsTest {

    private static LinearRegionTimings.LinearFolderSnapshot snapshot(long quarantines) {
        return snapshot(quarantines, 0L);
    }

    private static LinearRegionTimings.LinearFolderSnapshot snapshot(long quarantines, long conflicts) {
        LinearRegionTimings.LinearTimings empty = LinearRegionTimings.LinearTimings.EMPTY;
        return new LinearRegionTimings.LinearFolderSnapshot(
            empty, empty, empty, empty,
            0L, 3L, 0L, 0L, 0L, 0L, 0L, 0,
            0L, 0L, 0L, 0L, -1L, quarantines, conflicts);
    }

    private static Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps(long quarantines) {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/srv/world/region", snapshot(quarantines));
        snaps.put("/srv/world/poi", snapshot(0L));
        return snaps;
    }

    @Test
    public void compactTotalsShowQuarantinedFiles() {
        List<LinearStatsCompact.CompactRow> rows =
            LinearStatsCompact.renderCompact(snaps(2L), Optional.empty());
        LinearStatsCompact.CompactRow totals = rows.get(rows.size() - 1);
        assertEquals(LinearStatsCompact.RowKind.TOTALS, totals.kind());
        assertTrue(totals.text(), totals.text().endsWith(" folders quarantined=2"));
        boolean folderFlagged = false;
        for (LinearStatsCompact.CompactRow row : rows) {
            if (row.kind() == LinearStatsCompact.RowKind.FOLDER && row.parts().containsKey("quarantined")) {
                folderFlagged = true;
                assertEquals("q2", row.parts().get("quarantined"));
                assertEquals(LinearStatsHealth.CRIT, row.health().get("quarantined"));
            }
        }
        assertTrue(folderFlagged);
    }

    @Test
    public void compactOutputUnchangedWithoutQuarantines() {
        List<LinearStatsCompact.CompactRow> rows =
            LinearStatsCompact.renderCompact(snaps(0L), Optional.empty());
        for (LinearStatsCompact.CompactRow row : rows) {
            assertFalse(row.text(), row.text().contains("quarantined"));
            assertFalse(row.parts().containsKey("quarantined"));
        }
    }

    @Test
    public void plainViewShowsQuarantinedFiles() {
        List<String> rows = LinearStatsView.render(snaps(1L), Optional.empty());
        assertTrue(rows.get(rows.size() - 1).endsWith("quarantined=1"));
        List<String> clean = LinearStatsView.render(snaps(0L), Optional.empty());
        for (String row : clean) {
            assertFalse(row.contains("quarantined"));
        }
    }

    @Test
    public void conflictCopiesAreReportedApartFromQuarantines() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("/srv/world/region", snapshot(0L, 3L));
        List<LinearStatsCompact.CompactRow> rows = LinearStatsCompact.renderCompact(snaps, Optional.empty());
        LinearStatsCompact.CompactRow totals = rows.get(rows.size() - 1);
        assertTrue(totals.text(), totals.text().endsWith(" folders conflicts=3"));
        assertFalse(totals.text(), totals.text().contains("quarantined"));
        boolean folderFlagged = false;
        for (LinearStatsCompact.CompactRow row : rows) {
            if (row.kind() == LinearStatsCompact.RowKind.FOLDER) {
                folderFlagged = true;
                assertEquals("c3", row.parts().get("conflicts"));
                assertFalse(row.parts().containsKey("quarantined"));
            }
        }
        assertTrue(folderFlagged);
        List<String> plain = LinearStatsView.render(snaps, Optional.empty());
        assertTrue(plain.get(plain.size() - 1).endsWith("conflicts=3"));
    }

    @Test
    public void legacyConstructorReportsZeroQuarantines() {
        LinearRegionTimings.LinearTimings empty = LinearRegionTimings.LinearTimings.EMPTY;
        LinearRegionTimings.LinearFolderSnapshot legacy = new LinearRegionTimings.LinearFolderSnapshot(
            empty, empty, empty, empty, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0L, 0L, 0L, 0L, -1L);
        assertEquals(0L, legacy.quarantines());
        assertEquals(0L, legacy.conflicts());
        assertEquals(0L, LinearRegionTimings.LinearFolderSnapshot.EMPTY.quarantines());
    }
}
