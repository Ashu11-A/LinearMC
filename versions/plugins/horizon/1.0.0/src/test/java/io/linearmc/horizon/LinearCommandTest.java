package io.linearmc.horizon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.linear.LinearRegionTimings;
import net.linear.command.CommandSender;
import net.linear.command.ConvertDirection;
import net.linear.command.ConvertJob;
import net.linear.command.JobQueue;
import net.linear.command.JobState;
import net.linear.command.LinearAction;
import net.linear.command.LinearCommandExecutor;
import net.linear.command.LinearCommandParser;
import net.linear.command.LinearPermissions;
import net.linear.command.LinearTabCompleter;
import net.linear.command.QueueOp;
import net.linear.command.StatsProvider;
import org.junit.jupiter.api.Test;

/**
 * Contract the Paper-half {@code /linear} tree dispatches: core grammar
 * plus executor smoke with a fake sender/provider. Bukkit-free (the
 * {@code LinearCommand} adapter itself needs a server, so it stays out).
 */
public class LinearCommandTest {

    private static final class RecordingSender implements CommandSender {
        final List<String> lines = new ArrayList<>();
        final boolean permitted;

        RecordingSender(boolean permitted) {
            this.permitted = permitted;
        }

        @Override
        public void sendMessage(String message) {
            lines.add(message);
        }

        @Override
        public boolean hasPermission(String permission) {
            return permitted;
        }

        @Override
        public String name() {
            return "tester";
        }
    }

    @Test
    public void parseStatsAndHelp() {
        LinearCommandParser parser = new LinearCommandParser();
        assertTrue(parser.parse(new String[]{"stats"}) instanceof LinearAction.StatsAction);
        LinearAction.StatsAction filtered =
            (LinearAction.StatsAction) parser.parse(new String[]{"stats", "world"});
        assertEquals(Optional.of("world"), filtered.worldFilter());
        assertTrue(parser.parse(new String[]{}) instanceof LinearAction.HelpAction);
        assertTrue(parser.parse(new String[]{"help"}) instanceof LinearAction.HelpAction);
        assertThrows(net.linear.command.InvalidUsageException.class,
            () -> parser.parse(new String[]{"bogus"}));
        assertThrows(net.linear.command.InvalidUsageException.class,
            () -> parser.parse(new String[]{"stats", "a", "b"}));
    }

    @Test
    public void parseConvertDefaultsAndFlags() {
        LinearCommandParser parser = new LinearCommandParser();
        LinearAction.ConvertAction def =
            (LinearAction.ConvertAction) parser.parse(new String[]{"convert", "world"});
        assertEquals(ConvertDirection.MCA_TO_LINEAR, def.direction());
        assertEquals("world", def.world());
        assertEquals(6, def.level());
        assertEquals(1, def.threads());
        assertTrue(def.dryRun());

        LinearAction.ConvertAction full = (LinearAction.ConvertAction) parser.parse(new String[]{
            "convert", "nether", "--to-mca", "--level", "22", "--threads", "4", "--execute"});
        assertEquals(ConvertDirection.LINEAR_TO_MCA, full.direction());
        assertEquals(22, full.level());
        assertEquals(4, full.threads());
        assertFalse(full.dryRun());

        assertThrows(net.linear.command.InvalidUsageException.class,
            () -> parser.parse(new String[]{"convert"}));
        assertThrows(net.linear.command.InvalidUsageException.class,
            () -> parser.parse(new String[]{"convert", "world", "--level", "99"}));
    }

    @Test
    public void parseQueueOps() {
        LinearCommandParser parser = new LinearCommandParser();
        assertEquals(QueueOp.LIST,
            ((LinearAction.QueueAction) parser.parse(new String[]{"queue", "list"})).op());
        assertEquals(QueueOp.CLEAR,
            ((LinearAction.QueueAction) parser.parse(new String[]{"queue", "clear"})).op());
        UUID id = UUID.randomUUID();
        LinearAction.QueueAction pause =
            (LinearAction.QueueAction) parser.parse(new String[]{"queue", "pause", id.toString()});
        assertEquals(QueueOp.PAUSE, pause.op());
        assertEquals(Optional.of(id.toString()), pause.jobRef());
        LinearAction.QueueAction prefixPause =
            (LinearAction.QueueAction) parser.parse(
                new String[]{"queue", "pause", id.toString().substring(0, 8)});
        assertEquals(QueueOp.PAUSE, prefixPause.op());
        assertEquals(Optional.of(id.toString().substring(0, 8)), prefixPause.jobRef());
        assertThrows(net.linear.command.InvalidUsageException.class,
            () -> parser.parse(new String[]{"queue", "pause", "  "}));
        assertThrows(net.linear.command.InvalidUsageException.class,
            () -> parser.parse(new String[]{"queue", "bogus"}));
    }

