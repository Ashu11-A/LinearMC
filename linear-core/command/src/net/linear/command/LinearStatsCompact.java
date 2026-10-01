package net.linear.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionTimings;

/**
 * Compact color-coded {@code /linearstats} view (selected proposal A).
 *
 * <p>One row per folder with an inline world tag, e.g.
 * {@code world L6 reg d12/512 f0 p99 12.3ms 42% 3.1s W4.2s}:
 * world + level + short type + dirty (with {@code /512} suffix) +
 * failures + p99 + saved% + per-folder age + world age ({@code W} prefix).
 * 3 worlds x 3 types render as 11 lines (header + 9 folders + TOTALS),
 * every realistic line is {@code <=55} chars (no printf-aligned columns:
 * Minecraft chat is proportional, so alignment would not hold visually).
 * The world tag is truncated to {@value #WORLD_WIDTH} chars (no ellipsis)
 * so unbounded world names cannot overflow chat width.
 *
 * <p>JDK-only: no colour codes here. Each folder row carries a per-metric
 * health map ({@code dirty}/{@code fail}/{@code p99}/{@code saved};
 * {@code saved} is absent when {@code raw==0} so legs render {@code na}
 * in gray) plus pre-split display parts so legs can colour segments
 * (worlds/numbers blue, metrics by health) without re-parsing text.
 * Legacy {@link LinearStatsView#render} is untouched.
 */
public final class LinearStatsCompact {

    /** Fixed display width of the world tag (truncated, no ellipsis). */
    static final int WORLD_WIDTH = 8;

    /** Row kind so legs can colour header/totals without parsing text. */
    public enum RowKind {
        EMPTY,
        HEADER,
        FOLDER,
        TOTALS
    }

    /**
     * One compact row: plain {@code text} plus per-metric health and
     * pre-split display {@code parts} for segment colouring.
     *
     * @param text plain row text (no colour codes)
     * @param health per-metric health ({@code dirty}/{@code fail}/
     *     {@code p99}/{@code saved}; {@code saved} absent means
     *     {@code na}, legs use gray; header/empty rows carry an empty map)
     * @param parts display segments ({@code world}/{@code level}/
     *     {@code type}/{@code dirty}/{@code fail}/{@code p99}/
     *     {@code saved}/{@code age}/{@code wage} for folders;
     *     {@code files}/{@code fail}/{@code saved} for totals)
     * @param kind row kind for header/totals styling
     */
    public record CompactRow(
        String text,
        Map<String, LinearStatsHealth> health,
        Map<String, String> parts,
        RowKind kind
    ) {
    }

    private LinearStatsCompact() {
    }

    /** Compact render with unknown levels ({@code L?}). */
    public static List<CompactRow> renderCompact(
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snapshots,
        Optional<String> worldFilter) {
        return renderCompact(snapshots, worldFilter, world -> -1);
    }

