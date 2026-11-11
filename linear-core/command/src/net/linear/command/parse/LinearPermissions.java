package net.linear.command.parse;

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

    /**
     * Command surfaces guarded by {@link #allows}. One rule for every leg
     * (Folia fork, Canvas fork, Horizon plugin), documented in
     * {@code docs/commands.md}; legs must not register their own
     * command-level node or check nodes by hand.
     */
    public enum Action {
        /** {@code /linearstats [filter]}: {@link #STATS} only. */
        LINEARSTATS,
        /** {@code /linear stats [world]}: {@link #STATS} or {@link #BASE}. */
        LINEAR_STATS,
        /** {@code /linear convert ...}: {@link #CONVERT} or {@link #BASE}. */
        CONVERT,
        /** {@code /linear queue ...}: {@link #QUEUE} or {@link #BASE}. */
        QUEUE,
        /**
         * {@code /linear} itself and {@code /linear help}: any Linear node,
         * so a holder of only {@link #CONVERT} or {@link #QUEUE} can reach
         * their subcommand (never gate the root on {@link #BASE}).
         */
        ROOT
    }

    /**
     * The single permission rule.
     *
     * @param has the sender's permission test
     * @param action the surface being used
     * @return whether the sender may use it
     */
    public static boolean allows(java.util.function.Predicate<String> has, Action action) {
        switch (action) {
            case LINEARSTATS:
                return has.test(STATS);
            case LINEAR_STATS:
                return has.test(STATS) || has.test(BASE);
            case CONVERT:
                return has.test(CONVERT) || has.test(BASE);
            case QUEUE:
                return has.test(QUEUE) || has.test(BASE);
            case ROOT:
            default:
                return has.test(BASE) || has.test(CONVERT) || has.test(QUEUE) || has.test(STATS);
        }
    }

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
