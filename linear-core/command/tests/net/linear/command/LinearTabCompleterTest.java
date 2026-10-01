package net.linear.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.Test;

/** command: LinearTabCompleter contract. */
public class LinearTabCompleterTest {

    private static Supplier<Set<String>> worlds(String... names) {
        Set<String> set = new HashSet<>();
        for (String n : names) {
            set.add(n);
        }
        return () -> set;
    }

    private static JobQueue queueWith(int n) {
        JobQueue q = new JobQueue();
        for (int i = 0; i < n; i++) {
            q.submit(new JobQueue.Spec(ConvertDirection.MCA_TO_LINEAR, "world", 6, 1, true, 0));
        }
        return q;
    }

    @Test
    public void nullArgsIsEmpty() {
        assertEquals(List.of(), LinearTabCompleter.suggest(null, new JobQueue(), worlds("a")));
    }

    @Test
    public void emptyArgsListsAllSubcommands() {
        assertEquals(List.of("stats", "convert", "queue", "help", "-h", "--help", "?"),
            LinearTabCompleter.suggest(new String[]{}, new JobQueue(), worlds("a")));
    }

    @Test
    public void singletonEmptyTokenListsAll() {
        assertEquals(List.of("stats", "convert", "queue", "help", "-h", "--help", "?"),
            LinearTabCompleter.suggest(new String[]{""}, new JobQueue(), worlds("a")));
    }

