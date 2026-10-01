package net.linear;

import java.util.Objects;

/**
 * Eager class warm-up: forces load + initialization of named classes on the
 * calling thread (the enable thread), so steady-state region/tick threads
 * never pay class-load, JiJ nested-jar read, or Mixin/transform costs.
 *
 * <p>Every name is attempted independently: missing classes are skipped
 * (counted, never thrown), so one absent class on some MC line cannot break
 * enable. Uses {@code Class.forName(name, true, loader)} — initialize=true
 * matters, a bare {@code X.class} literal or method reference does NOT run
 * static initializers.
 */
public final class LinearWarmup {

    /** Outcome of one warm-up pass. */
    public record Result(int loaded, int skipped, long millis) {
    }

    private LinearWarmup() {
    }

    /**
     * Loads + initializes each named class with {@code loader}.
     *
     * @return how many loaded vs skipped, plus wall time
     */
    public static Result touch(final ClassLoader loader, final String... classNames) {
        Objects.requireNonNull(loader, "loader");
        final long start = System.nanoTime();
        int loaded = 0;
        int skipped = 0;
        if (classNames != null) {
            for (final String name : classNames) {
                if (name == null || name.isBlank()) {
                    skipped++;
                    continue;
                }
                try {
                    Class.forName(name, true, loader);
                    loaded++;
                } catch (final LinkageError | RuntimeException missed) {
                    skipped++;
                } catch (final ClassNotFoundException missed) {
                    skipped++;
                }
            }
        }
        final long millis = (System.nanoTime() - start) / 1_000_000L;
        return new Result(loaded, skipped, millis);
    }

    /**
     * Convenience over {@link #touch(ClassLoader, String...)} using the
     * loader of {@code anchor}.
     */
    public static Result touch(final Class<?> anchor, final String... classNames) {
        Objects.requireNonNull(anchor, "anchor");
        return touch(anchor.getClassLoader(), classNames);
    }
}