    @Test
    public void executorEmptyStatsAndQueueLifecycle() {
        JobQueue queue = new JobQueue();
        StatsProvider empty = LinkedHashMap::new;
        LinearCommandExecutor executor = new LinearCommandExecutor(queue, empty);
        RecordingSender sender = new RecordingSender(true);

        // Empty snapshots render the pinned empty-state line.
        executor.execute(sender, new String[]{"stats"});
        assertEquals(LinearPermissions.EMPTY_STATE, sender.lines.get(0));

        // Submit through the executor, like the command tree does.
        executor.execute(sender, new String[]{"convert", "world", "--execute"});
        assertEquals(1, queue.list().size());
        assertTrue(sender.lines.get(1).startsWith("Queued "));
        assertTrue(sender.lines.get(1).contains(" for world: "));
        ConvertJob job = queue.list().get(0);

        // QUEUED jobs cannot pause (RUNNING-only transition): the executor
        // reports the refusal instead of throwing.
        executor.execute(sender, new String[]{"queue", "pause", job.id().toString()});
        assertTrue(sender.lines.get(2).contains("Cannot pause"));

        // Start, then pause/resume/cancel via the executor.
        job.start();
        executor.execute(sender, new String[]{"queue", "pause", job.id().toString()});
        assertEquals(JobState.PAUSED, job.state());
        assertTrue(sender.lines.get(3).contains("paused."));
        executor.execute(sender, new String[]{"queue", "resume", job.id().toString()});
        assertEquals(JobState.RUNNING, job.state());
        assertTrue(sender.lines.get(4).contains("resumed."));
        executor.execute(sender, new String[]{"queue", "cancel", job.id().toString()});
        assertEquals(JobState.CANCELLED, job.state());
        assertTrue(sender.lines.get(5).contains("cancelled."));

        // Terminal jobs clear; the list then reports empty.
        executor.execute(sender, new String[]{"queue", "clear"});
        assertTrue(sender.lines.get(6).contains("Cleared 1"));
        executor.execute(sender, new String[]{"queue", "list"});
        assertTrue(sender.lines.get(7).contains("No jobs yet"));

        // Non-empty snapshots render the panel header.
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("world/region", LinearRegionTimings.LinearFolderSnapshot.EMPTY);
        new LinearCommandExecutor(queue, () -> snaps).execute(sender, new String[]{"stats"});
        assertTrue(sender.lines.get(8).contains("1 folders"));

        // Permission denial keeps the pinned wording.
        RecordingSender denied = new RecordingSender(false);
        executor.execute(denied, new String[]{"stats"});
        assertEquals(LinearPermissions.denied("linearstats"), denied.lines.get(0));
    }

    @Test
    public void reverseConvertSubmitsThroughNormalPath() {
        JobQueue queue = new JobQueue();
        StatsProvider empty = LinkedHashMap::new;
        LinearCommandExecutor executor = new LinearCommandExecutor(queue, empty);
        RecordingSender sender = new RecordingSender(true);

        // Reverse --execute is NOT refused: it submits like forward and the
        // async leg owns it (QUEUED, never a FAILED refusal).
        executor.execute(sender, new String[]{"convert", "nether", "--to-mca", "--execute"});
        assertEquals(1, queue.list().size());
        ConvertJob job = queue.list().get(0);
        assertEquals(ConvertDirection.LINEAR_TO_MCA, job.direction());
        assertEquals(JobState.QUEUED, job.state());
        assertTrue(sender.lines.get(0).startsWith("Queued "));
        assertTrue(sender.lines.get(0).contains(" for nether: "));
    }

    @Test
    public void tabSubcommandsEmptyAndPrefix() {
        JobQueue queue = new JobQueue();
        java.util.Set<String> worlds = java.util.Set.of();
        List<String> all = LinearTabCompleter.suggest(new String[]{""}, queue, () -> worlds);
        assertTrue(all.contains("stats"));
        assertTrue(all.contains("convert"));
        assertTrue(all.contains("queue"));
        assertTrue(all.contains("help"));

        List<String> filtered =
            LinearTabCompleter.suggest(new String[]{"c"}, queue, () -> worlds);
        assertTrue(filtered.contains("convert"));
        assertFalse(filtered.contains("stats"));
    }

