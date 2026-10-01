package net.linear.command;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.UUID;

import net.linear.LinearRegionTimings;

/**
 * Executes parsed {@link LinearAction}s against a {@link JobQueue} plus a
 * leg-provided {@link StatsProvider}. Pure JDK (no Bukkit): output goes
 * through {@link CommandSender#sendMessage}.
 */
public final class LinearCommandExecutor {

    private final JobQueue queue;
    private final StatsProvider stats;

    public LinearCommandExecutor(JobQueue queue, StatsProvider stats) {
        if (queue == null) {
            throw new IllegalArgumentException("queue must not be null");
        }
        if (stats == null) {
            throw new IllegalArgumentException("stats must not be null");
        }
        this.queue = queue;
        this.stats = stats;
    }

    /** Parses {@code args} then executes; usage errors go to the sender. */
    public void execute(CommandSender sender, String[] args) {
        final LinearAction action;
        try {
            action = new LinearCommandParser().parse(args);
        } catch (InvalidUsageException bad) {
            sender.sendMessage(bad.getMessage());
            return;
        }
        execute(sender, action);
    }

    /** Executes an already-parsed action. */
    public void execute(CommandSender sender, LinearAction action) {
        if (action instanceof LinearAction.HelpAction) {
            sender.sendMessage(LinearCommandParser.HELP_TEXT);
        } else if (action instanceof LinearAction.StatsAction s) {
            runStats(sender, s);
        } else if (action instanceof LinearAction.ConvertAction c) {
            runConvert(sender, c);
        } else if (action instanceof LinearAction.QueueAction q) {
            runQueue(sender, q);
        } else if (action instanceof LinearAction.StatusAction s) {
            runStatus(sender, s);
        } else {
            sender.sendMessage(LinearCommandParser.USAGE);
        }
    }

    private void runStats(CommandSender sender, LinearAction.StatsAction action) {
        if (!sender.hasPermission(LinearPermissions.STATS)
                && !sender.hasPermission(LinearPermissions.BASE)) {
            sender.sendMessage(LinearPermissions.denied("linearstats"));
            return;
        }
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = stats.snapshots();
        if (action.worldFilter().isPresent()) {
            String want = action.worldFilter().get();
            Map<String, LinearRegionTimings.LinearFolderSnapshot> filtered = new TreeMap<>();
            for (Map.Entry<String, LinearRegionTimings.LinearFolderSnapshot> e : snaps.entrySet()) {
                if (e.getKey().contains(want)) {
                    filtered.put(e.getKey(), e.getValue());
                }
            }
            snaps = filtered;
        }
        if (snaps.isEmpty()) {
            sender.sendMessage(LinearPermissions.EMPTY_STATE);
            return;
        }
        long files = 0;
        long fail = 0;
        for (LinearRegionTimings.LinearFolderSnapshot s : snaps.values()) {
            files += s.filesFlushed();
            fail += s.failures();
        }
        StringBuilder sb = new StringBuilder("Linear stats (" + snaps.size() + " folders)");
        action.worldFilter().ifPresent(w -> sb.append(" filter=").append(w));
        sb.append(": files=").append(files).append(" fail=").append(fail);
        sender.sendMessage(sb.toString());
    }

    private void runConvert(CommandSender sender, LinearAction.ConvertAction action) {
        if (!sender.hasPermission(LinearPermissions.CONVERT)
                && !sender.hasPermission(LinearPermissions.BASE)) {
            sender.sendMessage(LinearPermissions.denied("linear convert"));
            return;
        }
        ConvertJob job = queue.submit(new JobQueue.Spec(
            action.direction(), action.world(), action.level(),
            action.threads(), action.dryRun(), 0));
        if (action.dryRun()) {
            sender.sendMessage("Queued " + job.shortId() + " for " + action.world() + ": "
                + action.direction().plainDirection() + ". Practice run, nothing changed.");
        } else {
            sender.sendMessage("Queued " + job.shortId() + " for " + action.world() + ": "
                + action.direction().plainDirection() + ".");
        }
    }

