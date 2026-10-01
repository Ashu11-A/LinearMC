package net.linear.command;

import java.util.Map;

import net.linear.LinearRegionTimings;

/**
 * Leg-agnostic snapshot source for the {@code /linear stats} panel. Legs
 * (Paper, Horizon) implement it by delegating to their flush coordinator;
 * the executor stays NMS-free.
 */
public interface StatsProvider {

    /** Folder-keyed snapshots, empty when no Linear I/O has run yet. */
    Map<String, LinearRegionTimings.LinearFolderSnapshot> snapshots();
}
