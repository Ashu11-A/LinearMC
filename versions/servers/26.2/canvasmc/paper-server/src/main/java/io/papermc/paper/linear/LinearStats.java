package io.papermc.paper.linear;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionTimings;
import net.linear.command.LinearStatsView;
import org.jetbrains.annotations.NotNull;

/**
 * Linear linearstats API delegate: JDK-only view for plugins.
 *
 * <p>Plugins must not import {@code net.linear} (NMS/coordinator types are
 * server-internal and may move). This class is the single supported entry
 * point: {@link #snapshots()} / {@link #snapshot(String)} return plain JDK
 * DTOs ({@link FolderSnapshot} holds only String/long/int). The shaping
 * (averages, folder identity, unknown compression level) is owned by core
 * {@link LinearStatsView}; this class only converts the core DTOs to the
 * plugin-stable record below, so the plugin API never moves.</p>
 *
 * <p>Pull-only, no threads, no storage fields, no NMS leaks: every public
 * signature uses JDK types only (String/List/Optional/primitives + this
 * DTO).</p>
 */
public final class LinearStats {

    private LinearStats() {
    }

    /**
     * JDK-only folder snapshot (no {@code net.linear} in any component).
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
     * @param compressionLevel zstd level, or 0=unknown (no coordinator source; see class javadoc)
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
        static @NotNull FolderSnapshot fromCore(final LinearStatsView.FolderSnapshot s) {
            return new FolderSnapshot(
                s.folderKey(),
                s.folderType(),
                s.worldName(),
                s.reads(),
                s.readMicrosAvg(),
                s.writes(),
                s.writeMicrosAvg(),
                s.flushes(),
                s.flushMicrosAvg(),
                s.compressionLevel(),
                s.filesFlushed(),
                s.failures(),
                s.dirtyNow(),
                s.rawBytes(),
                s.compressedBytes(),
                s.flushP50Micros(),
                s.flushP99Micros(),
                s.millisSinceLastFlush());
        }
    }

    /**
     * Pull-only view: one DTO per tracked Linear folder.
     *
     * @return snapshots in folder-key order as returned by the coordinator map
     */
    public static @NotNull List<FolderSnapshot> snapshots() {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps;
        try {
            snaps = LinearFlushCoordinator.snapshots();
        } catch (RuntimeException e) {
            return List.of();
        }
        List<FolderSnapshot> out = new ArrayList<>(snaps.size());
        for (LinearStatsView.FolderSnapshot s : LinearStatsView.snapshots(snaps)) {
            out.add(FolderSnapshot.fromCore(s));
        }
        return out;
    }

    /**
     * Single-folder lookup by absolute folder-path key.
     *
     * @param folderKey absolute folder-path key (as in snapshots())
     * @return DTO when tracked, else empty
     */
    public static @NotNull Optional<FolderSnapshot> snapshot(final @NotNull String folderKey) {
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps;
        try {
            snaps = LinearFlushCoordinator.snapshots();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return LinearStatsView.snapshot(snaps, folderKey).map(FolderSnapshot::fromCore);
    }
}
