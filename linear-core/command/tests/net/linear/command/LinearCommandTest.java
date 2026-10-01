package net.linear.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.linear.LinearRegionTimings;
import org.junit.Test;

/** command: /linear grammar (parse) + executor smoke (no Bukkit). */
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
        try {
            parser.parse(new String[]{"bogus"});
            fail("unknown subcommand must throw");
        } catch (InvalidUsageException bad) {
            assertTrue(bad.getMessage().contains(LinearCommandParser.USAGE));
            assertTrue(bad.getMessage().contains(LinearCommandParser.HELP_TEXT));
        }
        try {
            parser.parse(new String[]{"stats", "a", "b"});
            fail("extra stats arg must throw");
        } catch (InvalidUsageException expected) {
        }
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

        try {
            parser.parse(new String[]{"convert"});
            fail("missing world must throw");
        } catch (InvalidUsageException expected) {
        }
        try {
            parser.parse(new String[]{"convert", "world", "--level", "99"});
            fail("bad level must throw");
        } catch (InvalidUsageException expected) {
        }
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
        try {
            parser.parse(new String[]{"queue", "pause", "  "});
            fail("blank job id must throw");
        } catch (InvalidUsageException expected) {
        }
        try {
            parser.parse(new String[]{"queue", "bogus"});
            fail("bad op must throw");
        } catch (InvalidUsageException expected) {
        }
    }

    @Test
    public void parseQueueStatusAcceptsPrefix() {
        LinearCommandParser parser = new LinearCommandParser();
        UUID id = UUID.randomUUID();
        LinearAction.StatusAction full =
            (LinearAction.StatusAction) parser.parse(
                new String[]{"queue", "status", id.toString()});
        assertEquals(id.toString(), full.jobRef());

        LinearAction.StatusAction prefix =
            (LinearAction.StatusAction) parser.parse(
                new String[]{"queue", "status", id.toString().substring(0, 8)});
        assertEquals(id.toString().substring(0, 8), prefix.jobRef());

        try {
            parser.parse(new String[]{"queue", "status"});
            fail("status without id must throw");
        } catch (InvalidUsageException expected) {
        }
    }

    @Test
    public void executorQueueStatusUsesPlainWords() {
        JobQueue queue = new JobQueue();
        StatsProvider empty = LinkedHashMap::new;
        LinearCommandExecutor executor = new LinearCommandExecutor(queue, empty);
        RecordingSender sender = new RecordingSender(true);

        ConvertJob job = queue.submit(new JobQueue.Spec(
            ConvertDirection.MCA_TO_LINEAR, "world", 6, 1, true, 0));
        executor.execute(sender, new String[]{"queue", "status", job.shortId()});
        assertTrue(sender.lines.size() >= 2);
        assertTrue(sender.lines.get(0), sender.lines.get(0).contains("Waiting"));
        boolean dryRunNote = false;
        for (String line : sender.lines) {
            assertTrue("too wide: " + line, line.length() <= 55);
            if (line.contains("Practice run, nothing changed")) {
                dryRunNote = true;
            }
        }
        assertTrue(dryRunNote);

        executor.execute(sender,
            new String[]{"queue", "status", UUID.randomUUID().toString()});
        assertTrue(sender.lines.get(sender.lines.size() - 1).contains("No job"));
    }

    @Test
    public void executorStatusAmbiguousPrefixAsksForFullId() {
        JobQueue queue = new JobQueue();
        StatsProvider empty = LinkedHashMap::new;
        LinearCommandExecutor executor = new LinearCommandExecutor(queue, empty);
        for (int i = 0; i < 20; i++) {
            queue.submit(new JobQueue.Spec(
                ConvertDirection.MCA_TO_LINEAR, "world", 6, 1, true, 0));
        }
        String ambiguous = null;
        for (char c = '0'; c <= 'f'; c++) {
            if (queue.countByPrefix(String.valueOf(c)) > 1) {
                ambiguous = String.valueOf(c);
                break;
            }
        }
        assertTrue("expected a colliding prefix among 20 jobs", ambiguous != null);
        RecordingSender sender = new RecordingSender(true);
        executor.execute(sender, new String[]{"queue", "status", ambiguous});
        assertTrue(sender.lines.get(sender.lines.size() - 1).contains("Ambiguous id, use full id"));
    }

    @Test
    public void executorConvertIsPlainAndDryRunSafe() {
        JobQueue queue = new JobQueue();
        StatsProvider empty = LinkedHashMap::new;
        LinearCommandExecutor executor = new LinearCommandExecutor(queue, empty);
        RecordingSender sender = new RecordingSender(true);

        executor.execute(sender, new String[]{"convert", "world"});
        assertTrue(sender.lines.get(0),
            sender.lines.get(0).contains("Practice run, nothing changed"));
        assertTrue(sender.lines.get(0),
            sender.lines.get(0).contains("Old saves to new faster saves"));

        executor.execute(sender,
            new String[]{"convert", "world", "--to-mca", "--execute"});
        assertTrue(sender.lines.get(1),
            sender.lines.get(1).contains("New saves back to old"));
    }

    @Test
    public void helpMentionsQueueStatusAndDryRun() {
        assertTrue(LinearCommandParser.HELP_TEXT,
            LinearCommandParser.HELP_TEXT.contains("queue status"));
        assertTrue(LinearCommandParser.HELP_TEXT,
            LinearCommandParser.HELP_TEXT.contains("Practice run, nothing changed"));
        assertTrue(LinearCommandParser.HELP_TEXT,
            LinearCommandParser.HELP_TEXT.contains("Your old files are still there"));
        assertTrue(LinearCommandParser.HELP_TEXT,
            LinearCommandParser.HELP_TEXT.contains("Add --to-mca to copy new saves back to old."));
    }

    @Test
    public void executorConvertQueueAndStats() {
        JobQueue queue = new JobQueue();
        StatsProvider empty = LinkedHashMap::new;
        LinearCommandExecutor executor = new LinearCommandExecutor(queue, empty);
        RecordingSender sender = new RecordingSender(true);

        executor.execute(sender, new String[]{"convert", "world", "--execute"});
        assertEquals(1, queue.list().size());
        assertTrue(sender.lines.get(0), sender.lines.get(0).contains("Queued"));

        executor.execute(sender, new String[]{"queue", "list"});
        assertTrue(sender.lines.get(1), sender.lines.get(1).contains("Waiting"));

        executor.execute(sender, new String[]{"stats"});
        assertEquals(LinearPermissions.EMPTY_STATE, sender.lines.get(2));

        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = new LinkedHashMap<>();
        snaps.put("world/region", LinearRegionTimings.LinearFolderSnapshot.EMPTY);
        LinearCommandExecutor withStats =
            new LinearCommandExecutor(queue, () -> snaps);
        withStats.execute(sender, new String[]{"stats"});
        assertTrue(sender.lines.get(3).contains("1 folders"));

        RecordingSender denied = new RecordingSender(false);
        executor.execute(denied, new String[]{"stats"});
        assertEquals(LinearPermissions.denied("linearstats"), denied.lines.get(0));
    }

    @Test
    public void executorQueuePauseResumeCancelAcceptPrefix() {
        JobQueue queue = new JobQueue();
        StatsProvider empty = LinkedHashMap::new;
        LinearCommandExecutor executor = new LinearCommandExecutor(queue, empty);
        RecordingSender sender = new RecordingSender(true);

        ConvertJob job = queue.submit(new JobQueue.Spec(
            ConvertDirection.MCA_TO_LINEAR, "world", 6, 1, true, 0));
        job.start();
        executor.execute(sender, new String[]{"queue", "pause", job.shortId()});
        assertEquals(JobState.PAUSED, job.state());
        assertTrue(sender.lines.get(sender.lines.size() - 1).contains("paused."));

        executor.execute(sender, new String[]{"queue", "resume", job.shortId()});
        assertEquals(JobState.RUNNING, job.state());
        assertTrue(sender.lines.get(sender.lines.size() - 1).contains("resumed."));

        executor.execute(sender, new String[]{"queue", "cancel", job.shortId()});
        assertEquals(JobState.CANCELLED, job.state());
        assertTrue(sender.lines.get(sender.lines.size() - 1).contains("cancelled."));

        executor.execute(sender, new String[]{"queue", "pause", "zzz-no-match"});
        assertTrue(sender.lines.get(sender.lines.size() - 1).contains("No job"));
    }

    @Test
    public void executorQueuePauseAmbiguousPrefixAsksForFullId() {
        JobQueue queue = new JobQueue();
        StatsProvider empty = LinkedHashMap::new;
        LinearCommandExecutor executor = new LinearCommandExecutor(queue, empty);
        for (int i = 0; i < 20; i++) {
            queue.submit(new JobQueue.Spec(
                ConvertDirection.MCA_TO_LINEAR, "world", 6, 1, true, 0));
        }
        String ambiguous = null;
        for (char c = '0'; c <= 'f'; c++) {
            if (queue.countByPrefix(String.valueOf(c)) > 1) {
                ambiguous = String.valueOf(c);
                break;
            }
        }
        assertTrue("expected a colliding prefix among 20 jobs", ambiguous != null);
        RecordingSender sender = new RecordingSender(true);
        executor.execute(sender, new String[]{"queue", "pause", ambiguous});
        assertTrue(sender.lines.get(sender.lines.size() - 1).contains("Ambiguous id, use full id"));
    }
}
