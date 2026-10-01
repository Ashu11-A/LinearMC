package net.linear.command;

import java.util.*;
import java.util.function.Supplier;

/**
 * Tab completion for {@code /linear} args (excluding the leading label).
 * Pure JDK; never throws for null/throwing queue or world supplier.
 */
public final class LinearTabCompleter {

    private LinearTabCompleter() {
    }

    private static final List<String> FIRST_SLOT = List.of(
        "stats", "convert", "queue", "help", "-h", "--help", "?");
    private static final List<String> QUEUE_OPS =
        List.of("list", "status", "clear", "pause", "resume", "cancel");
    private static final List<String> CONVERT_FLAGS = List.of(
        "--to-linear", "--to-mca", "--level", "--threads", "--execute", "--dry-run");
    private static final List<String> LEVEL_VALUES = levelValues();
    private static final List<String> THREAD_VALUES = threadValues();

    /**
     * Suggests completions for the token being typed (last element of args).
     *
     * @param args command args excluding the {@code /linear} label
     * @param queue job queue for job-id completion (may be null)
     * @param worldNames world-name supplier (may be null or throwing)
     * @return completion candidates; never null
     */
    public static List<String> suggest(String[] args, JobQueue queue,
        Supplier<Set<String>> worldNames) {
        if (args == null) {
            return List.of();
        }
        if (args.length == 0) {
            return List.copyOf(FIRST_SLOT);
        }
        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0];
            return filterInOrder(FIRST_SLOT, prefix);
        }
        String headRaw = args[0];
        if (headRaw == null) {
            return List.of();
        }
        String head = headRaw.toLowerCase(Locale.ROOT);
        switch (head) {
            case "stats": {
                if (args.length == 2) {
                    return worldSuggestions(args[1], worldNames);
                }
                return List.of();
            }
            case "convert": {
                if (args.length == 2) {
                    return worldSuggestions(args[1], worldNames);
                }
                String prev = args[args.length - 2];
                String cur = args[args.length - 1] == null ? "" : args[args.length - 1];
                if (prev != null && prev.equalsIgnoreCase("--level")) {
                    return filterInOrder(LEVEL_VALUES, cur);
                }
                if (prev != null && prev.equalsIgnoreCase("--threads")) {
                    return filterInOrder(THREAD_VALUES, cur);
                }
                return filterInOrder(CONVERT_FLAGS, cur);
            }
            case "queue": {
                if (args.length == 2) {
                    String prefix = args[1] == null ? "" : args[1];
                    return filterInOrder(QUEUE_OPS, prefix);
                }
                if (args.length == 3) {
                    String opRaw = args[1];
                    if (opRaw == null) {
                        return List.of();
                    }
                    String op = opRaw.toLowerCase(Locale.ROOT);
                    if (op.equals("pause") || op.equals("resume") || op.equals("cancel")
                        || op.equals("status")) {
                        String prefix = args[2] == null ? "" : args[2];
                        return jobIdSuggestions(prefix, queue);
                    }
                    return List.of();
                }
                return List.of();
            }
            case "help":
            case "-h":
            case "--help":
            case "?": {
                return List.of();
            }
            default:
                return List.of();
        }
    }

    private static List<String> levelValues() {
        List<String> out = new ArrayList<>(22);
        for (int i = 1; i <= 22; i++) {
            out.add(String.valueOf(i));
        }
        return List.copyOf(out);
    }

    private static List<String> threadValues() {
        List<String> out = new ArrayList<>(8);
        for (int i = 1; i <= 8; i++) {
            out.add(String.valueOf(i));
        }
        return List.copyOf(out);
    }

    private static List<String> filterInOrder(List<String> candidates, String prefix) {
        String p = prefix == null ? "" : prefix;
        List<String> out = new ArrayList<>(candidates.size());
        for (String c : candidates) {
            if (c == null) {
                continue;
            }
            if (startsWithIgnoreCase(c, p)) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<String> worldSuggestions(String prefix, Supplier<Set<String>> worldNames) {
        try {
            if (worldNames == null) {
                return List.of();
            }
            Set<String> worlds = worldNames.get();
            if (worlds == null) {
                return List.of();
            }
            String p = prefix == null ? "" : prefix;
            List<String> out = new ArrayList<>();
            for (String w : worlds) {
                if (w == null) {
                    continue;
                }
                if (startsWithIgnoreCase(w, p)) {
                    out.add(w);
                }
            }
            Collections.sort(out);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> jobIdSuggestions(String prefix, JobQueue queue) {
        try {
            if (queue == null) {
                return List.of();
            }
            List<ConvertJob> jobs = queue.list();
            if (jobs == null) {
                return List.of();
            }
            String p = prefix == null ? "" : prefix;
            List<String> out = new ArrayList<>();
            for (ConvertJob job : jobs) {
                if (job == null) {
                    continue;
                }
                java.util.UUID id;
                try {
                    id = job.id();
                } catch (Exception e) {
                    continue;
                }
                if (id == null) {
                    continue;
                }
                String s = id.toString();
                if (startsWithIgnoreCase(s, p)) {
                    out.add(s);
                }
            }
            Collections.sort(out);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean startsWithIgnoreCase(String candidate, String prefix) {
        if (candidate == null || prefix == null) {
            return false;
        }
        if (prefix.isEmpty()) {
            return true;
        }
        if (prefix.length() > candidate.length()) {
            return false;
        }
        return candidate.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
