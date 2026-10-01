package net.linear.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import net.linear.LinearFlushCoordinator;
import net.linear.LinearFolderNames;
import net.linear.LinearRegionTimings;

/**
 * Pure-JDK plain-text view-model for the {@code /linear stats} panel.
 *
 * <p>Renders {@link LinearRegionTimings.LinearFolderSnapshot}s as grouped
 * per-world rows (region/poi/entities sections, human units, dirty-depth
 * bar, TOTALS footer). No Bukkit: legs send each returned row through
 * their own sender. Grouping and row shapes match the legacy panels.
 */
public final class LinearStatsView {

    private LinearStatsView() {
    }

    /**
     * Renders the snapshots as plain-text rows: one header, per-world
     * groups of two rows per folder type, and a TOTALS footer. Applies
     * {@code worldFilter} as a substring match on the folder key when
     * present. Returns the legacy empty-state line when nothing remains.
     */
    public static List<String> render(
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snapshots,
        Optional<String> worldFilter) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (worldFilter == null) {
            throw new IllegalArgumentException("worldFilter must not be null");
        }
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = snapshots;
        if (worldFilter.isPresent()) {
            String want = worldFilter.get();
            Map<String, LinearRegionTimings.LinearFolderSnapshot> filtered = new TreeMap<>();
            for (Map.Entry<String, LinearRegionTimings.LinearFolderSnapshot> e : snaps.entrySet()) {
                if (e.getKey().contains(want)) {
                    filtered.put(e.getKey(), e.getValue());
                }
            }
            snaps = filtered;
        }
        List<String> rows = new ArrayList<>();
        if (snaps.isEmpty()) {
            rows.add(LinearPermissions.EMPTY_STATE);
            return rows;
        }
        Map<String, Map<String, LinearRegionTimings.LinearFolderSnapshot>> byWorld = new TreeMap<>();
        for (Map.Entry<String, LinearRegionTimings.LinearFolderSnapshot> e : snaps.entrySet()) {
            byWorld.computeIfAbsent(inferWorld(e.getKey()), k -> new TreeMap<>())
                .put(inferFolderType(e.getKey()), e.getValue());
        }
        long tRead = 0;
        long tReadMicros = 0;
        long tWrite = 0;
        long tWriteMicros = 0;
        long tFlush = 0;
        long tFlushMicros = 0;
        long tLoad = 0;
        long tFiles = 0;
        long tFail = 0;
        long tRaw = 0;
        long tPacked = 0;