    /**
     * Compact render.
     *
     * @param snapshots folder-keyed coordinator snapshots, must not be null
     * @param worldFilter substring match on the folder key when present,
     *     must not be null
     * @param levelForWorld per-world compression level ({@code <0} renders
     *     {@code L?}), must not be null
     */
    public static List<CompactRow> renderCompact(
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snapshots,
        Optional<String> worldFilter,
        ToIntFunction<String> levelForWorld) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (worldFilter == null) {
            throw new IllegalArgumentException("worldFilter must not be null");
        }
        if (levelForWorld == null) {
            throw new IllegalArgumentException("levelForWorld must not be null");
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
        List<CompactRow> rows = new ArrayList<>();
        if (snaps.isEmpty()) {
            rows.add(new CompactRow(LinearPermissions.EMPTY_STATE,
                Map.of(), Map.of(), RowKind.EMPTY));
            return rows;
        }
        Map<String, Map<String, LinearRegionTimings.LinearFolderSnapshot>> byWorld = new TreeMap<>();
        for (Map.Entry<String, LinearRegionTimings.LinearFolderSnapshot> e : snaps.entrySet()) {
            byWorld.computeIfAbsent(LinearStatsView.inferWorld(e.getKey()), k -> new TreeMap<>())
                .put(LinearStatsView.inferFolderType(e.getKey()), e.getValue());
        }
        long tFiles = 0;
        long tFail = 0;
        long tRaw = 0;
        long tPacked = 0;

        StringBuilder head = new StringBuilder("Linear stats (" + snaps.size() + " folders)");
        worldFilter.ifPresent(w -> head.append(" filter=").append(w));
        rows.add(new CompactRow(head.toString(), Map.of(), Map.of(), RowKind.HEADER));

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
            String wAge = anyFlushed ? LinearStatsFormat.humanMillisSince(newestSince) : "never";
            int level;
            try {
                level = levelForWorld.applyAsInt(world);
            } catch (RuntimeException e) {
                level = -1;
            }
            String levelLabel = level < 0 ? "?" : String.valueOf(level);
            String displayWorld = displayWorld(world);
            for (Map.Entry<String, LinearRegionTimings.LinearFolderSnapshot> fe
                : we.getValue().entrySet()) {
                LinearRegionTimings.LinearFolderSnapshot s = fe.getValue();
                String shortType = shortType(fe.getKey());
                String p99 = LinearStatsFormat.humanMicros(s.flushP99Micros());
                String saved = s.rawBytes() <= 0L
                    ? "na"
                    : LinearStatsFormat.savedPercent(s.rawBytes(), s.compressedBytes()) + "%";
                String age = LinearStatsFormat.humanMillisSince(s.millisSinceLastFlush());
                String text = displayWorld + " L" + levelLabel + " " + shortType
                    + " d" + s.dirtyDepth() + "/" + LinearFlushCoordinator.MAX_DIRTY
                    + " f" + s.failures()
                    + " p99 " + p99 + " " + saved + " " + age + " W" + wAge;

                Map<String, LinearStatsHealth> health = new LinkedHashMap<>();
                health.put("dirty", LinearStatsStatus.dirtyHealth(s.dirtyDepth()));
                health.put("fail", LinearStatsStatus.failuresHealth(s.failures()));
                health.put("p99", LinearStatsStatus.p99Health(s.flushP99Micros()));
                LinearStatsHealth savedH =
                    LinearStatsStatus.savedHealth(s.rawBytes(), s.compressedBytes());
                if (savedH != null) {
                    health.put("saved", savedH);
                }
                Map<String, String> parts = new LinkedHashMap<>();
                parts.put("world", displayWorld);
                parts.put("level", levelLabel);
                parts.put("type", shortType);
                parts.put("dirty", "d" + s.dirtyDepth() + "/" + LinearFlushCoordinator.MAX_DIRTY);
                parts.put("fail", "f" + s.failures());
                parts.put("p99", "p99 " + p99);
                parts.put("saved", saved);
                parts.put("age", age);
                parts.put("wage", "W" + wAge);
                rows.add(new CompactRow(text, Map.copyOf(health), Map.copyOf(parts), RowKind.FOLDER));

                tFiles += s.filesFlushed();
                tFail += s.failures();
                tRaw += s.rawBytes();
                tPacked += s.compressedBytes();
            }
        }
        String savedTotal = tRaw <= 0L
            ? "na"
            : LinearStatsFormat.savedPercent(tRaw, tPacked) + "%";
        String totalText =
            "TOTALS files=" + tFiles + " fail=" + tFail + " " + savedTotal + " " + snaps.size() + " folders";
        Map<String, LinearStatsHealth> totalHealth = new LinkedHashMap<>();
        totalHealth.put("fail", LinearStatsStatus.failuresHealth(tFail));
        LinearStatsHealth totalSaved = LinearStatsStatus.savedHealth(tRaw, tPacked);
        if (totalSaved != null) {
            totalHealth.put("saved", totalSaved);
        }
        Map<String, String> totalParts = new LinkedHashMap<>();
        totalParts.put("files", "files=" + tFiles);
        totalParts.put("fail", "fail=" + tFail);
        totalParts.put("saved", savedTotal);
        rows.add(new CompactRow(totalText, Map.copyOf(totalHealth),
            Map.copyOf(totalParts), RowKind.TOTALS));
        return rows;
    }

    static String shortType(String folderType) {
        if ("entities".equals(folderType)) {
            return "ent";
        }
        if ("region".equals(folderType)) {
            return "reg";
        }
        return folderType;
    }

    /** Truncates the world tag to {@value #WORLD_WIDTH} chars (no ellipsis). */
    static String displayWorld(String world) {
        if (world == null) {
            return "";
        }
        return world.length() <= WORLD_WIDTH ? world : world.substring(0, WORLD_WIDTH);
    }
}
