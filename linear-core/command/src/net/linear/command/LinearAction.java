package net.linear.command;

import java.util.Optional;

/**
 * Parsed form of the {@code /linear} family. Pure JDK (no Bukkit): legs map
 * their native sender/args onto {@link LinearCommandParser} and
 * {@link LinearCommandExecutor}.
 */
public sealed interface LinearAction
    permits LinearAction.StatsAction, LinearAction.ConvertAction,
        LinearAction.QueueAction, LinearAction.StatusAction, LinearAction.HelpAction {

    /** {@code /linear stats [world]}: panel for one world or all worlds. */
    record StatsAction(Optional<String> worldFilter) implements LinearAction {
        public StatsAction {
            if (worldFilter == null) {
                throw new IllegalArgumentException("worldFilter must not be null");
            }
        }
    }

    /**
     * {@code /linear convert <world> [options]}: one conversion job.
     * Dry-run by default; {@code --execute} performs the conversion.
     */
    record ConvertAction(ConvertDirection direction, String world, int level,
        int threads, boolean dryRun) implements LinearAction {
        public ConvertAction {
            if (direction == null) {
                throw new IllegalArgumentException("direction must not be null");
            }
            if (world == null) {
                throw new IllegalArgumentException("world must not be null");
            }
            if (level < 1 || level > 22) {
                throw new IllegalArgumentException("level must be 1..22, got " + level);
            }
            if (threads < 1) {
                throw new IllegalArgumentException("threads must be >= 1, got " + threads);
            }
        }
    }

    /** {@code /linear queue <op> [jobId]}: inspect or mutate queued jobs (8-char prefix ok). */
    record QueueAction(QueueOp op, Optional<String> jobRef) implements LinearAction {
        public QueueAction {
            if (op == null) {
                throw new IllegalArgumentException("op must not be null");
            }
            if (jobRef == null) {
                throw new IllegalArgumentException("jobRef must not be null");
            }
        }
    }

    /** {@code /linear queue status <id>}: one job in plain words (8-char prefix ok). */
    record StatusAction(String jobRef) implements LinearAction {
        public StatusAction {
            if (jobRef == null || jobRef.trim().isEmpty()) {
                throw new IllegalArgumentException("jobRef must not be blank");
            }
        }
    }

    /** {@code /linear help} (also the empty-args default). */
    record HelpAction() implements LinearAction {
    }
}
