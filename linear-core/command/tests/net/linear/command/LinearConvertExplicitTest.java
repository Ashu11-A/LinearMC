package net.linear.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.Test;

import net.linear.LinearRegionConverter;
import net.linear.command.parse.InvalidUsageException;
import net.linear.command.parse.LinearCommandParser;
import net.linear.command.parse.LinearTabCompleter;
import net.linear.command.queue.JobQueue;

/** command: explicit --from/--to/--grid (Phase-3, new files only). */
public class LinearConvertExplicitTest {

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
    public void parseExplicitFromToGrid() {
        LinearCommandParser parser = new LinearCommandParser();
        LinearAction action = parser.parse(new String[]{
            "convert", "world", "--from", "v1", "--to", "pumpkin", "--execute"});
        assertTrue(action instanceof LinearAction.ExplicitConvertAction);
        LinearAction.ExplicitConvertAction explicit = (LinearAction.ExplicitConvertAction) action;
        assertEquals(LinearRegionConverter.ConvertFormat.V1, explicit.from());
        assertEquals(LinearRegionConverter.ConvertFormat.PUMPKIN, explicit.to());
        assertEquals(0, explicit.grid());
        assertFalse(explicit.dryRun());
    }

    @Test
    public void parseExplicitDefaultsFromAuto() {
        LinearCommandParser parser = new LinearCommandParser();
        LinearAction action = parser.parse(new String[]{
            "convert", "nether", "--to", "v3"});
        assertTrue(action instanceof LinearAction.ExplicitConvertAction);
        LinearAction.ExplicitConvertAction explicit = (LinearAction.ExplicitConvertAction) action;
        assertEquals(LinearRegionConverter.ConvertFormat.AUTO, explicit.from());
        assertEquals(LinearRegionConverter.ConvertFormat.V3, explicit.to());
        assertTrue(explicit.dryRun());
    }

    @Test
    public void parseExplicitGridAndAliases() {
        LinearCommandParser parser = new LinearCommandParser();
        LinearAction action = parser.parse(new String[]{
            "convert", "world", "--from", "blinear", "--to", "linear", "--grid", "4", "--execute"});
        assertTrue(action instanceof LinearAction.ExplicitConvertAction);
        LinearAction.ExplicitConvertAction explicit = (LinearAction.ExplicitConvertAction) action;
        assertEquals(LinearRegionConverter.ConvertFormat.B_LINEAR, explicit.from());
        // --to linear stays V1 (shipped default, not a family wildcard).
        assertEquals(LinearRegionConverter.ConvertFormat.V1, explicit.to());
        assertEquals(4, explicit.grid());
    }

    @Test
    public void parseFromLinearMatchesFamilyNotV1Only() {
        // F4: --from linear is the family wildcard (V1/V2/V3/PUMPKIN), while --to linear stays V1.
        // Previously fromCli("linear") returned V1, silently narrowing --from linear to V1-only.
        LinearCommandParser parser = new LinearCommandParser();
        LinearAction action = parser.parse(new String[]{
            "convert", "world", "--from", "linear", "--to", "v2", "--execute"});
        assertTrue(action instanceof LinearAction.ExplicitConvertAction);
        LinearAction.ExplicitConvertAction explicit = (LinearAction.ExplicitConvertAction) action;
        assertEquals(LinearRegionConverter.ConvertFormat.LINEAR, explicit.from());
        assertEquals(LinearRegionConverter.ConvertFormat.V2, explicit.to());
        assertTrue(LinearRegionConverter.matchesFrom(
            LinearRegionConverter.ConvertFormat.V1, explicit.from()));
        assertTrue(LinearRegionConverter.matchesFrom(
            LinearRegionConverter.ConvertFormat.V2, explicit.from()));
        assertTrue(LinearRegionConverter.matchesFrom(
            LinearRegionConverter.ConvertFormat.V3, explicit.from()));
        assertTrue(LinearRegionConverter.matchesFrom(
            LinearRegionConverter.ConvertFormat.PUMPKIN, explicit.from()));
        assertFalse(LinearRegionConverter.matchesFrom(
            LinearRegionConverter.ConvertFormat.MCA, explicit.from()));
        assertFalse(LinearRegionConverter.matchesFrom(
            LinearRegionConverter.ConvertFormat.B_LINEAR, explicit.from()));
    }