    @Test
    public void firstSlotIncludesHelpAliasesFiltered() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world");
        assertEquals(List.of("-h", "--help"),
            LinearTabCompleter.suggest(new String[]{"-"}, q, w));
        assertEquals(List.of("--help"),
            LinearTabCompleter.suggest(new String[]{"--"}, q, w));
        assertEquals(List.of("?"),
            LinearTabCompleter.suggest(new String[]{"?"}, q, w));
        assertEquals(List.of("help"),
            LinearTabCompleter.suggest(new String[]{"h"}, q, w));
        assertEquals(List.of("-h"),
            LinearTabCompleter.suggest(new String[]{"-h"}, q, w));
    }

    @Test
    public void headFiltering() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world");
        assertEquals(List.of("stats"), LinearTabCompleter.suggest(new String[]{"s"}, q, w));
        assertEquals(List.of("convert"), LinearTabCompleter.suggest(new String[]{"c"}, q, w));
        assertEquals(List.of("queue"), LinearTabCompleter.suggest(new String[]{"q"}, q, w));
        assertEquals(List.of("help"), LinearTabCompleter.suggest(new String[]{"h"}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"x"}, q, w));
        assertEquals(List.of("convert"), LinearTabCompleter.suggest(new String[]{"convert"}, q, w));
    }

    @Test
    public void headFilteringIsCaseInsensitive() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world");
        assertEquals(List.of("convert"), LinearTabCompleter.suggest(new String[]{"C"}, q, w));
        assertEquals(List.of("stats"), LinearTabCompleter.suggest(new String[]{"ST"}, q, w));
        assertEquals(List.of("queue"), LinearTabCompleter.suggest(new String[]{"Q"}, q, w));
        assertEquals(List.of("help"), LinearTabCompleter.suggest(new String[]{"HELP"}, q, w));
    }

    @Test
    public void unknownHeadIsEmpty() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world");
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"bogus", ""}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"bogus", "x", "y"}, q, w));
    }

    @Test
    public void statsWorldsFilteredAndSorted() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world_nether", "world", "other");
        assertEquals(List.of("other", "world", "world_nether"),
            LinearTabCompleter.suggest(new String[]{"stats", ""}, q, w));
        assertEquals(List.of("world", "world_nether"),
            LinearTabCompleter.suggest(new String[]{"stats", "wor"}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"stats", "zzz"}, q, w));
    }

    @Test
    public void statsWorldsCaseInsensitivePreservesCasing() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("World", "WORLD_NETHER", "other");
        List<String> got = LinearTabCompleter.suggest(new String[]{"stats", "wor"}, q, w);
        assertEquals(List.of("WORLD_NETHER", "World"), got);
    }

    @Test
    public void statsNoFlagsBeyondWorld() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world");
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"stats", "world", ""}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"stats", "a", "b"}, q, w));
    }

    @Test
    public void convertWorldSlot() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world_nether", "world", "other");
        assertEquals(List.of("other", "world", "world_nether"),
            LinearTabCompleter.suggest(new String[]{"convert", ""}, q, w));
        assertEquals(List.of("world", "world_nether"),
            LinearTabCompleter.suggest(new String[]{"convert", "wor"}, q, w));
    }

    @Test
    public void convertFlagSlot() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world");
        assertEquals(
            List.of("--to-linear", "--to-mca", "--level", "--threads", "--execute", "--dry-run"),
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--"}, q, w));
        assertEquals(List.of("--to-linear", "--to-mca"),
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--to-"}, q, w));
        assertEquals(List.of("--to-linear", "--to-mca"),
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--TO-"}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"convert", "world", "--zzz"}, q, w));
    }

    @Test
    public void convertEmptyTokenAfterSpaceOffersFlags() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world");
        assertEquals(
            List.of("--to-linear", "--to-mca", "--level", "--threads", "--execute", "--dry-run"),
            LinearTabCompleter.suggest(new String[]{"convert", "myworld", ""}, q, w));
    }

    @Test
    public void convertLevelValues() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world");
        List<String> all = new java.util.ArrayList<>();
        for (int i = 1; i <= 22; i++) {
            all.add(String.valueOf(i));
        }
        assertEquals(all,
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--level", ""}, q, w));
        assertEquals(List.of("6"),
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--level", "6"}, q, w));
        assertEquals(List.of("2", "20", "21", "22"),
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--level", "2"}, q, w));
        assertEquals(all,
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--LEVEL", ""}, q, w));
        assertEquals(List.of(),
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--level", "99"}, q, w));
    }

    @Test
    public void convertThreadsValues() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world");
        List<String> all = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            all.add(String.valueOf(i));
        }
        assertEquals(all,
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--threads", ""}, q, w));
        assertEquals(List.of("2"),
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--threads", "2"}, q, w));
        assertEquals(all,
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--THREADS", ""}, q, w));
        assertEquals(List.of(),
            LinearTabCompleter.suggest(new String[]{"convert", "world", "--threads", "9"}, q, w));
    }

    @Test
    public void queueOpsFilteredInOrder() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world");
        assertEquals(List.of("list", "status", "clear", "pause", "resume", "cancel"),
            LinearTabCompleter.suggest(new String[]{"queue", ""}, q, w));
        assertEquals(List.of("clear", "cancel"),
            LinearTabCompleter.suggest(new String[]{"queue", "c"}, q, w));
        assertEquals(List.of("pause"),
            LinearTabCompleter.suggest(new String[]{"queue", "P"}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"queue", "zzz"}, q, w));
    }

    @Test
    public void queueJobIdCompletion() {
        JobQueue q = queueWith(2);
        List<String> all = LinearTabCompleter.suggest(new String[]{"queue", "pause", ""}, q, worlds("w"));
        assertEquals(2, all.size());
        assertTrue(all.get(0).compareTo(all.get(1)) < 0);
        String prefix = all.get(0).substring(0, 4);
        List<String> filtered = LinearTabCompleter.suggest(
            new String[]{"queue", "pause", prefix}, q, worlds("w"));
        assertTrue(filtered.contains(all.get(0)));
        for (String id : filtered) {
            assertTrue(id.toLowerCase(java.util.Locale.ROOT)
                .startsWith(prefix.toLowerCase(java.util.Locale.ROOT)));
        }
        assertEquals(all,
            LinearTabCompleter.suggest(new String[]{"queue", "resume", ""}, q, worlds("w")));
        assertEquals(all,
            LinearTabCompleter.suggest(new String[]{"queue", "cancel", ""}, q, worlds("w")));
        assertEquals(all,
            LinearTabCompleter.suggest(new String[]{"queue", "status", ""}, q, worlds("w")));
        assertEquals(all,
            LinearTabCompleter.suggest(new String[]{"queue", "PAUSE", ""}, q, worlds("w")));
    }

    @Test
    public void queueJobIdCaseInsensitive() {
        JobQueue q = queueWith(1);
        String id = q.list().get(0).id().toString();
        List<String> got = LinearTabCompleter.suggest(
            new String[]{"queue", "pause", id.substring(0, 2).toUpperCase(java.util.Locale.ROOT)},
            q, worlds("w"));
        assertEquals(List.of(id), got);
    }

    @Test
    public void queueListWithExtraArgIsEmpty() {
        JobQueue q = queueWith(1);
        Supplier<Set<String>> w = worlds("world");
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"queue", "list", ""}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"queue", "clear", "x"}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"queue", "bogus", ""}, q, w));
        assertEquals(List.of(),
            LinearTabCompleter.suggest(new String[]{"queue", "pause", q.list().get(0).id().toString(), "extra"}, q, w));
        assertEquals(List.of(),
            LinearTabCompleter.suggest(new String[]{"queue", "list", "a", "b"}, q, w));
    }

    @Test
    public void helpWithExtraArgsIsEmpty() {
        JobQueue q = new JobQueue();
        Supplier<Set<String>> w = worlds("world");
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"help", ""}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"help", "x"}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"-h", "x"}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"--help", "x"}, q, w));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"?", "x"}, q, w));
    }

    @Test
    public void nullQueueAndSupplierSafe() {
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"stats", ""}, null, null));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"stats", ""}, new JobQueue(), null));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"convert", ""}, null, null));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"queue", "pause", ""}, null, null));
        assertEquals(
            List.of("--to-linear", "--to-mca", "--level", "--threads", "--execute", "--dry-run"),
            LinearTabCompleter.suggest(new String[]{"convert", "w", "--"}, null, null));
        assertEquals(List.of("list", "status", "clear", "pause", "resume", "cancel"),
            LinearTabCompleter.suggest(new String[]{"queue", ""}, null, null));
    }

    @Test
    public void throwingSupplierSafe() {
        Supplier<Set<String>> throwing = () -> {
            throw new RuntimeException("boom");
        };
        Supplier<Set<String>> nullSet = () -> null;
        JobQueue q = new JobQueue();
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"stats", ""}, q, throwing));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"convert", ""}, q, throwing));
        assertEquals(List.of(), LinearTabCompleter.suggest(new String[]{"stats", ""}, q, nullSet));
        assertEquals(
            List.of("--to-linear", "--to-mca", "--level", "--threads", "--execute", "--dry-run"),
            LinearTabCompleter.suggest(new String[]{"convert", "w", "--"}, q, throwing));
        assertEquals(List.of("list", "status", "clear", "pause", "resume", "cancel"),
            LinearTabCompleter.suggest(new String[]{"queue", ""}, q, throwing));
    }
}
