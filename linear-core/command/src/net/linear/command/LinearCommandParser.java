package net.linear.command;

import java.util.Locale;
import java.util.Optional;

/**
 * Parses {@code String[]} args into the sealed {@link LinearAction}
 * hierarchy. Pure JDK (no Bukkit).
 *
 * <p>Grammar:
 * <pre>
 * /linear stats [world]
 * /linear convert &lt;world&gt; [--to-linear | --to-mca] [--level N] [--threads N] [--execute | --dry-run]
 * /linear queue list
 * /linear queue status &lt;jobId&gt;
 * /linear queue clear
 * /linear queue pause|resume|cancel &lt;jobId&gt;
 * /linear help
 * </pre>
 * Convert defaults: direction {@code MCA_TO_LINEAR}, level 6, threads 1,
 * dry-run (pass {@code --execute} to convert for real).
 */
public final class LinearCommandParser {

    /** One-line usage, appended to every {@link InvalidUsageException}. */
    public static final String USAGE =
        "Usage: /linear stats [world] | /linear convert <world> [--to-linear|--to-mca] "
            + "[--level 1..22] [--threads N] [--execute|--dry-run] | /linear queue list|status|clear|pause|resume|cancel [jobId] | /linear help";

    /** Human-readable help, shown for {@code /linear help} and empty args. */
    public static final String HELP_TEXT =
        "Linear in plain words:\n"
            + "  /linear stats [world] - See how your saves are doing.\n"
            + "  /linear convert <world> - Copy old saves to new faster saves."
            + " Practice run, nothing changed, unless you add --execute.\n"
            + " Add --to-mca to copy new saves back to old.\n"
            + "  /linear queue list - See all jobs and how far each one got.\n"
            + "  /linear queue status <id> - See one job in plain words."
            + " The first 8 letters of the id are enough.\n"
            + "  /linear queue pause|resume|cancel <id> - Hold, restart, or stop a job."
            + " The first 8 letters of the id are enough.\n"
            + "  /linear queue clear - Forget jobs that are done.\n"
            + "  /linear help - Show this help.\n"
            + "Your old files are still there until a job finishes.";

    /**
     * Parses the args (excluding the leading {@code /linear} label).
     *
     * @throws InvalidUsageException with the usage text when the args do not match
     */
    public LinearAction parse(String[] args) throws InvalidUsageException {
        if (args == null || args.length == 0 || isHelp(args[0])) {
            if (args != null && args.length > 1) {
                throw usage("too many args for help");
            }
            return new LinearAction.HelpAction();
        }
        String head = args[0].toLowerCase(Locale.ROOT);
        switch (head) {
            case "stats": {
                if (args.length > 2) {
                    throw usage("stats takes at most one world filter");
                }
                Optional<String> filter = args.length == 2
                    ? Optional.of(requireToken(args[1], "world"))
                    : Optional.empty();
                return new LinearAction.StatsAction(filter);
            }
            case "convert":
                return parseConvert(args);
            case "queue":
                return parseQueue(args);
            default:
                throw usage("unknown subcommand '" + args[0] + "'");
        }
    }

    private LinearAction.ConvertAction parseConvert(String[] args) {
        if (args.length < 2) {
            throw usage("convert needs a world");
        }
        String world = requireToken(args[1], "world");
        ConvertDirection direction = ConvertDirection.MCA_TO_LINEAR;
        int level = 6;
        int threads = 1;
        boolean dryRun = true;
        for (int i = 2; i < args.length; i++) {
            String flag = args[i].toLowerCase(Locale.ROOT);
            switch (flag) {
                case "--to-linear":
                    direction = ConvertDirection.MCA_TO_LINEAR;
                    break;
                case "--to-mca":
                    direction = ConvertDirection.LINEAR_TO_MCA;
                    break;
                case "--execute":
                    dryRun = false;
                    break;
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--level": {
                    String raw = nextValue(args, ++i, "--level");
                    level = parseIntInRange(raw, "--level", 1, 22);
                    break;
                }
                case "--threads": {
                    String raw = nextValue(args, ++i, "--threads");
                    threads = parseIntAtLeast(raw, "--threads", 1);
                    break;
                }
                default:
                    throw usage("unknown convert flag '" + args[i] + "'");
            }
        }
        return new LinearAction.ConvertAction(direction, world, level, threads, dryRun);
    }

    private LinearAction parseQueue(String[] args) {
        if (args.length < 2) {
            throw usage("queue needs an op: list|status|clear|pause|resume|cancel");
        }
        QueueOp op;
        try {
            op = QueueOp.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException bad) {
            throw usage("unknown queue op '" + args[1] + "'");
        }
        switch (op) {
            case LIST:
            case CLEAR: {
                if (args.length > 2) {
                    throw usage("queue " + op.name().toLowerCase(Locale.ROOT) + " takes no job id");
                }
                return new LinearAction.QueueAction(op, Optional.empty());
            }
            case STATUS: {
                if (args.length != 3) {
                    throw usage("queue status needs a job id");
                }
                String ref = args[2] == null ? "" : args[2].trim();
                if (ref.isEmpty()) {
                    throw usage("queue status needs a job id");
                }
                return new LinearAction.StatusAction(ref);
            }
            default: {
                if (args.length != 3) {
                    throw usage("queue " + op.name().toLowerCase(Locale.ROOT) + " needs a job id");
                }
                String ref = args[2] == null ? "" : args[2].trim();
                if (ref.isEmpty()) {
                    throw usage("queue " + op.name().toLowerCase(Locale.ROOT) + " needs a job id");
                }
                return new LinearAction.QueueAction(op, Optional.of(ref));
            }
        }
    }

    private static String requireToken(String raw, String what) {
        if (raw == null || raw.trim().isEmpty()) {
            throw usage("missing " + what);
        }
        return raw.trim();
    }

    private static String nextValue(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw usage(flag + " needs a value");
        }
        return args[i];
    }

    private static int parseIntInRange(String raw, String flag, int min, int max) {
        final int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException bad) {
            throw usage(flag + " must be " + min + ".." + max + ", got '" + raw + "'");
        }
        if (v < min || v > max) {
            throw usage(flag + " must be " + min + ".." + max + ", got '" + raw + "'");
        }
        return v;
    }

    private static int parseIntAtLeast(String raw, String flag, int min) {
        final int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException bad) {
            throw usage(flag + " must be >= " + min + ", got '" + raw + "'");
        }
        if (v < min) {
            throw usage(flag + " must be >= " + min + ", got '" + raw + "'");
        }
        return v;
    }

    private static boolean isHelp(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("help") || t.equals("-h") || t.equals("--help") || t.equals("?");
    }

    private static InvalidUsageException usage(String detail) {
        return new InvalidUsageException(detail + ". " + USAGE + "\n" + HELP_TEXT);
    }
}
