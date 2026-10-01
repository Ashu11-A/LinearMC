package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.linear.command.CommandSender;
import net.linear.command.ConvertDirection;
import net.linear.command.ConvertJob;
import net.linear.command.InvalidUsageException;
import net.linear.command.JobQueue;
import net.linear.command.JobState;
import net.linear.command.LinearAction;
import net.linear.command.LinearCommandExecutor;
import net.linear.command.LinearCommandParser;
import net.linear.command.LinearPermissions;
import net.linear.command.QueueOp;

import org.junit.jupiter.api.Test;

/**
 * {@code /linear} command-tree binding: parser grammar plus core executor
 * smoke with a fake sender/stats source (no NMS bootstrap, no Bukkit).
 *
 * <p>Runs in {@link LinearNmsTestSuite} via the {@code net.linear} package
 * select. The Paper leg ({@code io.papermc.paper.command.CommandLinear},
 * a Bukkit {@code Command}) is not instantiated here — it only adapts the
 * Bukkit sender, pulls {@code LinearFlushCoordinator.snapshots()} and
 * delegates to the parser/executor covered below. Reverse
 * ({@code LINEAR_TO_MCA}) now executes through the normal path (core
 * {@code JobRunner} switches on direction); no refusal constant remains.</p>
 */
public class LinearCommandBindingTest {

    private static final class FakeSender implements CommandSender {
        final List<String> lines = new ArrayList<>();
        final Set<String> grants = new HashSet<>(List.of(
            LinearPermissions.STATS,
            LinearPermissions.BASE,
            LinearPermissions.CONVERT,
            LinearPermissions.QUEUE));

        @Override
        public void sendMessage(String message) {
            this.lines.add(message);
        }

        @Override
        public boolean hasPermission(String permission) {
            return this.grants.contains(permission);
        }

        @Override
        public String name() {
            return "test";
        }
    }

    private static LinearCommandExecutor executorWith(JobQueue queue, Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps) {
        return new LinearCommandExecutor(queue, () -> snaps);
    }

    // --- parser: top-level grammar ---

    @Test
    public void emptyArgsShowHelp() {
        assertInstanceOf(LinearAction.HelpAction.class, new LinearCommandParser().parse(new String[0]));
        assertInstanceOf(LinearAction.HelpAction.class, new LinearCommandParser().parse(null));
        assertInstanceOf(LinearAction.HelpAction.class, new LinearCommandParser().parse(new String[]{"help"}));
    }

