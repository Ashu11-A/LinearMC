package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.linear.command.CommandSender;
import net.linear.command.ConvertDirection;
import net.linear.command.ConvertJob;
import net.linear.command.InvalidUsageException;
import net.linear.command.JobQueue;
import net.linear.command.LinearAction;
import net.linear.command.LinearCommandExecutor;
import net.linear.command.LinearCommandParser;
import net.linear.command.LinearPermissions;
import net.linear.command.QueueOp;
import net.linear.command.StatsProvider;
import net.linear.config.LinearPolicy;

import org.junit.jupiter.api.Test;

/**
 * {@code /linear} command binding: parser grammar plus executor smoke
 * against fake sender/stats (no Bukkit, no NMS bootstrap — pure
 * {@code net.linear.command} plus the {@code LinearPolicy} precedence the
 * Canvas startup gate relies on).
 */
public class LinearCommandBindingTest {

    private static final class FakeSender implements CommandSender {
        final List<String> lines = new ArrayList<>();
        final Set<String> perms;

        FakeSender(Set<String> perms) {
            this.perms = perms;
        }

        @Override
        public void sendMessage(String message) {
            this.lines.add(message);
        }

        @Override
        public boolean hasPermission(String permission) {
            return this.perms.contains(permission);
        }

        @Override
        public String name() {
            return "test";
        }

        String joined() {
            return String.join("\n", this.lines);
        }
    }

    private static final Set<String> ALL = Set.of(
        LinearPermissions.BASE, LinearPermissions.STATS,
        LinearPermissions.CONVERT, LinearPermissions.QUEUE);

    private static LinearCommandExecutor executor(JobQueue queue, Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps) {
        final StatsProvider stats = () -> new HashMap<>(snaps);
        return new LinearCommandExecutor(queue, stats);
    }

    // --- parser ---

    @Test
    public void emptyArgsAreHelp() {
        assertInstanceOf(LinearAction.HelpAction.class,
            new LinearCommandParser().parse(new String[]{}));
        assertInstanceOf(LinearAction.HelpAction.class,
            new LinearCommandParser().parse(new String[]{"help"}));
    }

    @Test
    public void statsParsesWithOptionalFilter() {
        final LinearAction bare = new LinearCommandParser().parse(new String[]{"stats"});
        assertInstanceOf(LinearAction.StatsAction.class, bare);
        assertTrue(((LinearAction.StatsAction) bare).worldFilter().isEmpty());

        final LinearAction filtered = new LinearCommandParser().parse(new String[]{"stats", "world"});
        assertEquals("world",
            ((LinearAction.StatsAction) filtered).worldFilter().orElseThrow());
    }

    @Test
    public void convertDefaultsToDryRunMcaToLinear() {
        final LinearAction action =
            new LinearCommandParser().parse(new String[]{"convert", "world"});
        assertInstanceOf(LinearAction.ConvertAction.class, action);
        final LinearAction.ConvertAction convert = (LinearAction.ConvertAction) action;
        assertEquals(ConvertDirection.MCA_TO_LINEAR, convert.direction());
        assertEquals("world", convert.world());
        assertEquals(6, convert.level());
        assertEquals(1, convert.threads());
        assertTrue(convert.dryRun());
    }

    @Test
    public void convertParsesFullFlags() {
        final LinearAction action = new LinearCommandParser().parse(new String[]{
            "convert", "nether", "--to-mca", "--level", "9", "--threads", "4", "--execute"});
        final LinearAction.ConvertAction convert = (LinearAction.ConvertAction) action;
        assertEquals(ConvertDirection.LINEAR_TO_MCA, convert.direction());
        assertEquals(9, convert.level());
        assertEquals(4, convert.threads());
        assertFalse(convert.dryRun());
    }

    @Test
    public void queueParsesOps() {
        final LinearAction list =
            new LinearCommandParser().parse(new String[]{"queue", "list"});
        assertEquals(QueueOp.LIST, ((LinearAction.QueueAction) list).op());

        final ConvertJob probe = new JobQueue().submit(new JobQueue.Spec(
            ConvertDirection.MCA_TO_LINEAR, "w", 6, 1, true, 0));
        final LinearAction pause = new LinearCommandParser().parse(
            new String[]{"queue", "pause", probe.id().toString()});
        assertEquals(QueueOp.PAUSE, ((LinearAction.QueueAction) pause).op());
        assertEquals(probe.id().toString(),
            ((LinearAction.QueueAction) pause).jobRef().orElseThrow());
    }

    @Test
    public void rejectsBadArgsWithUsage() {
        final String[][] bad = {
            {"bogus"},
            {"convert"},
            {"convert", "w", "--level", "99"},
            {"convert", "w", "--nope"},
            {"queue"},
            {"queue", "pause", "  "},
            {"stats", "a", "b"},
        };
        for (String[] args : bad) {
            final InvalidUsageException thrown = assertThrows(InvalidUsageException.class,
                () -> new LinearCommandParser().parse(args),
                "must reject: " + String.join(" ", args));
            assertTrue(thrown.getMessage().contains("Usage:"),
                "usage text must ride along: " + thrown.getMessage());
        }
    }

    // --- executor smoke (fake sender + fake stats) ---

