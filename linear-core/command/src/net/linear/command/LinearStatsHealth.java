package net.linear.command;

/**
 * Health signal for a single {@code /linearstats} metric.
 *
 * <p>JDK-only: the core maps raw counters to health; legs map
 * health to colours (Adventure/ChatColor). No colour codes in core.
 */
public enum LinearStatsHealth {
    OK,
    WARN,
    CRIT
}