    @Test
    public void unknownSubcommandShowsUsage() {
        InvalidUsageException bad = assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"bogus"}));
        assertTrue(bad.getMessage().contains(LinearCommandParser.USAGE), "usage must be shown");
    }

    @Test
    public void statsTakesAtMostOneFilter() {
        LinearAction bare = new LinearCommandParser().parse(new String[]{"stats"});
        assertTrue(((LinearAction.StatsAction) bare).worldFilter().isEmpty());
        LinearAction filtered = new LinearCommandParser().parse(new String[]{"stats", "world"});
        assertEquals("world", ((LinearAction.StatsAction) filtered).worldFilter().orElseThrow());
        assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"stats", "a", "b"}));
    }

    // --- parser: convert flags ---

    @Test
    public void convertDefaultsAreDryRunMcaToLinear() {
        LinearAction action = new LinearCommandParser().parse(new String[]{"convert", "world"});
        LinearAction.ConvertAction convert = (LinearAction.ConvertAction) action;
        assertEquals(ConvertDirection.MCA_TO_LINEAR, convert.direction());
        assertEquals("world", convert.world());
        assertEquals(6, convert.level());
        assertEquals(1, convert.threads());
        assertTrue(convert.dryRun(), "convert is dry-run unless --execute");
    }

    @Test
    public void convertFlagsParse() {
        LinearAction action = new LinearCommandParser().parse(new String[]{
            "convert", "world_nether", "--to-mca", "--level", "3", "--threads", "2", "--execute"});
        LinearAction.ConvertAction convert = (LinearAction.ConvertAction) action;
        assertEquals(ConvertDirection.LINEAR_TO_MCA, convert.direction());
        assertEquals(3, convert.level());
        assertEquals(2, convert.threads());
        assertFalse(convert.dryRun());
    }

    @Test
    public void convertRejectsBadInput() {
        assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"convert"}));
        assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"convert", "world", "--level", "0"}));
        assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"convert", "world", "--level", "23"}));
        assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"convert", "world", "--threads", "0"}));
        assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"convert", "world", "--bogus"}));
    }

    // --- parser: queue ops ---

    @Test
    public void queueOpsParse() {
        assertEquals(QueueOp.LIST,
            ((LinearAction.QueueAction) new LinearCommandParser().parse(new String[]{"queue", "list"})).op());
        assertEquals(QueueOp.CLEAR,
            ((LinearAction.QueueAction) new LinearCommandParser().parse(new String[]{"queue", "clear"})).op());
        UUID id = UUID.randomUUID();
        LinearAction.QueueAction pause =
            (LinearAction.QueueAction) new LinearCommandParser().parse(new String[]{"queue", "pause", id.toString()});
        assertEquals(QueueOp.PAUSE, pause.op());
        assertEquals(id.toString(), pause.jobRef().orElseThrow());
    }

    @Test
    public void queueRejectsBadInput() {
        assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"queue"}));
        assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"queue", "bogus"}));
        assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"queue", "pause"}));
        assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"queue", "pause", "  "}));
        assertThrows(InvalidUsageException.class,
            () -> new LinearCommandParser().parse(new String[]{"queue", "list", UUID.randomUUID().toString()}));
    }

    // --- executor: help/stats ---

    @Test
    public void helpSendsHelpText() {
        JobQueue queue = new JobQueue();
        FakeSender sender = new FakeSender();
        executorWith(queue, Map.of()).execute(sender, new String[]{"help"});
        assertEquals(1, sender.lines.size());
        assertEquals(LinearCommandParser.HELP_TEXT, sender.lines.get(0));
    }

    @Test
    public void usageErrorReachesSender() {
        JobQueue queue = new JobQueue();
        FakeSender sender = new FakeSender();
        executorWith(queue, Map.of()).execute(sender, new String[]{"bogus"});
        assertEquals(1, sender.lines.size());
        assertTrue(sender.lines.get(0).contains(LinearCommandParser.USAGE));
    }

    @Test
    public void statsEmptyStateIsLegacyLine() {
        JobQueue queue = new JobQueue();
        FakeSender sender = new FakeSender();
        executorWith(queue, Map.of()).execute(sender, new String[]{"stats"});
        assertEquals(1, sender.lines.size());
        assertEquals(LinearPermissions.EMPTY_STATE, sender.lines.get(0));
    }

    @Test
    public void statsSummarisesFolders() {
        JobQueue queue = new JobQueue();
        FakeSender sender = new FakeSender();
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = Map.of(
            "world/region", LinearRegionTimings.LinearFolderSnapshot.EMPTY,
            "world/poi", LinearRegionTimings.LinearFolderSnapshot.EMPTY);
        executorWith(queue, snaps).execute(sender, new String[]{"stats"});
        assertEquals(1, sender.lines.size());
        assertTrue(sender.lines.get(0).startsWith("Linear stats (2 folders)"),
            "was: " + sender.lines.get(0));
    }

    @Test
    public void statsDeniedWithoutNode() {
        JobQueue queue = new JobQueue();
        FakeSender sender = new FakeSender();
        sender.grants.clear();
        executorWith(queue, Map.of()).execute(sender, new String[]{"stats"});
        assertEquals(1, sender.lines.size());
        assertEquals(LinearPermissions.denied("linearstats"), sender.lines.get(0));
    }

    // --- executor: convert submit + queue lifecycle ---

    @Test
    public void convertDryRunQueuesWithoutExecuting() {
        JobQueue queue = new JobQueue();
        FakeSender sender = new FakeSender();
        executorWith(queue, Map.of()).execute(sender, new String[]{"convert", "world"});
        assertEquals(1, sender.lines.size());
        assertTrue(sender.lines.get(0).contains("Practice run, nothing changed"), "was: " + sender.lines.get(0));
        assertEquals(1, queue.list().size());
        ConvertJob job = queue.list().get(0);
        assertEquals(JobState.QUEUED, job.state(), "core submit only queues; the leg executes");
        assertTrue(job.dryRun());
    }

    @Test
    public void convertExecuteQueuesRealJob() {
        JobQueue queue = new JobQueue();
        FakeSender sender = new FakeSender();
        executorWith(queue, Map.of()).execute(
            sender, new String[]{"convert", "world", "--execute"});
        assertEquals(1, queue.list().size());
        ConvertJob job = queue.list().get(0);
        assertFalse(job.dryRun());
        assertEquals(JobState.QUEUED, job.state());
    }

    @Test
    public void queueListPauseResumeCancelClear() {
        JobQueue queue = new JobQueue();
        FakeSender sender = new FakeSender();
        LinearCommandExecutor executor = executorWith(queue, Map.of());
        executor.execute(sender, new String[]{"convert", "world", "--execute"});
        UUID id = queue.list().get(0).id();
        // Submitted jobs are QUEUED; the leg moves them to RUNNING on launch
        // (pause/resume only apply to running jobs).
        queue.get(id).start();

        sender.lines.clear();
        executor.execute(sender, new String[]{"queue", "list"});
        assertEquals(1, sender.lines.size());
        assertTrue(sender.lines.get(0).contains(id.toString().substring(0, 8)));

        executor.execute(sender, new String[]{"queue", "pause", id.toString()});
        assertEquals(JobState.PAUSED, queue.get(id).state());
        executor.execute(sender, new String[]{"queue", "resume", id.toString()});
        assertEquals(JobState.RUNNING, queue.get(id).state());
        executor.execute(sender, new String[]{"queue", "cancel", id.toString()});
        assertEquals(JobState.CANCELLED, queue.get(id).state());

        sender.lines.clear();
        executor.execute(sender, new String[]{"queue", "clear"});
        assertEquals(0, queue.list().size(), "clear drops the cancelled job");
        assertTrue(sender.lines.get(0).contains("Cleared 1"));
    }

    @Test
    public void queueUnknownJobReports() {
        JobQueue queue = new JobQueue();
        FakeSender sender = new FakeSender();
        executorWith(queue, Map.of()).execute(
            sender, new String[]{"queue", "pause", UUID.randomUUID().toString()});
        assertEquals(1, sender.lines.size());
        assertTrue(sender.lines.get(0).contains("No job"));
    }

    // --- leg contract: reverse now executes (no refusal) ---

    @Test
    public void reverseConvertQueuesAndRoutesByDirection() {
        // The Paper leg retired the linear-to-mca refusal: reverse --execute
        // queues like forward and the leg runs it via core JobRunner,
        // which switches on direction (forward vs reverse).
        JobQueue queue = new JobQueue();
        FakeSender sender = new FakeSender();
        executorWith(queue, Map.of()).execute(
            sender, new String[]{"convert", "world", "--to-mca", "--execute"});
        assertEquals(1, queue.list().size());
        ConvertJob job = queue.list().get(0);
        assertEquals(ConvertDirection.LINEAR_TO_MCA, job.direction());
        assertEquals(JobState.QUEUED, job.state(), "core submit only queues; the leg executes via JobRunner");
        assertFalse(job.dryRun());
    }
}