    private void runQueue(CommandSender sender, LinearAction.QueueAction action) {
        if (!sender.hasPermission(LinearPermissions.QUEUE)
                && !sender.hasPermission(LinearPermissions.BASE)) {
            sender.sendMessage(LinearPermissions.denied("linear queue"));
            return;
        }
        switch (action.op()) {
            case LIST: {
                List<ConvertJob> jobs = queue.list();
                if (jobs.isEmpty()) {
                    sender.sendMessage("No jobs yet.");
                } else {
                    long nowMs = System.currentTimeMillis();
                    for (ConvertJob job : jobs) {
                        sender.sendMessage(JobStatusView.queueListLine(job, nowMs));
                    }
                }
                break;
            }
            case CLEAR: {
                int removed = queue.clearTerminal();
                sender.sendMessage("Cleared " + removed + " terminal convert job(s).");
                break;
            }
            case PAUSE:
            case RESUME:
            case CANCEL: {
                String ref = action.jobRef().orElseThrow(
                    () -> new InvalidUsageException("queue " + action.op() + " needs a job id. "
                        + LinearCommandParser.USAGE));
                ConvertJob job = resolveJob(ref);
                if (job == null) {
                    if (isAmbiguousPrefix(ref)) {
                        sender.sendMessage("Ambiguous id, use full id. See /linear queue list.");
                    } else {
                        sender.sendMessage("No job " + ref + ". See /linear queue list.");
                    }
                    break;
                }
                UUID id = job.id();
                try {
                    switch (action.op()) {
                        case PAUSE:
                            queue.pause(id);
                            break;
                        case RESUME:
                            queue.resume(id);
                            break;
                        default:
                            queue.cancel(id);
                            break;
                    }
                    sender.sendMessage("Job " + id + " " + pastTense(action.op()) + ".");
                } catch (NoSuchElementException | IllegalStateException bad) {
                    sender.sendMessage(bad.getMessage());
                }
                break;
            }
            default:
                sender.sendMessage(LinearCommandParser.USAGE);
                break;
        }
    }

    private void runStatus(CommandSender sender, LinearAction.StatusAction action) {
        if (!sender.hasPermission(LinearPermissions.QUEUE)
                && !sender.hasPermission(LinearPermissions.BASE)) {
            sender.sendMessage(LinearPermissions.denied("linear queue"));
            return;
        }
        ConvertJob job = resolveJob(action.jobRef());
        if (job == null) {
            if (isAmbiguousPrefix(action.jobRef())) {
                sender.sendMessage("Ambiguous id, use full id. See /linear queue list.");
            } else {
                sender.sendMessage("No job " + action.jobRef() + ". See /linear queue list.");
            }
            return;
        }
        long nowMs = System.currentTimeMillis();
        sender.sendMessage(JobStatusView.statusLine(job, nowMs));
        for (String line : JobStatusView.detailLines(job, nowMs)) {
            sender.sendMessage(line);
        }
    }

    /** Resolves a full id or an 8-char short id to its job, or {@code null}. */
    private ConvertJob resolveJob(String ref) {
        if (ref == null) {
            return null;
        }
        String trimmed = ref.trim();
        try {
            UUID id = UUID.fromString(trimmed);
            return queue.get(id);
        } catch (IllegalArgumentException notUuid) {
            return queue.findByPrefix(trimmed);
        }
    }

    private boolean isAmbiguousPrefix(String ref) {
        if (ref == null) {
            return false;
        }
        String trimmed = ref.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            UUID.fromString(trimmed);
            return false;
        } catch (IllegalArgumentException notUuid) {
            // Below JobQueue.MIN_PREFIX_LENGTH the lookup can never resolve
            // (fail-closed null), so report it as ambiguous/use-full-id rather
            // than no-job: the prefix carries too little entropy to identify.
            if (trimmed.length() < JobQueue.MIN_PREFIX_LENGTH) {
                return true;
            }
            return queue.countByPrefix(trimmed) > 1;
        }
    }

    private static String pastTense(QueueOp op) {
        switch (op) {
            case PAUSE:
                return "paused";
            case RESUME:
                return "resumed";
            case CANCEL:
                return "cancelled";
            default:
                return op.name().toLowerCase();
        }
    }
}