        StringBuilder head = new StringBuilder("Linear stats (" + snaps.size() + " folders)");
        worldFilter.ifPresent(w -> head.append(" filter=").append(w));
        rows.add(head.toString());
        for (Map.Entry<String, Map<String, LinearRegionTimings.LinearFolderSnapshot>> we
            : byWorld.entrySet()) {
            String world = we.getKey();
            long newestSince = Long.MAX_VALUE;
            boolean anyFlushed = false;
            for (LinearRegionTimings.LinearFolderSnapshot s : we.getValue().values()) {
                if (s.millisSinceLastFlush() >= 0L) {
                    anyFlushed = true;
                    newestSince = Math.min(newestSince, s.millisSinceLastFlush());
                }
            }
            rows.add(world + "  "
                + (anyFlushed
                    ? "last flush " + LinearStatsFormat.humanMillisSince(newestSince)
                    : "never flushed"));
            for (Map.Entry<String, LinearRegionTimings.LinearFolderSnapshot> fe
                : we.getValue().entrySet()) {
                String type = fe.getKey();
                LinearRegionTimings.LinearFolderSnapshot s = fe.getValue();
                rows.add(String.format("  %-8s r=%-6d (%-7s) w=%-6d (%-7s) f=%-6d (%-7s) l=%d",
                    "[" + type + "]",
                    s.read().count(), LinearStatsFormat.humanMicros(s.read().avgMicros()),
                    s.write().count(), LinearStatsFormat.humanMicros(s.write().avgMicros()),
                    s.flush().count(), LinearStatsFormat.humanMicros(s.flush().avgMicros()),
                    s.load().count()));
                String bar =
                    LinearStatsFormat.dirtyBar(s.dirtyDepth(), LinearFlushCoordinator.MAX_DIRTY);
                String bytes = s.rawBytes() <= 0L ? "no bytes yet"
                    : LinearStatsFormat.humanBytes(s.rawBytes()) + "->"
                        + LinearStatsFormat.humanBytes(s.compressedBytes())
                        + " (" + LinearStatsFormat.savedPercent(s.rawBytes(), s.compressedBytes())
                        + "% saved)";
                rows.add(String.format("    files=%-5d fail=%-3d dirty %s  %s  p50 %s p99 %s  %s",
                    s.filesFlushed(), s.failures(), bar, bytes,
                    LinearStatsFormat.humanMicros(s.flushP50Micros()),
                    LinearStatsFormat.humanMicros(s.flushP99Micros()),
                    s.millisSinceLastFlush() < 0L ? "never flushed"
                        : LinearStatsFormat.humanMillisSince(s.millisSinceLastFlush()) + " ago"));

                tRead += s.read().count();
                tReadMicros += s.read().totalMicros();
                tWrite += s.write().count();
                tWriteMicros += s.write().totalMicros();
                tFlush += s.flush().count();
                tFlushMicros += s.flush().totalMicros();
                tLoad += s.load().count();
                tFiles += s.filesFlushed();
                tFail += s.failures();
                tRaw += s.rawBytes();
                tPacked += s.compressedBytes();
            }
        }
        String totalBytes = tRaw <= 0L ? "no bytes yet"
            : LinearStatsFormat.humanBytes(tRaw) + "->" + LinearStatsFormat.humanBytes(tPacked)
                + " (" + LinearStatsFormat.savedPercent(tRaw, tPacked) + "% saved)";
        rows.add(String.format(
            "TOTALS: read=%d (%s) write=%d (%s) flush=%d (%s) load=%d files=%d fail=%d folders=%d  %s",
            tRead, LinearStatsFormat.humanMicros(tRead == 0 ? 0 : tReadMicros / tRead),
            tWrite, LinearStatsFormat.humanMicros(tWrite == 0 ? 0 : tWriteMicros / tWrite),
            tFlush, LinearStatsFormat.humanMicros(tFlush == 0 ? 0 : tFlushMicros / tFlush),
            tLoad, tFiles, tFail, snaps.size(), totalBytes));
        return rows;
    }

    /**
     * JDK-only folder snapshot (no coordinator types in any component).
     *
     * <p>Moved verbatim from the Paper leg's linearstats delegate: averages
     * are {@code total/count} with a divide-by-zero guard (count==0 yields
     * 0); folder identity reuses the shared {@link LinearFolderNames} helper
     * ({@code folderType} pinned to {@code region|poi|entities}).
     * Compression has no per-folder source in the coordinator snapshot, so
     * the mapping reports {@code compressionLevel = 0} (unknown), while byte,
     * latency and age fields ARE coordinator-sourced and mapped straight
     * through.</p>
     *
     * @param folderKey absolute folder-path key (as in coordinator snapshots())
     * @param folderType region|poi|entities (via LinearFolderNames)
     * @param worldName parent dir name (.../world/type)
     * @param reads read op count
     * @param readMicrosAvg avg read micros (total/count, 0 when reads==0)
     * @param writes write op count
     * @param writeMicrosAvg avg write micros (total/count, 0 when writes==0)
     * @param flushes flush op count
     * @param flushMicrosAvg avg flush micros (total/count, 0 when flushes==0)
     * @param compressionLevel zstd level, or 0=unknown (no coordinator source)
     * @param filesFlushed files successfully flushed
     * @param failures flush failures
     * @param dirtyNow current dirty depth (coordinator dirtyDepth)
     * @param rawBytes summed flush-image bytes, uncompressed (success-only)
     * @param compressedBytes summed flush-image bytes, zstd (success-only)
     * @param flushP50Micros bucketed p50 flush latency estimate, upper bound (clean-no-ops excluded, 0 when no flushes)
     * @param flushP99Micros bucketed p99 flush latency estimate, upper bound (clean-no-ops excluded, 0 when no flushes)
     * @param millisSinceLastFlush wall-millis since last success, -1 = never
     */
    public record FolderSnapshot(
        String folderKey,
        String folderType,
        String worldName,
        long reads,
        long readMicrosAvg,
        long writes,
        long writeMicrosAvg,
        long flushes,
        long flushMicrosAvg,
        int compressionLevel,
        long filesFlushed,
        long failures,
        int dirtyNow,
        long rawBytes,
        long compressedBytes,
        long flushP50Micros,
        long flushP99Micros,
        long millisSinceLastFlush
    ) {
    }

    /**
     * Pull-only view: one DTO per tracked Linear folder, in the map's
     * iteration order.
     *
     * @param snaps folder-keyed coordinator snapshots, must not be null
     * @return DTOs in the map's iteration order
     */
    public static List<FolderSnapshot> snapshots(
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps) {
        if (snaps == null) {
            throw new IllegalArgumentException("snaps must not be null");
        }
        List<FolderSnapshot> out = new ArrayList<>(snaps.size());
        for (Map.Entry<String, LinearRegionTimings.LinearFolderSnapshot> e : snaps.entrySet()) {
            out.add(toSnapshot(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * Single-folder lookup by absolute folder-path key.
     *
     * @param snaps folder-keyed coordinator snapshots, must not be null
     * @param folderKey absolute folder-path key (as in snapshots())
     * @return DTO when tracked, else empty
     */
    public static Optional<FolderSnapshot> snapshot(
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps, String folderKey) {
        if (snaps == null) {
            throw new IllegalArgumentException("snaps must not be null");
        }
        LinearRegionTimings.LinearFolderSnapshot s = snaps.get(folderKey);
        if (s == null) {
            return Optional.empty();
        }
        return Optional.of(toSnapshot(folderKey, s));
    }

    // Mapping: converts the coordinator record field by field;
    // avg = total/count with divide-by-zero guard.
    private static FolderSnapshot toSnapshot(
        final String folderKey, final LinearRegionTimings.LinearFolderSnapshot s) {
        long reads = s.read().count();
        long readAvg = s.read().count() == 0L ? 0L : s.read().totalMicros() / s.read().count();
        long writes = s.write().count();
        long writeAvg = s.write().count() == 0L ? 0L : s.write().totalMicros() / s.write().count();
        long flushes = s.flush().count();
        long flushAvg = s.flush().count() == 0L ? 0L : s.flush().totalMicros() / s.flush().count();
        String folderType = inferFolderType(folderKey);
        String worldName = inferWorld(folderKey);
        // No per-folder compression source in the coordinator snapshot; 0=unknown.
        int compressionLevel = 0;
        return new FolderSnapshot(
            folderKey,
            folderType,
            worldName,
            reads,
            readAvg,
            writes,
            writeAvg,
            flushes,
            flushAvg,
            compressionLevel,
            s.filesFlushed(),
            s.failures(),
            s.dirtyDepth(),
            s.rawBytes(),
            s.compressedBytes(),
            s.flushP50Micros(),
            s.flushP99Micros(),
            s.millisSinceLastFlush());
    }

    /**
     * Folder is {@code .../<world>/<region|poi|entities>}; the world is the
     * parent dir name. Same rule as the legacy linearstats panel
     * (delegates to the shared helper; null still rejected here).
     */
    public static String inferWorld(String folderKey) {
        if (folderKey == null) {
            throw new IllegalArgumentException("folderKey must not be null");
        }
        return LinearFolderNames.inferWorld(folderKey);
    }

    /** Folder type pinned to {@code region|poi|entities} by path suffix
     * (delegates to the shared helper; null still rejected here). */
    public static String inferFolderType(String folderKey) {
        if (folderKey == null) {
            throw new IllegalArgumentException("folderKey must not be null");
        }
        return LinearFolderNames.inferFolderType(folderKey);
    }
}
