package net.linear.command;

/**
 * Permission nodes and fixed player-facing lines for the {@code /linear} family.
 *
 * <p>Node values and the empty-state line are byte-exact legacy (pinned by
 * Paper {@code CommandLinearStats} and permissions docs); do not reword.
 */
public final class LinearPermissions {

    private LinearPermissions() {
    }

    /** Permission for {@code /linearstats}. */
    public static final String STATS = "linear.command.linearstats";
    /** Base permission for {@code /linear}. */
    public static final String BASE = "linear.command.linear";
    /** Permission for {@code /linear convert}. */
    public static final String CONVERT = "linear.command.convert";
    /** Permission for {@code /linear queue}. */
    public static final String QUEUE = "linear.command.queue";

    /** Empty-state line when no Linear folders are tracked yet (byte-exact legacy). */
    public static final String EMPTY_STATE =
        "linearstats: no Linear folders tracked (no linear I/O yet).";

    /**
     * Builds the denial message for a command label.
     *
     * @param label command label shown to the player (e.g. {@code linearstats})
     * @return {@code You do not have permission to use <label>.}
     */
    public static String denied(String label) {
        return "You do not have permission to use " + label + ".";
    }
}
