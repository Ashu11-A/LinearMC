package net.linear.save;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import net.linear.LinearFlushCoordinator;
import net.linear.io.AtomicFiles;
import net.linear.io.DirectoryFsync;
import net.linear.io.PlayerFileDurability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coupled save order ({@code coupled-save.enabled}, default on): a player
 * file never becomes durable before the chunks of its region.
 *
 * <p>Chunk data and player data reach the disk on two independent clocks
 * (chunk autosave plus the Linear age gate, and the periodic player save).
 * When a host stalls, a player file that is newer than its chunks loses
 * whatever the player put into the world in between: the item has left the
 * inventory on disk but has not reached the chunk on disk. With the flag on,
 * the legs run one <em>capture</em> per Folia region (per server on a
 * non-region host) every {@code coupled-save.interval} seconds, in that
 * region's tick:</p>
 * <ol>
 *   <li>every chunk holder the region owns is saved, with no per-tick limit
 *       (the saves are scheduled on the chunk I/O thread as usual);</li>
 *   <li>the region's players are saved inside {@link #begin(Host)}: their
 *       {@code .dat}, stats and advancements are serialized in memory and
 *       <em>staged</em> instead of written ({@link #stageDat},
 *       {@link #stageText}); nothing touches the disk on the region
 *       thread;</li>
 *   <li>{@link Capture#close()} lists every chunk I/O task in flight
 *       ({@link Host#snapshotInFlight}, which includes the saves of step 1)
 *       and hands the capture to the coupled-save thread.</li>
 * </ol>
 * <p>That thread waits until every listed I/O task has handed its data to
 * the region file ({@link Host#fenceState}), then runs the forced storage
 * flush ({@link Host#flushStorages}: the rank 2 barrier, which joins
 * flushes already running and returns after the rename and the directory
 * fsync), and only then writes the staged files atomically
 * (temp + force + {@code ATOMIC_MOVE}, then one directory fsync per
 * directory per batch). A failed or timed-out barrier withholds the player
 * files and retries the whole barrier with backoff; a newer capture of the
 * same file supersedes an older one, a newer direct write supersedes both,
 * and a capture whose files were all superseded completes without another
 * barrier.</p>
 *
 * <p>Logout, plugin {@code Player#saveData} and {@code save-all} go through
 * the same capture, and a player who leaves a region (cross-region teleport,
 * portal, respawn) triggers a <em>departure</em> capture on the source
 * region thread first: the destination region saves only the chunks it
 * owns, and the source region, now without players, skips its periodic
 * capture. While the flag is on the independent periodic player autosave is
 * skipped. A login (or {@code loadData}) that happens while a
 * save of that player is still staged reads the staged bytes
 * ({@link #stagedBytes}), never the older file.</p>
 *
 * <p><b>What this guarantees, and what it does not.</b> The player file on
 * disk is never newer than the chunks of the region it was captured in. An
 * item placed or deposited before a crash is therefore at worst in both the
 * chunk and the inventory (a duplicate), never in neither (this repo's
 * 2026-11-25 incident). The reverse direction stays open: an item mined,
 * withdrawn or picked up can still be lost, because a chunk can become
 * durable after the capture (unload, eviction, heartbeat) while the player
 * file waits for the next one. It is not an atomic cross-region
 * transaction: each region is its own cut, so an item that crosses regions
 * between two captures (teleport, item entity, portal) can still be in both
 * or neither. A region that splits while its player walks away leaves the
 * player-less part uncaptured until its chunk autosave (no departure hook
 * fires on a split). Paths that do not run a capture (stop, a plugin calling
 * {@code saveData} off the player's thread, {@code Server#savePlayers})
 * write directly, as before, and only supersede staged saves.</p>
 *
 * <p>Cost: one burst per region per interval on the region thread (chunk
 * serialization that autosave would do later anyway, plus in-memory player
 * serialization); the disk work runs on the coupled-save thread. The
 * not-due path of {@link #due} is one lock-free map read with a per-thread
 * probe key and allocates nothing.</p>
 */
public final class CoupledSave {

    private static final Logger LOGGER = LoggerFactory.getLogger("LinearMC");

    public static final long DEFAULT_INTERVAL_SECONDS = 20L;

    /**
     * Which region formats the ordering covers, logged at enable (same text
     * as docs/limitations.md). Linear/B_LINEAR: atomic rename plus directory
     * fsync per file. Anvil: the forced storage flush calls
     * {@code RegionFile.flush}, which fsyncs each open {@code .mca}
     * ({@code FileChannel.force}); an evicted one was forced by its close.
     */
    public static final String FORMAT_SCOPE = "LINEAR and B_LINEAR region files (atomic rename + directory fsync) and"
        + " ANVIL .mca files (forced flush fsyncs every open region file); for ANVIL an in-place sector write can"
        + " still tear on power loss and an oversized-chunk .mcc rename is not directory-synced";

    /** {@link Host#fenceState} results. */
    public static final int DONE = 1;
    public static final int PENDING = 0;
    public static final int FAILED = -1;

    /** Host adapter: chunk I/O fences and the forced storage flush. Thread-safe. */
    public interface Host {
        /** Adds every chunk I/O task (any world, any storage type) in flight right now. */
        void snapshotInFlight(List<Object> out);

        /**
         * {@link #DONE} when the task has handed its data to the region
         * file (or was replaced by a newer task), {@link #FAILED} when its
         * write failed and the chunk system kept it for a retry,
         * {@link #PENDING} otherwise.
         */
        int fenceState(Object task);

        /** Forced flush of every region/entities/poi storage (the rank 2 barrier). */
        void flushStorages() throws IOException;
    }

    /** An I/O step that may throw. */
    @FunctionalInterface
    public interface IoAction {
        void run() throws IOException;
    }

    private static volatile boolean enabled;
    private static volatile long intervalNanos = TimeUnit.SECONDS.toNanos(DEFAULT_INTERVAL_SECONDS);
    private static volatile boolean shuttingDown;

    /** Next due time per region key (identity; regions die on merge/split, so weak). */
    /**
     * Next due time per region (identity, weak: regions die on merge/split).
     * Lock-free: a concurrent map keyed by weak region references, read with
     * a per-thread probe (no allocation on the not-due path); dead regions
     * are expunged from {@link #DEAD_REGIONS} when a new region is first
     * seen. {@link #UNSEEN} marks a slot not yet due-checked.
     */
    private static final ConcurrentHashMap<Object, AtomicLong> NEXT_DUE = new ConcurrentHashMap<>();
    private static final java.lang.ref.ReferenceQueue<Object> DEAD_REGIONS = new java.lang.ref.ReferenceQueue<>();
    private static final ThreadLocal<RegionProbe> PROBE = ThreadLocal.withInitial(RegionProbe::new);
    /** Host without regions (Paper): the whole server is one slot, no map lookup. */
    private static final AtomicLong SERVER_DUE = new AtomicLong(Long.MIN_VALUE);
    private static final long UNSEEN = Long.MIN_VALUE;

    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();

    /** Newest staged save per target file (absolute, normalized). */
    private static final ConcurrentHashMap<Path, Staged> OVERLAY = new ConcurrentHashMap<>();
    /**
     * Serializes writes of the SAME player file while staged saves exist
     * (worker vs direct), striped by target path: a write never waits behind
     * the fsync of another player's file.
     */
    private static final Object[] WRITE_LOCKS = new Object[64];

    static {
        for (int i = 0; i < WRITE_LOCKS.length; i++) {
            WRITE_LOCKS[i] = new Object();
        }
    }

    private static Object writeLock(final Path key) {
        return WRITE_LOCKS[(key.hashCode() & 0x7fffffff) % WRITE_LOCKS.length];
    }

    private static final Set<Capture> PENDING_CAPTURES = ConcurrentHashMap.newKeySet();
    private static final Object EXECUTOR_LOCK = new Object();
    private static ScheduledThreadPoolExecutor executor;
    /** Set by {@link #finishShutdown}: later captures write directly. */
    private static boolean executorClosed;

    private static volatile long fenceTimeoutNanos = TimeUnit.SECONDS.toNanos(30L);
    private static volatile long retryBaseMillis = 1000L;
    private static final long RETRY_MAX_MILLIS = 30_000L;
    private static final long SLOW_CAPTURE_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);
    private static volatile LongConsumer writeObserver;
    /**
     * {@code -Dlinearmc.coupled-save.debug=true}: one INFO line per barrier
     * and per staged file written, read by {@code scripts/coupled-order-check.py}
     * ({@code [coupled-save-debug] barrier capture=<id> endMs=<ms> files=<n>},
     * {@code [coupled-save-debug] write capture=<id> barrierEndMs=<ms> ms=<ms> file=<path>}).
     * Off by default; diagnostics only (a line per player file per interval).
     */
    static volatile boolean debug = Boolean.getBoolean("linearmc.coupled-save.debug");

    /** {@link Capture#attempt()} result: fences still pending, poll again (not a failure). */
    private static final String WAITING = new String("waiting");

    private static final AtomicLong CAPTURES = new AtomicLong();
    private static final AtomicLong FILES_WRITTEN = new AtomicLong();
    private static final AtomicLong BARRIER_FAILURES = new AtomicLong();
    /** Withhold age after which a retrying capture logs at ERROR instead of WARN. */
    static final long WITHHOLD_ERROR_SECONDS = 60L;
    private static volatile long lastCaptureNanos;
    private static volatile long maxCaptureNanos;
    private static volatile long lastBarrierNanos;

    private CoupledSave() {
    }

    // ---------------------------------------------------------------- config

    /** Rejects an interval below 1 s (logs and uses the default). */
    public static long resolveIntervalSeconds(final long seconds, final java.util.function.Consumer<String> error) {
        if (seconds < 1L) {
            if (error != null) {
                error.accept("[coupled-save] interval must be >= 1 second, got " + seconds + ". Falling back to "
                    + DEFAULT_INTERVAL_SECONDS + ".");
            }
            return DEFAULT_INTERVAL_SECONDS;
        }
        return seconds;
    }

    /** Published by each leg's config load ({@code coupled-save.enabled}, {@code coupled-save.interval}). */
    public static void configure(final boolean enable, final long intervalSeconds) {
        intervalNanos = TimeUnit.SECONDS.toNanos(Math.max(1L, intervalSeconds));
        enabled = enable;
        if (enable) {
            LOGGER.info("Coupled save order on: players are saved after their region's chunks are durable, every {} s"
                + " per region, or per server on a host without regions (periodic player autosave off)", intervalSeconds);
            LOGGER.info("Coupled save scope: {}", FORMAT_SCOPE);
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    public static long intervalSeconds() {
        return TimeUnit.NANOSECONDS.toSeconds(intervalNanos);
    }

    /** Whether a leg should run captures now: enabled and the server is not stopping. */
    public static boolean active() {
        return enabled && !shuttingDown;
    }

    /**
     * Per-tick check of the region whose tick is running ({@code null} on a
     * host without regions). True at most once per interval per region, and
     * at once for a region not seen before (a region born from a split must
     * not wait a whole interval while the periodic player save is off).
     */
    public static boolean due(final Object regionKey) {
        if (!enabled || shuttingDown) {
            return false;
        }
        final long now = System.nanoTime();
        if (regionKey == null) {
            return claimDue(SERVER_DUE, now);
        }
        final RegionProbe probe = PROBE.get();
        probe.region = regionKey;
        AtomicLong next;
        try {
            next = NEXT_DUE.get(probe);
        } finally {
            probe.region = null;
        }
        if (next == null) {
            expungeDeadRegions();
            final AtomicLong fresh = new AtomicLong(UNSEEN);
            final AtomicLong raced = NEXT_DUE.putIfAbsent(new RegionRef(regionKey, DEAD_REGIONS), fresh);
            next = raced == null ? fresh : raced;
        }
        return claimDue(next, now);
    }

    /** True for the caller that moves {@code slot} past {@code now} (first sight or interval elapsed). */
    private static boolean claimDue(final AtomicLong slot, final long now) {
        final long next = slot.get();
        if (next != UNSEEN && now - next < 0L) {
            return false;
        }
        return slot.compareAndSet(next, now + intervalNanos);
    }

    private static void expungeDeadRegions() {
        for (java.lang.ref.Reference<?> dead; (dead = DEAD_REGIONS.poll()) != null;) {
            NEXT_DUE.remove(dead);
        }
    }

    /** Regions tracked by {@link #due} (tests: dead regions are dropped). */
    static int trackedRegionsForTests() {
        expungeDeadRegions();
        return NEXT_DUE.size();
    }

    /** Weak map key: identity of the region, hash fixed at creation. */
    private static final class RegionRef extends java.lang.ref.WeakReference<Object> {
        private final int hash;

        RegionRef(final Object region, final java.lang.ref.ReferenceQueue<Object> queue) {
            super(region, queue);
            this.hash = System.identityHashCode(region);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(final Object other) {
            if (other == this) {
                return true;
            }
            final Object region = this.get();
            if (region == null) {
                return false;
            }
            if (other instanceof RegionProbe probe) {
                return probe.region == region;
            }
            return other instanceof RegionRef ref && ref.get() == region;
        }
    }

    /** Per-thread lookup key so the not-due path allocates nothing. */
    private static final class RegionProbe {
        Object region;

        @Override
        public int hashCode() {
            return System.identityHashCode(this.region);
        }

        @Override
        public boolean equals(final Object other) {
            if (other == this) {
                return true;
            }
            return other instanceof RegionRef ref && this.region != null && ref.get() == this.region;
        }
    }

    // --------------------------------------------------------------- capture

    /**
     * Opens a staging capture on this thread. Player saves made until
     * {@link Capture#close()} are staged instead of written. Nested calls
     * join the outer capture.
     */
    public static Capture begin(final Host host) {
        final Capture outer = CURRENT.get();
        if (outer != null) {
            return outer.nested();
        }
        final Capture capture = new Capture(java.util.Objects.requireNonNull(host, "host"));
        CURRENT.set(capture);
        return capture;
    }

    /** Whether player saves on this thread are being staged. */
    public static boolean capturing() {
        return CURRENT.get() != null;
    }

    /** Stages a player {@code .dat} (compressed NBT bytes); {@code backup} becomes the previous save. */
    public static void stageDat(final Path real, final Path backup, final byte[] compressedNbt) {
        stage(real, java.util.Objects.requireNonNull(backup, "backup"), compressedNbt);
    }

    /** Stages a stats or advancements file. */
    public static void stageText(final Path target, final byte[] utf8) {
        stage(target, null, utf8);
    }

    private static void stage(final Path target, final Path backup, final byte[] data) {
        final Capture capture = CURRENT.get();
        if (capture == null) {
            throw new IllegalStateException("no coupled-save capture on this thread");
        }
        final Path key = key(target);
        final Staged staged = new Staged(key, backup == null ? null : backup.toAbsolutePath().normalize(), data);
        OVERLAY.put(key, staged);
        capture.staged.add(staged);
    }

    /**
     * The staged (not yet written) bytes of {@code file}, or {@code null}.
     * Read paths use these first: they are newer than the file on disk.
     */
    public static byte[] stagedBytes(final Path file) {
        if (OVERLAY.isEmpty() || file == null) {
            return null;
        }
        final Staged staged = OVERLAY.get(key(file));
        return staged == null ? null : staged.data;
    }

    /**
     * A write outside any capture (stop, off-thread {@code saveData}, the
     * vanilla paths). It runs under the coupled write lock and, once it
     * succeeded, drops any staged save of the same file, which is older, so
     * the coupled-save thread can never land an older save over it.
     */
    public static void writeDirect(final Path target, final IoAction write) throws IOException {
        if (!enabled && OVERLAY.isEmpty()) {
            write.run();
            return;
        }
        final Path key = key(target);
        synchronized (writeLock(key)) {
            // Only a save staged before this write started is older than it. A
            // capture may stage a newer save of the same file while a slow
            // direct write runs (staging takes no lock); that one must survive.
            final Staged prior = OVERLAY.get(key);
            write.run();
            if (prior != null) {
                OVERLAY.remove(key, prior);
            }
        }
    }

    private static Path key(final Path file) {
        return file.toAbsolutePath().normalize();
    }

    // ----------------------------------------------------------------- drain

    /**
     * Waits until every capture submitted before this call has written its
     * files (or was superseded). Throws when one is still withheld after
     * {@code timeoutMillis}. Never call it on a region thread.
     */
    public static void awaitSubmitted(final long timeoutMillis) {
        final List<Capture> pending = new ArrayList<>(PENDING_CAPTURES);
        if (pending.isEmpty()) {
            return;
        }
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        int withheld = 0;
        for (final Capture capture : pending) {
            try {
                capture.done.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (final TimeoutException | ExecutionException e) {
                withheld++;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                withheld++;
            }
        }
        if (withheld > 0) {
            throw new IllegalStateException(withheld + " coupled player save(s) still withheld after " + timeoutMillis
                + " ms (their region barrier keeps failing)");
        }
    }

    /**
     * Blocking fence wait for a {@code save-all flush} ticket: lists every
     * chunk I/O task in flight now ({@link Host#snapshotInFlight}) and waits
     * until each one has handed its data to the region file. Returns
     * {@code null} when all are done, else why not (a failed write, the
     * timeout, an unreadable task map). Polls with a short backoff (500 us
     * doubling to 20 ms). Never call it on a region thread.
     */
    public static String awaitFences(final Host host, final long timeoutMillis) {
        final List<Object> fences = new ArrayList<>();
        try {
            host.snapshotInFlight(fences);
        } catch (final RuntimeException e) {
            return "could not list the chunk I/O in flight: " + e;
        }
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long pollNanos = TimeUnit.MICROSECONDS.toNanos(500L);
        while (true) {
            for (int i = fences.size() - 1; i >= 0; i--) {
                final int state;
                try {
                    state = host.fenceState(fences.get(i));
                } catch (final RuntimeException e) {
                    return "could not read a chunk I/O task: " + e;
                }
                if (state == DONE) {
                    fences.remove(i);
                } else if (state == FAILED) {
                    return "a chunk write failed and is waiting for its retry";
                }
            }
            if (fences.isEmpty()) {
                return null;
            }
            if (System.nanoTime() - deadline > 0L) {
                return fences.size() + " chunk I/O task(s) still running after " + timeoutMillis + " ms";
            }
            java.util.concurrent.locks.LockSupport.parkNanos(pollNanos);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return "interrupted while waiting for chunk I/O";
            }
            pollNanos = Math.min(pollNanos * 2L, TimeUnit.MILLISECONDS.toNanos(20L));
        }
    }

    /** Stop, step 1: no new captures; saves from here on are direct. */
    public static void beginShutdown() {
        shuttingDown = true;
    }

    /**
     * Stop, step 2 (after the host's final chunk save): lets pending captures
     * finish for up to {@code timeoutMillis}, then writes whatever is still
     * staged directly (with an ERROR: their barrier did not complete) and
     * stops the coupled-save thread. Idempotent, never throws.
     * <p>Ordering limit: this runs after the host's chunk save but BEFORE the
     * worlds' region files are closed (and the Linear flush pool is already
     * shut down), so these leftover writes, like the stop's direct player
     * saves, can reach the disk before the last region flush. No .dat/region
     * ordering is guaranteed for a crash DURING stop (docs/limitations.md).
     */
    public static void finishShutdown(final long timeoutMillis) {
        shuttingDown = true;
        try {
            awaitSubmitted(timeoutMillis);
        } catch (final RuntimeException e) {
            LOGGER.error("Coupled save: {}; writing the staged player files directly at stop", e.getMessage());
        }
        final List<Staged> left = new ArrayList<>(OVERLAY.values());
        for (final Staged staged : left) {
            try {
                staged.write(null);
            } catch (final IOException | RuntimeException e) {
                LOGGER.error("Coupled save: could not write staged {} at stop", staged.path, e);
            }
        }
        synchronized (EXECUTOR_LOCK) {
            executorClosed = true;
            if (executor != null) {
                // shutdown(), not shutdownNow(): an interrupt inside
                // flushStorages would close a region FileChannel
                // (ClosedByInterruptException). Bounded wait, then interrupt.
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(Math.max(1_000L, Math.min(timeoutMillis, 5_000L)), TimeUnit.MILLISECONDS)) {
                        LOGGER.error("Coupled save: the coupled-save thread did not stop in time; interrupting it");
                        executor.shutdownNow();
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                }
                executor = null;
            }
        }
        PENDING_CAPTURES.clear();
    }

    // ----------------------------------------------------------------- stats

    /** Captures whose player files are withheld behind a failed region barrier (retrying). */
    public static int withheldCaptures() {
        int retrying = 0;
        for (final Capture capture : PENDING_CAPTURES) {
            if (capture.failedAttempts > 0) {
                retrying++;
            }
        }
        return retrying;
    }

    /** One line for logs and tests: captures, files written, failures, timings. */
    public static String statsLine() {
        int retrying = 0;
        long oldestNanos = 0L;
        final long now = System.nanoTime();
        for (final Capture capture : PENDING_CAPTURES) {
            if (capture.failedAttempts > 0) {
                retrying++;
                oldestNanos = Math.max(oldestNanos, now - capture.startNanos);
            }
        }
        return "captures=" + CAPTURES.get() + " filesWritten=" + FILES_WRITTEN.get() + " barrierFailures="
            + BARRIER_FAILURES.get() + " staged=" + OVERLAY.size() + " withheldCaptures=" + retrying
            + " oldestWithheldS=" + TimeUnit.NANOSECONDS.toSeconds(oldestNanos) + " lastCaptureMs="
            + TimeUnit.NANOSECONDS.toMillis(lastCaptureNanos) + " maxCaptureMs="
            + TimeUnit.NANOSECONDS.toMillis(maxCaptureNanos) + " lastBarrierMs="
            + TimeUnit.NANOSECONDS.toMillis(lastBarrierNanos);
    }

    // ------------------------------------------------------------ test seams

    /** Test-only: fence wait limit before a capture counts as failed. */
    public static void setFenceTimeoutForTests(final long millis) {
        fenceTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(millis);
    }

    /** Test-only: first retry delay (doubles per attempt, capped at 30 s). */
    public static void setRetryBaseForTests(final long millis) {
        retryBaseMillis = millis;
    }

    /** Test-only: called with the write sequence number each time a staged file lands. */
    public static void setWriteObserverForTests(final LongConsumer observer) {
        writeObserver = observer;
    }

    /** Test-only: back to the boot state. */
    public static void resetForTests() {
        synchronized (EXECUTOR_LOCK) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            executorClosed = false;
        }
        enabled = false;
        shuttingDown = false;
        intervalNanos = TimeUnit.SECONDS.toNanos(DEFAULT_INTERVAL_SECONDS);
        fenceTimeoutNanos = TimeUnit.SECONDS.toNanos(30L);
        retryBaseMillis = 1000L;
        writeObserver = null;
        NEXT_DUE.clear();
        SERVER_DUE.set(UNSEEN);
        OVERLAY.clear();
        PENDING_CAPTURES.clear();
        CURRENT.remove();
        CAPTURES.set(0L);
        FILES_WRITTEN.set(0L);
        BARRIER_FAILURES.set(0L);
        lastCaptureNanos = 0L;
        maxCaptureNanos = 0L;
        lastBarrierNanos = 0L;
    }

    // ---------------------------------------------------------------- worker

    /** The coupled-save thread, or {@code null} after {@link #finishShutdown}. */
    private static ScheduledThreadPoolExecutor executor() {
        synchronized (EXECUTOR_LOCK) {
            if (executorClosed) {
                return null;
            }
            if (executor == null) {
                final ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(1, runnable -> {
                    final Thread thread = new Thread(runnable, "LinearMC-coupled-save");
                    thread.setDaemon(true);
                    return thread;
                });
                created.setRemoveOnCancelPolicy(true);
                executor = created;
            }
            return executor;
        }
    }

    private static void submit(final Capture capture) {
        final ScheduledThreadPoolExecutor worker = executor();
        if (worker == null) {
            // After the stop drain: every chunk is saved and flushed already.
            capture.writeNow();
            return;
        }
        PENDING_CAPTURES.add(capture);
        try {
            worker.execute(() -> run(capture));
        } catch (final RuntimeException rejected) {
            PENDING_CAPTURES.remove(capture);
            capture.writeNow();
        }
    }

    private static void run(final Capture capture) {
        final String failure;
        try {
            failure = capture.attempt();
        } catch (final RuntimeException | Error e) {
            reschedule(capture, "unexpected " + e);
            return;
        }
        if (failure == null) {
            PENDING_CAPTURES.remove(capture);
            capture.done.complete(null);
            return;
        }
        if (failure == WAITING) {
            // Chunk I/O still running: poll again soon without holding the
            // thread, so one region's slow I/O never delays another's capture.
            final ScheduledThreadPoolExecutor worker = executor();
            if (worker != null) {
                try {
                    worker.schedule(() -> run(capture), capture.nextPollMicros(), TimeUnit.MICROSECONDS);
                    return;
                } catch (final RuntimeException rejected) {
                    // Stopping: finishShutdown writes what is still staged.
                }
            }
            PENDING_CAPTURES.remove(capture);
            capture.done.complete(null);
            return;
        }
        reschedule(capture, failure);
    }

    private static void reschedule(final Capture capture, final String failure) {
        BARRIER_FAILURES.incrementAndGet();
        // The next attempt starts over: it lists the chunk I/O in flight again.
        capture.fenceDeadline = 0L;
        final int attempt = ++capture.failedAttempts;
        final long delay = Math.min(RETRY_MAX_MILLIS, retryBaseMillis << Math.min(attempt - 1, 15));
        if (attempt == 1 || attempt % 10 == 0) {
            final long withheldSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - capture.startNanos);
            if (withheldSeconds >= WITHHOLD_ERROR_SECONDS) {
                // Long withhold: a host crash now rolls these players back to their last written file.
                LOGGER.error("Coupled save: {} player file(s) withheld for {} s ({}); attempt {}, retrying the region"
                        + " barrier in {} ms. A crash now loses their progress since the last written file: fix the"
                        + " region write failure (disk full, permissions) or turn coupled-save off",
                    capture.staged.size(), withheldSeconds, failure, attempt, delay);
            } else {
                LOGGER.warn("Coupled save: {} player file(s) withheld ({}); attempt {}, retrying the region barrier in {} ms",
                    capture.staged.size(), failure, attempt, delay);
            }
        }
        final ScheduledThreadPoolExecutor worker = executor();
        try {
            if (worker != null) {
                worker.schedule(() -> run(capture), delay, TimeUnit.MILLISECONDS);
                return;
            }
        } catch (final RuntimeException rejected) {
            // Stopping: fall through, finishShutdown wrote or writes what is still staged.
        }
        PENDING_CAPTURES.remove(capture);
        capture.done.complete(null);
    }

    /** One staging capture; see the class comment. */
    public static final class Capture implements AutoCloseable {
        private static final AtomicLong IDS = new AtomicLong();
        /** Debug log id ({@link #DEBUG}). */
        final long id = IDS.incrementAndGet();
        private final Host host;
        private final long startNanos = System.nanoTime();
        final List<Staged> staged = new ArrayList<>();
        private final List<Object> fences = new ArrayList<>();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        private boolean closed;
        private boolean refence;
        /** Set while waiting for the fences of one attempt; 0 between attempts. */
        private long fenceDeadline;
        private long pollMicros;
        volatile int failedAttempts;
        private final Capture outer;

        Capture(final Host host) {
            this.host = host;
            this.outer = null;
        }

        private Capture(final Capture outer) {
            this.host = outer.host;
            this.outer = outer;
            this.closed = false;
        }

        Capture nested() {
            return new Capture(this);
        }

        /**
         * Ends staging on this thread, lists the chunk I/O in flight (which
         * includes the chunk saves made before this call) and hands the
         * capture to the coupled-save thread. A capture that staged nothing
         * is dropped.
         */
        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            if (this.outer != null) {
                return;
            }
            CURRENT.remove();
            final long elapsed = System.nanoTime() - this.startNanos;
            lastCaptureNanos = elapsed;
            if (elapsed > maxCaptureNanos) {
                maxCaptureNanos = elapsed;
            }
            if (elapsed > SLOW_CAPTURE_NANOS) {
                LOGGER.debug("Coupled save: capture took {} ms on {}", TimeUnit.NANOSECONDS.toMillis(elapsed),
                    Thread.currentThread().getName());
            }
            if (this.staged.isEmpty()) {
                return;
            }
            CAPTURES.incrementAndGet();
            try {
                this.host.snapshotInFlight(this.fences);
            } catch (final RuntimeException e) {
                LOGGER.warn("Coupled save: could not list chunk I/O in flight ({}); the barrier lists it again", e.toString());
                this.fences.clear();
                this.refence = true;
            }
            submit(this);
        }

        /** Stop path only: writes without the barrier (the stop already saved and flushed every chunk). */
        void writeNow() {
            for (final Staged file : this.staged) {
                try {
                    file.write(null);
                } catch (final IOException | RuntimeException e) {
                    LOGGER.error("Coupled save: could not write staged {} at stop", file.path, e);
                }
            }
            this.staged.clear();
            this.done.complete(null);
        }

        /** Number of staged files (tests). */
        public int stagedCount() {
            return this.staged.size();
        }

        /** Fences, forced flush, writes. Returns null on success, else why it must be retried. */
        String attempt() {
            if (this.fenceDeadline == 0L) {
                // A newer capture or a direct write superseded these files: they
                // will never be written by this capture, so it has nothing left to
                // order. Without this, a barrier that keeps failing would retry the
                // global flush once per superseded capture.
                for (int i = this.staged.size() - 1; i >= 0; i--) {
                    final Staged file = this.staged.get(i);
                    if (OVERLAY.get(file.path) != file) {
                        this.staged.remove(i);
                    }
                }
                if (this.staged.isEmpty()) {
                    return null;
                }
                if (this.refence) {
                    // A retry re-lists the I/O in flight: a failed write the chunk
                    // system replaced since is waited for through its new task.
                    this.fences.clear();
                    this.host.snapshotInFlight(this.fences);
                }
                this.refence = true;
                this.fenceDeadline = System.nanoTime() + fenceTimeoutNanos;
                this.pollMicros = 0L;
            }
            final String fenceState = this.checkFences();
            if (fenceState == WAITING) {
                return WAITING;
            }
            this.fenceDeadline = 0L;
            if (fenceState != null) {
                return fenceState;
            }
            final long start = System.nanoTime();
            // Failures are counted only for the coordinators this barrier
            // flushed (its host's levels): a failing folder elsewhere (another
            // world, a flush on another thread) does not withhold these files.
            try (LinearFlushCoordinator.FailureScope scope = LinearFlushCoordinator.openFailureScope()) {
                try {
                    this.host.flushStorages();
                } catch (final IOException | RuntimeException e) {
                    return "forced region flush failed: " + e;
                }
                if (scope.failures() > 0L) {
                    return "a Linear region flush failed during the barrier";
                }
            }
            lastBarrierNanos = System.nanoTime() - start;
            final long barrierEndMs = System.currentTimeMillis();
            if (debug) {
                LOGGER.info("[coupled-save-debug] barrier capture={} endMs={} files={}", this.id, barrierEndMs,
                    this.staged.size());
            }
            // Each file is forced before its rename; the directories are synced
            // once per batch (a player's .dat, stats and advancements live in
            // three directories, and several players share them).
            final List<Path> dirs = new ArrayList<>(4);
            try {
                for (int i = 0; i < this.staged.size(); ) {
                    final Staged file = this.staged.get(i);
                    try {
                        final boolean written = file.write(dirs);
                        this.staged.remove(i);
                        if (debug && written) {
                            LOGGER.info("[coupled-save-debug] write capture={} barrierEndMs={} ms={} file={}", this.id,
                                barrierEndMs, System.currentTimeMillis(), file.path);
                        }
                    } catch (final IOException | RuntimeException e) {
                        return "writing " + file.path.getFileName() + " failed: " + e;
                    }
                }
            } finally {
                for (int i = 0; i < dirs.size(); i++) {
                    DirectoryFsync.syncQuietly(dirs.get(i));
                }
            }
            return null;
        }

        /** One non-blocking pass: null when every fence is done, {@link #WAITING}, or a failure. */
        private String checkFences() {
            for (int i = this.fences.size() - 1; i >= 0; i--) {
                final int state = this.host.fenceState(this.fences.get(i));
                if (state == DONE) {
                    this.fences.remove(i);
                } else if (state == FAILED) {
                    return "a chunk write failed and is waiting for its retry";
                }
            }
            if (this.fences.isEmpty()) {
                return null;
            }
            if (System.nanoTime() - this.fenceDeadline > 0L) {
                return this.fences.size() + " chunk I/O task(s) still running after "
                    + TimeUnit.NANOSECONDS.toMillis(fenceTimeoutNanos) + " ms";
            }
            return WAITING;
        }

        /** Poll delay while fences are pending: 500 us doubling to 20 ms. */
        long nextPollMicros() {
            this.pollMicros = this.pollMicros == 0L ? 500L : Math.min(this.pollMicros * 2L, 20_000L);
            return this.pollMicros;
        }
    }

    /** One staged file: the newest bytes of {@code path} until written. */
    static final class Staged {
        private static final AtomicLong SEQUENCE = new AtomicLong();

        final Path path;
        final Path backup;
        final byte[] data;

        Staged(final Path path, final Path backup, final byte[] data) {
            this.path = path;
            this.backup = backup;
            this.data = data;
        }

        /**
         * Writes it unless a newer save superseded it. Under the write lock
         * stripe of its path.
         * The data is forced before the rename; {@code deferredDirs}
         * collects the directory for one fsync per batch, {@code null}
         * syncs it now.
         */
        boolean write(final List<Path> deferredDirs) throws IOException {
            synchronized (writeLock(this.path)) {
                if (OVERLAY.get(this.path) != this) {
                    return false;
                }
                final boolean syncNow = deferredDirs == null;
                if (this.backup == null) {
                    AtomicFiles.write(this.path, this.data, true, syncNow);
                } else {
                    writeDat(this.path, this.backup, this.data, syncNow);
                }
                if (!syncNow) {
                    final Path dir = this.path.getParent();
                    if (dir != null && !deferredDirs.contains(dir)) {
                        deferredDirs.add(dir);
                    }
                }
                OVERLAY.remove(this.path, this);
                FILES_WRITTEN.incrementAndGet();
                final LongConsumer observer = writeObserver;
                if (observer != null) {
                    observer.accept(SEQUENCE.incrementAndGet());
                }
                return true;
            }
        }

        /** Same temp name shape as vanilla, so the boot quarantine recognizes an orphan. */
        private static void writeDat(final Path real, final Path backup, final byte[] data, final boolean syncDir)
            throws IOException {
            final String name = real.getFileName().toString();
            final String prefix = (name.endsWith(".dat") ? name.substring(0, name.length() - 4) : name) + "-";
            final Path dir = real.getParent();
            Files.createDirectories(dir);
            final Path tmp = Files.createTempFile(dir, prefix, ".dat");
            try {
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    final ByteBuffer buffer = ByteBuffer.wrap(data);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                }
                PlayerFileDurability.moveIntoPlace(real, tmp, backup, true, syncDir);
            } catch (final IOException | RuntimeException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        }
    }
}