    @Test
    public void executorQueuedMessageEchoesLevelAndGrid() {
        JobQueue queue = new JobQueue();
        LinearCommandExecutor executor =
            new LinearCommandExecutor(queue, LinkedHashMap::new);
        RecordingSender sender = new RecordingSender(true);

        executor.execute(sender, new String[]{
            "convert", "world", "--from", "v1", "--to", "pumpkin", "--level", "6", "--execute"});
        assertTrue(sender.lines.get(0).contains("level ignored for pumpkin (fixed 1), grid 2"));
    }

    @Test
    public void parseLegacyUntouchedWithoutExplicitFlags() {
        LinearCommandParser parser = new LinearCommandParser();
        LinearAction def = parser.parse(new String[]{"convert", "world"});
        assertTrue(def instanceof LinearAction.ConvertAction);
        assertEquals(ConvertDirection.MCA_TO_LINEAR, ((LinearAction.ConvertAction) def).direction());

        LinearAction legacy = parser.parse(new String[]{
            "convert", "world", "--to-mca", "--execute"});
        assertTrue(legacy instanceof LinearAction.ConvertAction);
        assertEquals(ConvertDirection.LINEAR_TO_MCA, ((LinearAction.ConvertAction) legacy).direction());
    }

    @Test
    public void parseExplicitRejectsBadFormatAndMissingTo() {
        LinearCommandParser parser = new LinearCommandParser();
        try {
            parser.parse(new String[]{"convert", "world", "--from", "bogus", "--to", "pumpkin"});
            fail("bad --from must throw");
        } catch (InvalidUsageException expected) {
        }
        try {
            parser.parse(new String[]{"convert", "world", "--from", "v1"});
            fail("explicit --from without --to must throw");
        } catch (InvalidUsageException expected) {
        }
        try {
            parser.parse(new String[]{"convert", "world", "--to", "auto"});
            fail("--to auto must throw");
        } catch (InvalidUsageException expected) {
        }
        try {
            parser.parse(new String[]{"convert", "world", "--to", "pumpkin", "--grid", "3"});
            fail("bad --grid must throw");
        } catch (InvalidUsageException expected) {
        }
    }

    @Test
    public void executorQueuesExplicitWithFromTo() {
        JobQueue queue = new JobQueue();
        LinearCommandExecutor executor =
            new LinearCommandExecutor(queue, LinkedHashMap::new);
        RecordingSender sender = new RecordingSender(true);

        executor.execute(sender, new String[]{
            "convert", "world", "--from", "pumpkin", "--to", "v1", "--execute"});
        assertEquals(1, queue.list().size());
        ConvertJob job = queue.list().get(0);
        assertTrue(job.isExplicit());
        assertEquals(LinearRegionConverter.ConvertFormat.PUMPKIN, job.fromFormat());
        assertEquals(LinearRegionConverter.ConvertFormat.V1, job.toFormat());
        assertTrue(sender.lines.get(0).contains("pumpkin"));
        assertTrue(sender.lines.get(0).contains("v1"));
    }

    @Test
    public void explicitJobDefaultsAreAutoAndNull() {
        ConvertJob legacy =
            new ConvertJob(ConvertDirection.MCA_TO_LINEAR, "world", 6, 1, true, 0);
        assertFalse(legacy.isExplicit());
        assertEquals(LinearRegionConverter.ConvertFormat.AUTO, legacy.fromFormat());
        assertEquals(null, legacy.toFormat());
        assertEquals(0, legacy.gridSize());
    }

    @Test
    public void completerSuggestsExplicitValues() {
        JobQueue q = new JobQueue();
        assertTrue(LinearTabCompleter.suggest(
            new String[]{"convert", "world", "--from", ""}, q, () -> java.util.Set.of("world"))
            .contains("pumpkin"));
        assertTrue(LinearTabCompleter.suggest(
            new String[]{"convert", "world", "--to", ""}, q, () -> java.util.Set.of("world"))
            .contains("pumpkin"));
        assertTrue(LinearTabCompleter.suggest(
            new String[]{"convert", "world", "--grid", ""}, q, () -> java.util.Set.of("world"))
            .contains("2"));
    }
}