    @Test
    public void statsEmptyStateMatchesLegacyLine() {
        final JobQueue queue = new JobQueue();
        final FakeSender sender = new FakeSender(ALL);
        executor(queue, Map.of()).execute(sender, new String[]{"stats"});
        assertEquals(1, sender.lines.size());
        assertEquals(LinearPermissions.EMPTY_STATE, sender.lines.get(0));
    }

    @Test
    public void statsSummaryCountsFolders() {
        final JobQueue queue = new JobQueue();
        final FakeSender sender = new FakeSender(ALL);
        executor(queue, Map.of(
            "world/region", LinearRegionTimings.LinearFolderSnapshot.EMPTY,
            "world/poi", LinearRegionTimings.LinearFolderSnapshot.EMPTY))
            .execute(sender, new String[]{"stats"});
        assertTrue(sender.joined().contains("2 folders"), sender.joined());
    }

    @Test
    public void statsWorldFilterNarrows() {
        final JobQueue queue = new JobQueue();
        final FakeSender sender = new FakeSender(ALL);
        executor(queue, Map.of(
            "world/region", LinearRegionTimings.LinearFolderSnapshot.EMPTY,
            "other/region", LinearRegionTimings.LinearFolderSnapshot.EMPTY))
            .execute(sender, new String[]{"stats", "world"});
        assertTrue(sender.joined().contains("1 folders"), sender.joined());
        assertTrue(sender.joined().contains("filter=world"), sender.joined());
    }

    @Test
    public void convertDryRunPlansOnly() {
        final JobQueue queue = new JobQueue();
        final FakeSender sender = new FakeSender(ALL);
        executor(queue, Map.of()).execute(sender, new String[]{"convert", "world"});
        assertTrue(sender.joined().contains("Practice run, nothing changed"), sender.joined());
        assertEquals(1, queue.list().size(), "dry run still records the plan");
        final ConvertJob job = queue.list().get(0);
        assertTrue(job.dryRun());
        assertEquals(ConvertDirection.MCA_TO_LINEAR, job.direction());
    }

    @Test
    public void convertReverseDryRunPlansOnly() {
        final JobQueue queue = new JobQueue();
        final FakeSender sender = new FakeSender(ALL);
        executor(queue, Map.of()).execute(sender, new String[]{"convert", "world", "--to-mca"});
        assertTrue(sender.joined().contains("Practice run"), sender.joined());
        assertEquals(1, queue.list().size(), "reverse dry run still records the plan");
        final ConvertJob job = queue.list().get(0);
        assertTrue(job.dryRun());
        assertEquals(ConvertDirection.LINEAR_TO_MCA, job.direction());
    }

    @Test
    public void queueListCancelClear() {
        final JobQueue queue = new JobQueue();
        final FakeSender sender = new FakeSender(ALL);
        final LinearCommandExecutor exec = executor(queue, Map.of());
        exec.execute(sender, new String[]{"convert", "world"});
        final ConvertJob job = queue.list().get(0);

        sender.lines.clear();
        exec.execute(sender, new String[]{"queue", "list"});
        assertTrue(sender.joined().contains(job.id().toString().substring(0, 8)), sender.joined());

        sender.lines.clear();
        exec.execute(sender, new String[]{"queue", "pause", job.id().toString()});
        assertTrue(sender.joined().contains("Cannot pause"),
            "QUEUED job cannot pause: " + sender.joined());

        sender.lines.clear();
        exec.execute(sender, new String[]{"queue", "cancel", job.id().toString()});
        assertTrue(sender.joined().contains("cancelled"), sender.joined());

        sender.lines.clear();
        exec.execute(sender, new String[]{"queue", "clear"});
        assertTrue(sender.joined().contains("Cleared 1"), sender.joined());
        assertTrue(queue.list().isEmpty());
    }

    @Test
    public void deniedWithoutPermission() {
        final JobQueue queue = new JobQueue();
        final FakeSender sender = new FakeSender(Set.of());
        executor(queue, Map.of()).execute(sender, new String[]{"convert", "world"});
        assertTrue(sender.joined().contains("do not have permission"), sender.joined());
        assertTrue(queue.list().isEmpty(), "denied submit must not enqueue");
    }

    @Test
    public void helpShowsHelpText() {
        final JobQueue queue = new JobQueue();
        final FakeSender sender = new FakeSender(ALL);
        executor(queue, Map.of()).execute(sender, new String[]{"help"});
        assertTrue(sender.joined().contains("/linear convert"), sender.joined());
    }

    @Test
    public void restartDropsAreNamed() {
        assertTrue(JobQueue.droppedByRestartLine(0).contains("lost on restart"));
    }

    // --- startup-gate precedence (what -Dlinearmc.format=ANVIL relies on) ---

    @Test
    public void syspropFormatSuppressesLinearFileValue() {
        assertEquals(RegionFileFormat.ANVIL,
            LinearPolicy.resolveFormat("ANVIL", "LINEAR", RegionFileFormat.LINEAR),
            "sysprop ANVIL must beat a LINEAR world file (conversion suppressed)");
        assertEquals(RegionFileFormat.LINEAR,
            LinearPolicy.resolveFormat(null, "LINEAR", RegionFileFormat.LINEAR),
            "absent sysprop keeps the LINEAR world file (conversion runs)");
    }
}
