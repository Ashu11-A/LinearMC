package net.linear.command;

/**
 * JDK-only classifier for the compact {@code /linearstats} view.
 *
 * <p>Boundaries (pinned by tests):
 * <ul>
 *   <li>dirty: {@code <=50 OK}, {@code 51-256 WARN}, {@code >256 CRIT}
 *       ({@code MAX_DIRTY=512}; WARN spans half the bound).</li>
 *   <li>failures: {@code 0 OK}, {@code 1 WARN}, {@code >=2 CRIT}.</li>
 *   <li>p99 micros: {@code <100ms OK}, {@code 100-500ms WARN},
 *       {@code >500ms CRIT}; negative (unknown) reports OK so empty
 *       panels stay quiet.</li>
 *   <li>saved: {@code raw<=0} returns {@code null} (no bytes yet, legs
 *       render {@code na} in gray); {@code 0% with raw>0} is CRIT;
 *       {@code 25-70%} OK, anything else WARN.</li>
 * </ul>
 */
public final class LinearStatsStatus {

    private LinearStatsStatus() {
    }

    public static LinearStatsHealth dirtyHealth(int depth) {
        if (depth > 256) {
            return LinearStatsHealth.CRIT;
        }
        if (depth > 50) {
            return LinearStatsHealth.WARN;
        }
        return LinearStatsHealth.OK;
    }

    public static LinearStatsHealth failuresHealth(long failures) {
        if (failures >= 2L) {
            return LinearStatsHealth.CRIT;
        }
        if (failures == 1L) {
            return LinearStatsHealth.WARN;
        }
        return LinearStatsHealth.OK;
    }

    public static LinearStatsHealth p99Health(long p99Micros) {
        if (p99Micros > 500_000L) {
            return LinearStatsHealth.CRIT;
        }
        if (p99Micros >= 100_000L) {
            return LinearStatsHealth.WARN;
        }
        return LinearStatsHealth.OK;
    }

    /**
     * Saved-percent health, or {@code null} when {@code raw<=0}
     * (no bytes yet; legs render {@code na} in gray).
     */
    public static LinearStatsHealth savedHealth(long rawBytes, long packedBytes) {
        if (rawBytes <= 0L) {
            return null;
        }
        long pct = LinearStatsFormat.savedPercent(rawBytes, packedBytes);
        if (pct <= 0L) {
            return LinearStatsHealth.CRIT;
        }
        if (pct >= 25L && pct <= 70L) {
            return LinearStatsHealth.OK;
        }
        return LinearStatsHealth.WARN;
    }

    /** Worst of the given signals (CRIT wins, then WARN, else OK). */
    public static LinearStatsHealth worst(LinearStatsHealth... signals) {
        LinearStatsHealth out = LinearStatsHealth.OK;
        if (signals == null) {
            return out;
        }
        for (LinearStatsHealth s : signals) {
            if (s == LinearStatsHealth.CRIT) {
                return LinearStatsHealth.CRIT;
            }
            if (s == LinearStatsHealth.WARN) {
                out = LinearStatsHealth.WARN;
            }
        }
        return out;
    }
}
