package net.linear.save;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.linear.LinearFlushCoordinator;
import net.linear.io.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code save-all flush} as a real barrier with a completion marker.
 *
 * <p>A leg opens a {@link Ticket}, registers one task per unit of work it
 * schedules (a region section on the region-threaded forks, one synchronous
 * save on Paper), reports each finished task, and seals the ticket after the
 * last task is scheduled. The seal guard means a task that finishes before the
 * leg is done scheduling can never complete the ticket early.</p>
 *
 * <p>When every task is done, a {@code flush} ticket first waits for every
 * chunk write in flight at that moment to reach its region file (the leg's
 * fence {@link CoupledSave.Host}, when it passed one: region tasks that only
 * <em>queue</em> chunk saves, like a coupled capture, are covered here), then
 * for the coupled player saves already staged ({@link CoupledSave}, when on),
 * then runs the forced coordinator barrier ({@link LinearFlushCoordinator#flushAllDirty(boolean)}
 * with {@code force}: it joins every flush already running, renamed and
 * directory-synced) on its own thread, never a region thread, then replaces
 * {@code <level>/linear-checkpoint/save-all.done} atomically with
 * {@code {"id","startMs","endMs","regions","failed"}}, then calls the leg's
 * completion callback. A marker that exists with a given id therefore means
 * every region file written by that save-all is on disk. {@code failed}
 * counts regions whose save threw plus Linear flush failures recorded while
 * the save ran; any value above 0 means "do not trust this cut, retry".</p>
 *
 * <p>Ids increase across restarts: the first ticket continues from the id in
 * the existing marker.</p>
 */
public final class SaveAllBarrier {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    public static final String MARKER_DIR = "linear-checkpoint";
    public static final String MARKER_FILE = "save-all.done";

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Object ID_LOCK = new Object();
    private static long lastId;

    /**
     * Default wait budget of a flush save-all on the forks (barrier thread,
     * never a tick thread): chunk I/O fences plus staged coupled player
     * saves. Past it the save-all reports failure.
     */
    public static final long DEFAULT_WAIT_MILLIS = 60_000L;
    /** Longest a flush ticket waits for the chunk I/O fences (within the budget). */
    static final long FENCE_WAIT_MILLIS = 30_000L;

    /** The forced barrier after the fences: argument = what is left of the wait budget, in ms. */
    @FunctionalInterface
    interface Barrier {
        void run(long remainingMillis);
    }

    /**
     * Coupled player saves staged by this save-all (or before it) land first
     * (each behind its own region barrier), then the forced barrier over
     * every folder. A withheld coupled save fails the save-all.
     */
    private static final Barrier DEFAULT_BARRIER = remainingMillis -> {
        try {
            CoupledSave.awaitSubmitted(Math.max(0L, remainingMillis));
        } finally {
            LinearFlushCoordinator.flushAllDirty(true);
        }
    };
    private static volatile Barrier barrier = DEFAULT_BARRIER;

    private SaveAllBarrier() {
    }

    public static Path markerPath(final Path levelRoot) {
        return levelRoot.resolve(MARKER_DIR).resolve(MARKER_FILE);
    }

    /** Opens a ticket. {@code flush=false} tickets complete without barrier or marker. */
    public static Ticket begin(final Path levelRoot, final boolean flush) {
        return begin(levelRoot, flush, null, DEFAULT_WAIT_MILLIS);
    }

    /**
     * Opens a ticket. {@code fences} (may be {@code null}): the leg's chunk
     * I/O fences, waited for before the forced barrier of a flush ticket.
     * {@code waitMillis}: budget for the fences plus the staged coupled
     * player saves; a host that waits on its tick thread passes a small one.
     */
    public static Ticket begin(final Path levelRoot, final boolean flush, final CoupledSave.Host fences,
                               final long waitMillis) {
        final long id;
        synchronized (ID_LOCK) {
            id = Math.max(lastId, readMarkerId(levelRoot)) + 1L;
            lastId = id;
        }
        return new Ticket(levelRoot, flush, id, System.currentTimeMillis(), totalFlushFailures(), fences,
            Math.max(0L, waitMillis));
    }

    /** Id stored in the marker, 0 when it is absent or unreadable. */
    static long readMarkerId(final Path levelRoot) {
        if (levelRoot == null) {
            return 0L;
        }
        try {
            final Path marker = markerPath(levelRoot);
            if (!Files.isRegularFile(marker)) {
                return 0L;
            }
            final Matcher m = ID.matcher(Files.readString(marker, StandardCharsets.UTF_8));
            return m.find() ? Long.parseLong(m.group(1)) : 0L;
        } catch (IOException | RuntimeException unreadable) {
            return 0L;
        }
    }

    private static long totalFlushFailures() {
        return LinearFlushCoordinator.totalFailures();
    }

    /** Test-only: replaces the forced coordinator barrier ({@code null} restores it). */
    public static void setBarrierForTests(final Runnable replacement) {
        barrier = replacement == null ? DEFAULT_BARRIER : remaining -> replacement.run();
    }

    /** Test-only: forgets the issued ids. */
    public static void resetIdsForTests() {
        synchronized (ID_LOCK) {
            lastId = 0L;
        }
    }

    /** What the marker records. */
    public record Result(long id, long startMs, long endMs, int regions, int failed, boolean flush,
                         boolean markerWritten) {

        public String toJson() {
            return "{\"id\":" + this.id + ",\"startMs\":" + this.startMs + ",\"endMs\":" + this.endMs
                + ",\"regions\":" + this.regions + ",\"failed\":" + this.failed + "}\n";
        }

        /** One line for the console and the command sender. */
        public String summary() {
            return "save-all " + this.id + ": " + this.regions + " region(s), " + this.failed + " failed, "
                + (this.endMs - this.startMs) + " ms"
                + (this.flush ? (this.markerWritten ? ", marker written" : ", MARKER NOT WRITTEN") : "");
        }
    }

    public static final class Ticket {
        private final Path levelRoot;
        private final boolean flush;
        private final long id;
        private final long startMs;
        private final long failuresAtStart;
        private final CoupledSave.Host fences;
        private final long waitMillis;
        /** Starts at 1: the seal guard, released by {@link #seal}. */
        private final AtomicInteger pending = new AtomicInteger(1);
        private final AtomicInteger failed = new AtomicInteger();
        private final Set<Object> regions = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        private volatile Consumer<Result> onComplete;

        Ticket(final Path levelRoot, final boolean flush, final long id, final long startMs, final long failuresAtStart,
               final CoupledSave.Host fences, final long waitMillis) {
            this.levelRoot = levelRoot;
            this.flush = flush;
            this.id = id;
            this.startMs = startMs;
            this.failuresAtStart = failuresAtStart;
            this.fences = fences;
            this.waitMillis = waitMillis;
        }

        public long id() {
            return this.id;
        }

        public boolean flush() {
            return this.flush;
        }

        /** Call before scheduling each task; every call needs one {@link #taskDone()}. */
        public void taskQueued() {
            this.pending.incrementAndGet();
        }

        /**
         * First claim of {@code region} (identity) in this ticket: true means
         * "save it now"; later tasks of the same region skip the save. A
         * {@code null} region (no region context) is always claimed.
         */
        public boolean claimRegion(final Object region) {
            return region == null ? this.regions.add(new Object()) : this.regions.add(region);
        }

        /** A region save threw; the marker reports it in {@code failed}. */
        public void regionFailed() {
            this.failed.incrementAndGet();
        }

        public void taskDone() {
            if (this.pending.decrementAndGet() == 0) {
                this.finish();
            }
        }

        /**
         * Releases the seal guard after the last {@link #taskQueued()}.
         * {@code onComplete} runs once, on the barrier thread for a flush
         * ticket, on the thread that finished the last task otherwise.
         */
        public void seal(final Consumer<Result> onComplete) {
            this.onComplete = Objects.requireNonNull(onComplete);
            this.taskDone();
        }

        private void finish() {
            if (!this.flush) {
                this.deliver(new Result(this.id, this.startMs, System.currentTimeMillis(), this.regions.size(),
                    this.failed.get(), false, false));
                return;
            }
            Thread.ofPlatform().daemon().name("LinearMC-save-all-" + this.id).start(this::runBarrier);
        }

        private void runBarrier() {
            final long waitStart = System.nanoTime();
            if (this.fences != null) {
                // Every region task is done, so every chunk save this save-all
                // made is queued: wait until each write in flight reached its
                // region file, or the forced flush below could miss it.
                final String fenceFailure = CoupledSave.awaitFences(this.fences,
                    Math.min(FENCE_WAIT_MILLIS, this.waitMillis));
                if (fenceFailure != null) {
                    LOGGER.error("save-all {}: chunk writes did not land ({}); the marker reports failed", this.id,
                        fenceFailure);
                    this.failed.incrementAndGet();
                }
            }
            final long remaining = this.waitMillis
                - java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStart);
            try {
                barrier.run(remaining);
            } catch (RuntimeException e) {
                LOGGER.error("save-all {}: forced Linear barrier failed", this.id, e);
                this.failed.incrementAndGet();
            }
            final long flushFailures = Math.max(0L, totalFlushFailures() - this.failuresAtStart);
            final int failedTotal = (int) Math.min(Integer.MAX_VALUE, this.failed.get() + flushFailures);
            final long endMs = System.currentTimeMillis();
            final Result unsaved = new Result(this.id, this.startMs, endMs, this.regions.size(), failedTotal, true, false);
            boolean written = false;
            if (this.levelRoot != null) {
                try {
                    AtomicFiles.write(markerPath(this.levelRoot), unsaved.toJson().getBytes(StandardCharsets.UTF_8));
                    written = true;
                } catch (IOException | RuntimeException e) {
                    LOGGER.error("save-all {}: could not write {}", this.id, markerPath(this.levelRoot), e);
                }
            }
            this.deliver(new Result(this.id, this.startMs, endMs, unsaved.regions(), failedTotal, true, written));
        }

        private void deliver(final Result result) {
            if (result.failed() > 0) {
                LOGGER.warn("{}", result.summary());
            } else {
                LOGGER.info("{}", result.summary());
            }
            try {
                this.onComplete.accept(result);
            } catch (RuntimeException e) {
                LOGGER.warn("save-all {}: completion callback failed", this.id, e);
            }
        }
    }
}
