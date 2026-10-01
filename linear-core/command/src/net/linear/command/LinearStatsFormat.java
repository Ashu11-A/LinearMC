package net.linear.command;

import java.util.Locale;

/**
 * JDK-only formatting helpers for the {@code /linearstats} panel, moved
 * conceptually from Paper {@code CommandLinearStats} so the view-model stays
 * NMS-free. Logic matches the legacy implementation, with one deliberate fix:
 * {@link Locale#ROOT} pins the decimal separator so output is identical on any
 * server locale (legacy used the default locale, yielding {@code 1,0KiB} on
 * e.g. German servers).
 */
public final class LinearStatsFormat {

    private LinearStatsFormat() {
    }

    public static String humanBytes(long bytes) {
        if (bytes < 0L) {
            return "—";
        }
        if (bytes < 1024L) {
            return bytes + "B";
        }
        double v = bytes / 1024.0;
        if (v < 1024.0) {
            return String.format(Locale.ROOT, "%.1fKiB", v);
        }
        v /= 1024.0;
        if (v < 1024.0) {
            return String.format(Locale.ROOT, "%.1fMiB", v);
        }
        v /= 1024.0;
        return String.format(Locale.ROOT, "%.2fGiB", v);
    }

    public static String humanMicros(long micros) {
        if (micros < 0L) {
            return "—";
        }
        if (micros < 1000L) {
            return micros + "us";
        }
        double ms = micros / 1000.0;
        if (ms < 1000.0) {
            return String.format(Locale.ROOT, "%.1fms", ms);
        }
        return String.format(Locale.ROOT, "%.2fs", ms / 1000.0);
    }

    public static long savedPercent(long raw, long packed) {
        if (raw <= 0L) {
            return 0L;
        }
        if (packed < 0L) {
            packed = 0L;
        }
        if (packed >= raw) {
            return 0L;
        }
        return (raw - packed) * 100L / raw;
    }

    public static String dirtyBar(int depth, int max) {
        final int width = 10;
        int filled = 0;
        if (max > 0 && depth > 0) {
            filled = (int) Math.round((double) depth / (double) max * width);
            if (filled < 1) {
                filled = 1;
            }
            if (filled > width) {
                filled = width;
            }
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? "█" : "░");
        }
        sb.append("] ").append(depth).append("/").append(max);
        return sb.toString();
    }

    /**
     * Age since the last flush, ported from Paper {@code CommandLinearStats}:
     * {@code never} when negative, {@code Nms} below a second, {@code N.Ns}
     * below a minute, else {@code Nm Ns}.
     */
    public static String humanMillisSince(long millis) {
        if (millis < 0L) {
            return "never";
        }
        if (millis < 1000L) {
            return millis + "ms";
        }
        if (millis < 60_000L) {
            return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
        }
        long m = millis / 60_000L;
        long s = (millis % 60_000L) / 1000L;
        return m + "m " + s + "s";
    }
}