    @Test
    public void tabConvertWorldAndFlagSlots() {
        JobQueue queue = new JobQueue();
        java.util.Set<String> worlds = java.util.Set.of("world", "world_nether");
        List<String> both =
            LinearTabCompleter.suggest(new String[]{"convert", ""}, queue, () -> worlds);
        assertTrue(both.contains("world"));
        assertTrue(both.contains("world_nether"));

        List<String> prefixed =
            LinearTabCompleter.suggest(new String[]{"convert", "world_n"}, queue, () -> worlds);
        assertEquals(List.of("world_nether"), prefixed);

        List<String> flags =
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--"}, queue, () -> worlds);
        assertTrue(flags.contains("--to-linear"));
        assertTrue(flags.contains("--execute"));
    }

    @Test
    public void tabQueueJobIdCompletion() {
        JobQueue queue = new JobQueue();
        ConvertJob job = queue.submit(new JobQueue.Spec(
            ConvertDirection.MCA_TO_LINEAR, "world", 6, 1, true, 0));
        java.util.Set<String> worlds = java.util.Set.of();
        List<String> ids = LinearTabCompleter.suggest(
            new String[]{"queue", "pause", ""}, queue, () -> worlds);
        assertTrue(ids.contains(job.id().toString()));

        List<String> none = LinearTabCompleter.suggest(
            new String[]{"queue", "pause", "zzz-no-match"}, queue, () -> worlds);
        assertTrue(none.isEmpty());
    }

    @Test
    public void tabUnknownHeadEmpty() {
        JobQueue queue = new JobQueue();
        java.util.Set<String> worlds = java.util.Set.of("world");
        List<String> unknown =
            LinearTabCompleter.suggest(new String[]{"bogus", ""}, queue, () -> worlds);
        assertTrue(unknown.isEmpty());
    }

    @Test
    public void tabStatsWorldsFilteredAndSorted() {
        JobQueue queue = new JobQueue();
        java.util.Set<String> worlds = java.util.Set.of("world_nether", "world", "other");
        assertEquals(List.of("other", "world", "world_nether"),
            LinearTabCompleter.suggest(new String[]{"stats", ""}, queue, () -> worlds));
        assertEquals(List.of("world", "world_nether"),
            LinearTabCompleter.suggest(new String[]{"stats", "wor"}, queue, () -> worlds));
        assertTrue(LinearTabCompleter.suggest(
            new String[]{"stats", "zzz"}, queue, () -> worlds).isEmpty());
    }

    @Test
    public void tabLevelAndThreadsValues() {
        JobQueue queue = new JobQueue();
        java.util.Set<String> worlds = java.util.Set.of("world");
        List<String> levels = LinearTabCompleter.suggest(
            new String[]{"convert", "world", "--level", ""}, queue, () -> worlds);
        assertEquals(22, levels.size());
        assertTrue(levels.contains("1"));
        assertTrue(levels.contains("22"));
        assertEquals(List.of("6"), LinearTabCompleter.suggest(
            new String[]{"convert", "world", "--level", "6"}, queue, () -> worlds));
        List<String> threads = LinearTabCompleter.suggest(
            new String[]{"convert", "world", "--threads", ""}, queue, () -> worlds);
        assertEquals(8, threads.size());
        assertTrue(threads.contains("8"));
        assertEquals(List.of("2"), LinearTabCompleter.suggest(
            new String[]{"convert", "world", "--threads", "2"}, queue, () -> worlds));
    }

    @Test
    public void tabEmptyTokenOffersConvertFlags() {
        JobQueue queue = new JobQueue();
        java.util.Set<String> worlds = java.util.Set.of("world");
        List<String> flags = LinearTabCompleter.suggest(
            new String[]{"convert", "myworld", ""}, queue, () -> worlds);
        assertTrue(flags.contains("--to-linear"));
        assertTrue(flags.contains("--level"));
        assertTrue(flags.contains("--threads"));
        assertTrue(flags.contains("--execute"));
    }

    @Test
    public void tabNoFlagsOnStats() {
        JobQueue queue = new JobQueue();
        java.util.Set<String> worlds = java.util.Set.of("world");
        assertTrue(LinearTabCompleter.suggest(
            new String[]{"stats", "world", ""}, queue, () -> worlds).isEmpty());
        assertTrue(LinearTabCompleter.suggest(
            new String[]{"stats", "a", "b"}, queue, () -> worlds).isEmpty());
    }
}
