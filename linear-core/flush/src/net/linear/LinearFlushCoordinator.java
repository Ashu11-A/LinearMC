package net.linear;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-world ownership point for deferred Linear flushes.
 *
 * <p>One instance per storage folder, no owned threads, bounded dirty set,
 * and eviction on unload. The four rules below constrain the design
 * (per-world keying, caller-thread flushing, bounded tracking, eviction):</p>
 * <ol>
 *   <li><b>Keyed per world:</b> one instance per storage folder. Every
 *       {@code RegionFileStorage} (chunk / poi / entities) owns a distinct
 *       folder under its dimension path, so the folder key is exactly
 *       {@code (ServerLevel x RegionFileType)} without holding a hard
 *       reference to the level.</li>
 *   <li><b>One shared bounded pool, not Moonrise executors:</b> a single
 *       static {@code ThreadPoolExecutor} (daemon {@code linear-flush-*}
 *       threads, fixed size = {@code flush-max-threads}) is shared server-wide
 *       across all coordinators (9 coordinators on a 3-world server share one
 *       pool, not 9 pools). The joining barrier {@code flushDirty()} runs on
 *       the calling thread when {@code <=1} (serial, byte-identical to
 *       pre-patch) or on the pool + caller-participation when {@code >1}.
 *       Explicitly NOT a ForkJoinPool
 *       (managed-blocker compensation breaks the memory budget), NOT
 *       {@code invokeAll} (cancels on interrupt, truncating the durability
 *       barrier), and NOT Moonrise's pool (submit-and-block from those threads
 *       deadlocks). The caller always participates as a worker and joins
 *       before returning, so {@code flushDirty()} remains a durability
 *       barrier. (Fire-and-forget submissions — periodic drains and the
 *       {@code markDirty} hygiene paths — never join; forced barriers join
 *       them instead.)</li>
 *   <li><b>Bounded dirty set, first-dirty order:</b> at most {@link #MAX_DIRTY}
 *       files are tracked in a {@code LinkedHashMap} keyed by file identity
 *       with first-dirty nanos; repeat marks do NOT reorder.
 *       Exceeding the bound untracks the longest-unflushed file and drains it
 *       on the shared pool WITHOUT joining (the caller never blocks on file
 *       I/O; forced barriers join every in-flight batch). DELIBERATE change from
 *       least-recently-written victim to longest-unflushed; strictly more
 *       correct.</li>
 *   <li><b>Age-based flush:</b> {@code flushDirty()} flushes only files whose
 *       first-dirty age {@code >=} global {@code flush-frequency} seconds;
 *       younger files stay tracked. Eviction forces all.
 *       PLUS an opportunistic driver: every {@code markDirty} drains
 *       the longest-unflushed file off-thread when already age-eligible
 *       (bounded: at most one file per call, never joins the caller,
 *       production frequencies {@code >=1}s only). The driver exists because
 *       the Folia save path never calls {@code flushDirty} on its own
 *       (autosave and plain {@code save-all} bypass it); EDIT workloads
 *       therefore self-drain within ~frequency without manual saves.</li>
 *   <li><b>Unload eviction:</b> {@code RegionFileStorage.close()} calls
 *       {@link #evict(Path)}, which force-flushes remaining dirty files and
 *       drops the registry entry so worlds do not leak coordinators.</li>
 * </ol>
 *
 * <p>Durability model: {@code LinearRegionFile.write()} marks the file via
 * {@code isMarkedToSave()}; {@code RegionFileStorage} reports each write to
 * {@link #markDirty} (first-dirty timestamp kept), and the actual bytes hit
 * disk on the next age-eligible {@link #flushDirty()} (explicit
 * {@code save-all flush} / shutdown path), the opportunistic per-write age
 * flush, bound-pressure flush, or forced eviction.
 * Polling uses the region-file contract ({@code isMarkedToSave()} leaves
 * the flag set; the flag is cleared only after a successful flush).</p>
 */
public final class LinearFlushCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(LinearFlushCoordinator.class);

    /** Max tracked dirty files per storage folder before off-thread drain of the eldest. */
    public static final int MAX_DIRTY = 512;

    private static final ConcurrentHashMap<String, LinearFlushCoordinator> BY_FOLDER = new ConcurrentHashMap<>();

    // Linear - ONE shared bounded server-wide flush pool behind
    // flush-max-threads. NOT per-coordinator (9 coordinators on a 3-world
    // server would be 9 pools); NOT ForkJoinPool (managed-blocker compensation
    // breaks the memory budget); NOT Moonrise's pool (submit-and-block from
    // those threads deadlocks). Fixed-size ThreadPoolExecutor, daemon threads,
    // shared across all coordinators; caller participates and joins (barrier).
    private static final Object FLUSH_POOL_LOCK = new Object();
    private static java.util.concurrent.ExecutorService FLUSH_POOL = null;
    private static int FLUSH_POOL_THREADS = 0;
    // Test-only override (null = read the registered supplier).
    private static volatile Integer FLUSH_THREADS_OVERRIDE_FOR_TESTS = null;
    // Adapter-registered live readers (defaults = old null-config fallbacks:
    // 1 thread = serial, 10 s frequency). Suppliers keep runtime-reload
    // semantics: the adapter lambda reads live config on every call.
    private static volatile java.util.function.IntSupplier FLUSH_THREADS_SUPPLIER = () -> 1;
    private static volatile java.util.function.LongSupplier FLUSH_FREQUENCY_SUPPLIER = () -> 10L;

    /**
     * Flush-completion listener (adapter: Paper {@code LinearFlushBridge} event).
     * Default is a no-op; durability never depends on listeners.
     */
    @FunctionalInterface
    public interface FlushListener {
        void onFlushSync(String folderKey, int filesAttempted, long elapsedMicros);
    }

    private static volatile FlushListener FLUSH_LISTENER = (folderKey, filesAttempted, elapsedMicros) -> {
    };

    /** Adapter wiring: live {@code flush-max-threads} reader (registered once at startup). */
    public static void linear$setFlushThreadsSupplier(java.util.function.IntSupplier supplier) {
        FLUSH_THREADS_SUPPLIER = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    /** Adapter wiring: live {@code flush-frequency} reader (registered once at startup). */
    public static void linear$setFlushFrequencySupplier(java.util.function.LongSupplier supplier) {
        FLUSH_FREQUENCY_SUPPLIER = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    /** Adapter wiring: flush-completion event sink (registered once at startup). */
    public static void linear$setFlushListener(FlushListener listener) {
        FLUSH_LISTENER = java.util.Objects.requireNonNull(listener, "listener");
    }

    /**
     * Single notify choke point (both flush paths + tests). Listener failures
     * never propagate: durability is always complete before notification.
     * Catches {@code Error} as well as {@code RuntimeException} so a
     * misbehaving listener can neither break the task finally (which must
     * still unregister the batch) nor escape to the caller.
     */
    static void linear$notifyFlushSync(String folderKey, int filesAttempted, long elapsedMicros) {
        try {
            FLUSH_LISTENER.onFlushSync(folderKey, filesAttempted, elapsedMicros);
        } catch (RuntimeException | Error ignored) {
            // Event stays silent; durability already complete (Error covers
            // LinkageError and any other listener-thrown throwable).
        }
    }

    /** Per-folder singleton; creates on first use for a storage folder. */
    public static LinearFlushCoordinator forFolder(Path folder) {
        return BY_FOLDER.computeIfAbsent(key(folder), key -> new LinearFlushCoordinator(folder));
    }

    /**
     * Shared dirty check for conversion skip predicates (legs).
     *
     * <p>Returns {@code true} (treat as dirty: skip/defer) when a coordinator
     * entry exists for {@code folder} and its snapshot reports
     * {@code dirtyDepth != 0}, or on any key/snapshot exception. Unknown
     * folder (no entry yet) yields {@code false}: the coordinator observes
     * every Linear write through {@code markDirty}, so a never-tracked folder
     * has no unflushed Linear data by construction (boot conversion, fresh
     * test dirs, and idle dimensions all convert normally). A {@code null}
     * folder still yields {@code true} — callers cannot prove anything about
     * it, and the legs additionally guard null before calling.</p>
     *
     * <p>Residual (accepted, documented): an open-but-clean handle (tracked
     * before, currently clean) is invisible to this probe, same as before
     * skip predicates existed. The backstop is the shadow-pair policy, not
     * this check: a resurrected source escalates to FAILED/protection with
     * neither side deleted. Matching is case-sensitive on the absolute
     * normalized folder key (same key as {@link #forFolder}); this helper
     * itself never creates entries, so a pure probe cannot pollute the
     * registry.</p>
     */
    public static boolean isFolderDirty(Path folder) {
        if (folder == null) {
            return true;
        }
        try {
            LinearFlushCoordinator coordinator = BY_FOLDER.get(key(folder));
            if (coordinator == null) {
                return false;
            }
            LinearRegionTimings.LinearFolderSnapshot snapshot = coordinator.snapshot();
            if (snapshot == null) {
                return true;
            }
            return snapshot.dirtyDepth() != 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Flushes any remaining dirty files for {@code folder} and drops the
     * registry entry. Called from {@code RegionFileStorage.close()}.
     * Forces all files regardless of age (unload must not lose young writes).
     */
    public static void evict(Path folder) {
        LinearFlushCoordinator coordinator = BY_FOLDER.remove(key(folder));
        if (coordinator != null) {
            coordinator.flushDirty(true);
            coordinator.recordEvict(); // Linear: evict count (lock-free)
        }
    }

    /**
     * Linear - drains every tracked folder. Called from the server save path
     * ({@code ServerChunkCache.save}): {@code force} mirrors vanilla
     * flushStorage semantics (explicit save/stop forces everything to disk;
     * autosave stays age-gated, cheap when clean). Per-folder failures are
     * logged, never thrown: a save must not die on one bad folder.
     */
    public static void flushAllDirty(boolean force) {
        for (String folderKey : new ArrayList<>(BY_FOLDER.keySet())) {
            try {
                LinearFlushCoordinator coordinator = BY_FOLDER.get(folderKey);
                if (coordinator != null) {
                    coordinator.flushDirty(force);
                }
            } catch (RuntimeException e) {
                LOGGER.error("Failed to flush linear folder {}", folderKey, e);
            }
        }
    }

    /**
     * Linear - force-drains and drops every tracked folder. Called from
     * {@code ServerLevel.close()}: belt-and-braces stop drain so a clean
     * shutdown never loses young writes even if a save path was bypassed.
     * Idempotent (a second call finds an empty registry).
     */
    public static void evictAll() {
        java.util.List<LinearFlushCoordinator> all =
            new java.util.ArrayList<>(BY_FOLDER.values());
        BY_FOLDER.clear();
        for (LinearFlushCoordinator coordinator : all) {
            try {
                coordinator.flushDirty(true);
                coordinator.recordEvict();
            } catch (RuntimeException e) {
                LOGGER.error("Failed to evict linear folder {}", coordinator.folder, e);
            }
        }
        // Linear - async-split barrier net (B-RC): the per-folder forced
        // flushes above already join in-flight batches, but a periodic drain
        // may have submitted mid-evict; join the remainder so the stop barrier
        // never returns with an async batch in flight. Lock-free, never throws
        // (the join swallows per-task failures and restores interrupts).
        linear$joinInFlightAsync();
    }

    private static String key(Path folder) {
        return folder.toAbsolutePath().normalize().toString();
    }

    /**
     * Linear - resolves the shared pool size via the registered supplier
     * (adapter: global {@code region-format.linear.flush-max-threads},
     * post-processed, >=1). Supplier default yields 1 = serial, byte-identical.
     */
    static int linear$resolveFlushThreads() {
        Integer override = FLUSH_THREADS_OVERRIDE_FOR_TESTS;
        if (override != null) {
            return override;
        }
        try {
            return FLUSH_THREADS_SUPPLIER.getAsInt();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** Test-only: forces the pool size (null clears back to GlobalConfiguration). */
    static void linear$setFlushThreadsForTests(Integer threads) {
        FLUSH_THREADS_OVERRIDE_FOR_TESTS = threads;
    }

    /** Test-only: shuts the shared pool down and clears the overrides. */
    static void linear$resetFlushPoolForTests() {
        synchronized (FLUSH_POOL_LOCK) {
            if (FLUSH_POOL != null) {
                FLUSH_POOL.shutdown();
                FLUSH_POOL = null;
                FLUSH_POOL_THREADS = 0;
            }
            FLUSH_THREADS_OVERRIDE_FOR_TESTS = null;
        }
        FLUSH_FREQUENCY_SECONDS_OVERRIDE_FOR_TESTS = null;
        CACHED_FLUSH_FREQUENCY_NANOS_FOR_DIRTY = 10_000_000_000L;
        CACHED_FLUSH_FREQUENCY_AT_NANOS = 0L;
    }

    /**
     * Linear - resolves flush-frequency seconds (global
     * {@code region-format.linear.flush-frequency}, post-processed >=1).
     * Null config yields 10 (documented default). Test override wins.
     */
    static long linear$resolveFlushFrequencySeconds() {
        Long override = FLUSH_FREQUENCY_SECONDS_OVERRIDE_FOR_TESTS;
        if (override != null) {
            return override;
        }
        try {
            return FLUSH_FREQUENCY_SUPPLIER.getAsLong();
        } catch (RuntimeException e) {
            return 10L;
        }
    }

    /** Test-only: forces flush-frequency seconds (null clears). */
    static void linear$setFlushFrequencyForTests(Long seconds) {
        FLUSH_FREQUENCY_SECONDS_OVERRIDE_FOR_TESTS = seconds;
        // Keep the opportunistic markDirty driver deterministic in tests
        // (no 5s cache staleness across override changes).
        long now = System.nanoTime();
        if (seconds == null) {
            CACHED_FLUSH_FREQUENCY_AT_NANOS = 0L;
        } else {
            CACHED_FLUSH_FREQUENCY_NANOS_FOR_DIRTY = seconds <= 0L ? 0L : seconds * 1_000_000_000L;
            CACHED_FLUSH_FREQUENCY_AT_NANOS = now;
        }
    }

    /**
     * Linear - cached frequency nanos for the opportunistic
     * markDirty driver. Production frequencies ({@code >=1}s) enable the
     * driver; test-only {@code <=0} disables it (immediate drain stays on
     * explicit {@code flushDirty} only, preserving batch/pool path coverage).
     * Refreshed at most every 5s; races recompute the same value (benign).
     */
    static long linear$cachedFlushFrequencyNanos() {
        long now = System.nanoTime();
        long at = CACHED_FLUSH_FREQUENCY_AT_NANOS;
        if (at != 0L && now - at < 5_000_000_000L) {
            return CACHED_FLUSH_FREQUENCY_NANOS_FOR_DIRTY;
        }
        long seconds = linear$resolveFlushFrequencySeconds();
        long nanos = seconds <= 0L ? 0L : seconds * 1_000_000_000L;
        CACHED_FLUSH_FREQUENCY_NANOS_FOR_DIRTY = nanos;
        CACHED_FLUSH_FREQUENCY_AT_NANOS = now;
        return nanos;
    }

    /**
     * Linear - returns the shared pool, (re)creating it when
     * the requested size differs. Caller holds no lock while flushing; pool
     * creation is the only synchronized section.
     */
    private static java.util.concurrent.ExecutorService linear$sharedPool(int threads) {
        synchronized (FLUSH_POOL_LOCK) {
            if (FLUSH_POOL != null && FLUSH_POOL_THREADS == threads
                    && !FLUSH_POOL.isShutdown() && !FLUSH_POOL.isTerminated()) {
                return FLUSH_POOL;
            }
            if (FLUSH_POOL != null) {
                FLUSH_POOL.shutdown();
            }
            FLUSH_POOL_THREADS = threads;
            java.util.concurrent.ThreadFactory factory = r -> {
                Thread t = new Thread(r);
                t.setName("linear-flush-" + t.getId());
                t.setDaemon(true);
                return t;
            };
            // Fixed size = bounded concurrency. The queue itself is unbounded
            // but bounded in practice by the snapshot size (<= MAX_DIRTY*2);
            // bounding the queue with rejection would drop durability, so the
            // bound lives in the thread count, not the queue.
            FLUSH_POOL = new java.util.concurrent.ThreadPoolExecutor(
                threads, threads, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), factory);
            return FLUSH_POOL;
        }
    }

    // Linear - in-flight async batch tracking (async-split correctness net, B-RC).
    // An async task holds files ALREADY REMOVED from the dirty set, so a later
    // forced flush or evictAll would neither see nor wait for them. Every
    // submitted batch registers here and removes itself in the task finally;
    // the sync barriers (flushDirty(true), evictAll) join this set before
    // returning, so a clean stop stays zero-loss. DEADLOCK-FREE: the set is a
    // lock-free ConcurrentHashMap-backed set; joining takes NO lock (neither
    // the coordinator monitor nor FLUSH_POOL_LOCK) while blocked in
    // Future.get, and pool threads touch the set only via non-blocking
    // add/remove. The only lock pool threads can need is the brief
    // coordinator-monitor section in flushOne's failure re-queue, which the
    // join path never holds while waiting, so a gated/slow batch always
    // completes.
    private static final java.util.Set<java.util.concurrent.Future<?>> IN_FLIGHT_ASYNC =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * Linear - creates the self-unregistering ordered batch task and registers
     * it in {@code IN_FLIGHT_ASYNC} (see the field discipline above). Pure
     * construction plus a lock-free set add: no pool touch, no file I/O, safe
     * under the coordinator monitor. If construction throws, the caller still
     * holds its files tracked.
     */
    private java.util.concurrent.FutureTask<?> linear$createFlushTask(
            final List<AbstractRegionFile> snapshot) {
        final long batchStartNanos = System.nanoTime();
        final int filesAttempted = snapshot.size();
        final String folderKey = key(this.folder);
        // Self-unregistering holder: the array cell is assigned before any
        // submit/inline path, so the task finally never sees a null entry
        // (remove of an unregistered task is a no-op, never throws).
        final java.util.concurrent.FutureTask<?>[] holder = new java.util.concurrent.FutureTask<?>[1];
        final Runnable task = () -> {
            try {
                for (AbstractRegionFile file : snapshot) {
                    this.flushOne(file);
                }
            } finally {
                // Unregister FIRST: the listener below must never observe (or
                // join) a batch that already completed, and a listener throw
                // must not leak the registration.
                IN_FLIGHT_ASYNC.remove(holder[0]);
                final long elapsedMicros = (System.nanoTime() - batchStartNanos) / 1000L;
                linear$notifyFlushSync(folderKey, filesAttempted, elapsedMicros);
            }
        };
        holder[0] = new java.util.concurrent.FutureTask<Void>(task, null);
        IN_FLIGHT_ASYNC.add(holder[0]);
        return holder[0];
    }

    /**
     * Linear - ensures the pool and executes a tracked task, WITHOUT joining,
     * so the submitter (tick / I/O thread) never blocks. The pool is ensured
     * with a minimum of 1 thread, independent of the serial gate in
     * {@code flushDirty}: config 1 yields a single-thread ordered off-tick
     * drain, config >1 reuses the current shared pool. Pool-create/submit
     * failure falls back to inline execution (never dropped, {@code Error}
     * included). Must NOT be called holding the coordinator monitor: the
     * inline fallback performs file I/O.
     */
    private void linear$submitFlushTask(java.util.concurrent.FutureTask<?> tracked) {
        final java.util.concurrent.ExecutorService pool;
        try {
            pool = linear$sharedPool(Math.max(1, linear$resolveFlushThreads()));
        } catch (RuntimeException | Error poolFailed) {
            // Pool-create fallback: run inline so durability is not lost.
            tracked.run();
            return;
        }
        try {
            pool.execute(tracked);
        } catch (RuntimeException | Error submitFailed) {
            // Queue/reject fallback: untrack and run inline so durability is
            // not lost.
            IN_FLIGHT_ASYNC.remove(tracked);
            tracked.run();
        }
    }

    /**
     * Linear - joins every in-flight async batch (sync-barrier net, B-RC).
     * Snapshots the set and blocks in Future.get WITHOUT holding any lock
     * (see IN_FLIGHT_ASYNC discipline above), so pool threads always make
     * progress. Repeats while new batches arrive mid-join; terminates because
     * the forced barrier drains everything, leaving follow-up periodic
     * snapshots empty (no new submissions). Log-never-throw: per-batch
     * failures were already logged/counted by flushOne; interrupt status is
     * preserved, never propagated.
     */
    static void linear$joinInFlightAsync() {
        boolean interrupted = false;
        while (true) {
            java.util.List<java.util.concurrent.Future<?>> batch =
                new java.util.ArrayList<>(IN_FLIGHT_ASYNC);
            if (batch.isEmpty()) {
                break;
            }
            for (java.util.concurrent.Future<?> future : batch) {
                while (true) {
                    try {
                        future.get();
                        break;
                    } catch (InterruptedException ie) {
                        // Keep waiting (do NOT truncate the barrier); the flag
                        // is restored once the join completes below.
                        interrupted = true;
                    } catch (java.util.concurrent.ExecutionException ee) {
                        break;
                    } catch (RuntimeException re) {
                        break;
                    }
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Linear - static fire-and-forget drain across every tracked folder
     * (async split, B-RC). Periodic call sites (unload pass, autosave) submit
     * the age-eligible snapshot per folder and return WITHOUT joining, so the
     * tick never blocks. {@code force} mirrors flushDirty snapshot semantics
     * (forced takes all ages, else age-gated); submission is always async.
     * Per-folder failures are logged, never thrown.
     */
    public static void flushDirtyAsync(boolean force) {
        final long now = System.nanoTime();
        final long frequencyNanos;
        if (force) {
            frequencyNanos = 0L;
        } else {
            long seconds = linear$resolveFlushFrequencySeconds();
            if (seconds < 0L) {
                seconds = 10L;
            }
            frequencyNanos = seconds * 1_000_000_000L;
        }
        for (String folderKey : new ArrayList<>(BY_FOLDER.keySet())) {
            try {
                LinearFlushCoordinator coordinator = BY_FOLDER.get(folderKey);
                if (coordinator == null) {
                    continue;
                }
                final java.util.List<AbstractRegionFile> batch;
                final java.util.concurrent.FutureTask<?> tracked;
                synchronized (coordinator) {
                    if (coordinator.dirty.isEmpty()) {
                        continue;
                    }
                    if (force) {
                        batch = new ArrayList<>(coordinator.dirty.keySet());
                    } else {
                        batch = new ArrayList<>();
                        for (java.util.Map.Entry<AbstractRegionFile, Long> e
                                : coordinator.dirty.entrySet()) {
                            if (now - e.getValue() >= frequencyNanos) {
                                batch.add(e.getKey());
                            }
                        }
                        if (batch.isEmpty()) {
                            continue;
                        }
                    }
                    // Register before the untrack publishes (same critical
                    // section): a create failure leaves everything tracked,
                    // and a concurrent forced barrier can never miss a batch
                    // in flight.
                    tracked = coordinator.linear$createFlushTask(batch);
                    if (force) {
                        coordinator.dirty.clear();
                    } else {
                        for (AbstractRegionFile file : batch) {
                            coordinator.dirty.remove(file);
                        }
                    }
                }
                coordinator.linear$submitFlushTask(tracked);
            } catch (RuntimeException e) {
                LOGGER.error("Failed to flush linear folder {}", folderKey, e);
            }
        }
    }

    /**
     * Linear - production pool shutdown for the stop path (async split, B-RC).
     * Called after the joining stop drain. Uses shutdown() (NEVER shutdownNow:
     * an in-flight task must complete a torn image rewrite, not die mid-write)
     * plus a bounded 30s awaitTermination, then log-and-return. Idempotent (a
     * second call finds the pool already shut down) and never throws.
     * FLUSH_POOL_LOCK is held only to snapshot the reference; the await runs
     * OUTSIDE the lock so pool creation can never block on shutdown.
     */
    public static void shutdownFlushPool() {
        final java.util.concurrent.ExecutorService pool;
        synchronized (FLUSH_POOL_LOCK) {
            pool = FLUSH_POOL;
        }
        if (pool == null) {
            return;
        }
        try {
            pool.shutdown();
            if (!pool.awaitTermination(30L, java.util.concurrent.TimeUnit.SECONDS)) {
                LOGGER.warn("Linear flush pool did not terminate within 30s; stragglers retry next start via dirty-file replay");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while awaiting linear flush pool termination", ie);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to shut down linear flush pool", e);
        }
    }

    private final Path folder;
    // Linear - FIRST-dirty ordering (LinkedHashMap, no reorder
    // on repeat marks). Value is first-dirty nanos (System.nanoTime) for the
    // age-based flush. DELIBERATE change from least-recently-written
    // (remove+add tail) to longest-unflushed victim; strictly more correct.
    // Guarded by {@code this}.
    private final java.util.LinkedHashMap<AbstractRegionFile, Long> dirty = new java.util.LinkedHashMap<>();
    // Flush-frequency override for tests (null = read
    // GlobalConfiguration). Seconds, >=1 post-processed; nanos derived.
    private static volatile Long FLUSH_FREQUENCY_SECONDS_OVERRIDE_FOR_TESTS = null;
    // Linear - cached frequency nanos for the opportunistic markDirty
    // driver (volatile; refreshed at most every 5s; cleared with the override).
    private static volatile long CACHED_FLUSH_FREQUENCY_NANOS_FOR_DIRTY = 10_000_000_000L;
    private static volatile long CACHED_FLUSH_FREQUENCY_AT_NANOS = 0L;
    // Linear: lock-free folder-wide timing/counts. Hot updates use
    // LongAdder/LongAccumulator only (no synchronized, no locks); dirtyDepth is
    // the only value read under lock in snapshot().
    private final LongAdder readCount = new LongAdder();
    private final LongAdder readTotalMicros = new LongAdder();
    private final LongAccumulator readMaxMicros = new LongAccumulator(Long::max, 0L);
    private final LongAdder writeCount = new LongAdder();
    private final LongAdder writeTotalMicros = new LongAdder();
    private final LongAccumulator writeMaxMicros = new LongAccumulator(Long::max, 0L);
    private final LongAdder flushCount = new LongAdder();
    private final LongAdder flushTotalMicros = new LongAdder();
    private final LongAccumulator flushMaxMicros = new LongAccumulator(Long::max, 0L);
    private final LongAdder loadCount = new LongAdder();
    private final LongAdder loadTotalMicros = new LongAdder();
    private final LongAccumulator loadMaxMicros = new LongAccumulator(Long::max, 0L);
    private final LongAdder markDirtyCount = new LongAdder();
    private final LongAdder filesFlushedCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final LongAdder oversizeRejectCount = new LongAdder();
    private final LongAdder evictCount = new LongAdder();
    private final LongAdder cacheHitCount = new LongAdder();
    private final LongAdder cacheMissCount = new LongAdder();
    // Linear: measurement counters (lock-free only, no
    // synchronized on hot paths, no pool re-cut of recordFlush): summed flush
    // image bytes, bucketed flush latency for p50/p99, last-success timestamp.
    private final LongAdder rawBytesTotal = new LongAdder();
    private final LongAdder compressedBytesTotal = new LongAdder();
    private final LongAccumulator lastFlushSuccessNanos = new LongAccumulator(Long::max, 0L);
    // Linear: windowed flush-latency buckets (micros, upper bounds).
    // Two generations (current/previous) rotate when the current generation
    // exceeds 1024 success samples OR is older than 5 minutes, so one slow
    // flush pins p99 for at most one window instead of the process lifetime.
    // Choice rationale: 1024 bounds staleness under write bursts (a burst of
    // fast flushes evicts an ancient slow sample even within 5 min); 5 min
    // bounds wall-clock staleness on quiet servers; both are cheap to check
    // (one LongAdder sum + one nanoTime read per success). p50/p99 quantize
    // the current generation, falling back to the previous when the current
    // holds fewer than 10 samples (avoids noisy p99 on tiny windows).
    // Lifetime since-start totals/max stay in flushCount/flushTotalMicros/
    // flushMaxMicros; only the buckets are windowed.
    // 12 buckets cover 1ms..5s+MAX; recordFlushSuccess increments exactly
    // one current-generation bucket (LongAdder only). Snapshot walks one
    // generation for p50/p99 estimates.
    private static final long[] FLUSH_BUCKET_UPPER = new long[]{
        1_000L, 5_000L, 10_000L, 25_000L, 50_000L,
        100_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, Long.MAX_VALUE};
    /** Samples per generation before rotation (burst bound). */
    static final int FLUSH_WINDOW_SAMPLES = 1024;
    /** Generation age before rotation (quiet-server bound). */
    static final long FLUSH_WINDOW_NANOS = 5L * 60L * 1_000_000_000L;
    /** Minimum current-generation samples before p50/p99 trust it. */
    static final int FLUSH_WINDOW_MIN_SAMPLES = 10;
    /** Sentinel returned for the unbounded top bucket (5s + 1us, mirrors the old 1s + 1us pattern). */
    static final long FLUSH_TOP_SENTINEL_MICROS = 5_000_001L;
    private final LongAdder[] flushBucketsCur = newBucketSet();
    private final LongAdder[] flushBucketsPrev = newBucketSet();
    private final LongAdder flushWindowSamples = new LongAdder();
    private volatile long flushWindowStartNanos = System.nanoTime();
    private final Object flushWindowLock = new Object();

    private static LongAdder[] newBucketSet() {
        LongAdder[] set = new LongAdder[FLUSH_BUCKET_UPPER.length];
        for (int i = 0; i < set.length; i++) {
            set[i] = new LongAdder();
        }
        return set;
    }

    private LinearFlushCoordinator(Path folder) {
        this.folder = folder;
    }

    /**
     * Records a file with unflushed writes. Over-capacity evicts the eldest
     * entry for an off-thread drain (bounded memory: at most {@link #MAX_DIRTY}
     * files stay tracked), so write bursts cannot grow the tracked set
     * without bound and the caller never blocks on file I/O.
     *
     * <p>Linear - FIRST-dirty ordering (no reorder on repeat
     * marks). DELIBERATE change: the MAX_DIRTY victim is now the
     * longest-unflushed file, not the least-recently-written one. A hot region
     * rewritten every cycle keeps its original first-dirty timestamp and
     * therefore becomes the pressure victim first, which is strictly more
     * correct (oldest data at risk flushes first). Ship unconditionally.
     *
     * <p>Linear - opportunistic age flush. When no pressure victim
     * exists, the head (longest-unflushed) drains off-thread if already
     * age-eligible per flush-frequency (bounded: at most one file per call,
     * production frequencies {@code >=1}s only). This is the automatic driver
     * the Folia save path lacks: autosave and plain {@code save-all} never reach
     * {@code flushDirty}, so without this an EDIT workload accumulates dirty
     * files that only a manual {@code save-all flush} or shutdown would drain.
     *
     * <p>Linear - async hygiene (both victims): each victim leaves the dirty
     * set and drains on the shared pool WITHOUT joining, so the caller
     * (Moonrise I/O thread, region thread) never blocks on file I/O. The
     * batch is registered before the untrack publishes (same critical
     * section), so every forced barrier joins it; pool/submit failure runs
     * the batch inline, never dropped. See {@link #linear$createFlushTask}
     * and {@link #linear$submitFlushTask}.
     */
    public void markDirty(AbstractRegionFile file) {
        final long now = System.nanoTime();
        final long frequencyNanos = linear$cachedFlushFrequencyNanos();
        // At most one victim: the branches below are if/else-if.
        java.util.concurrent.FutureTask<?> drain = null;
        synchronized (this) {
            if (!this.dirty.containsKey(file)) {
                this.dirty.put(file, now);
            }
            // Repeat marks: no reorder, no timestamp refresh (first-dirty wins).
            if (this.dirty.size() > MAX_DIRTY) {
                java.util.Iterator<java.util.Map.Entry<AbstractRegionFile, Long>> it =
                    this.dirty.entrySet().iterator();
                AbstractRegionFile eldest = it.next().getKey();
                // Register before the untrack publishes: a create failure
                // leaves the file tracked, and a concurrent forced barrier
                // can never miss the batch in flight.
                drain = linear$createFlushTask(Collections.singletonList(eldest));
                it.remove();
            } else if (frequencyNanos > 0L && !this.dirty.isEmpty()) {
                // Opportunistic age flush (head only, one file per call).
                java.util.Iterator<java.util.Map.Entry<AbstractRegionFile, Long>> head =
                    this.dirty.entrySet().iterator();
                java.util.Map.Entry<AbstractRegionFile, Long> e = head.next();
                if (now - e.getValue() >= frequencyNanos) {
                    AbstractRegionFile aged = e.getKey();
                    drain = linear$createFlushTask(Collections.singletonList(aged));
                    head.remove();
                }
            }
        }
        // Linear: markDirty count outside sync (lock-free).
        this.markDirtyCount.increment();
        // Submit outside the monitor: on pool failure the submitter runs the
        // batch inline, which performs file I/O that must never hold it.
        if (drain != null) {
            linear$submitFlushTask(drain);
        }
    }

    /** Flushes dirty files old enough per flush-frequency. Called from storage save. */
    public void flushDirty() {
        this.flushDirty(false);
    }

    /**
     * Linear - REAL age-based flush behind flush-frequency.
     * Only files whose first-dirty age {@code >=} frequency seconds are
     * flushed; younger files stay tracked for the next save. {@code force}
     * (evict/unload) flushes everything regardless of age.
     */
    void flushDirty(boolean force) {
        // Linear - pre-barrier net (B-RC): join in-flight async batches BEFORE
        // the empty check. An async batch holds files already removed from the
        // dirty set, so isEmpty() would lie without this join. Lock-free join
        // (see IN_FLIGHT_ASYNC discipline); only the forced path pays it.
        if (force) {
            linear$joinInFlightAsync();
        }
        if (this.isEmpty()) {
            return;
        }
        final long now = System.nanoTime();
        final long frequencyNanos;
        if (force) {
            frequencyNanos = 0L;
        } else {
            long seconds = linear$resolveFlushFrequencySeconds();
            if (seconds < 0L) {
                seconds = 10L;
            }
            frequencyNanos = seconds * 1_000_000_000L;
        }
        final List<AbstractRegionFile> snapshot;
        synchronized (this) {
            if (this.dirty.isEmpty()) {
                return;
            }
            if (force) {
                snapshot = new ArrayList<>(this.dirty.keySet());
                this.dirty.clear();
            } else {
                snapshot = new ArrayList<>();
                java.util.Iterator<java.util.Map.Entry<AbstractRegionFile, Long>> it =
                    this.dirty.entrySet().iterator();
                while (it.hasNext()) {
                    java.util.Map.Entry<AbstractRegionFile, Long> e = it.next();
                    long age = now - e.getValue();
                    if (age >= frequencyNanos) {
                        snapshot.add(e.getKey());
                        it.remove();
                    }
                }
                if (snapshot.isEmpty()) {
                    return;
                }
            }
        }
        // Linear - <=1 keeps the pre-patch serial loop
        // byte-identical (no pool, no futures, no extra branches in the loop).
        // Linear - minecraft-0019: missing bridge call site.
        // Fires LinearRegionFlushCompletedEvent right after flushDirty drains,
        // via the Paper sync overload (no Plugin on this NMS path; autosave +
        // manual saves all funnel through here). filesAttempted = snapshot
        // size (>=1, clean-no-ops already returned above).
        final long batchStartNanos = System.nanoTime();
        final int filesAttempted = snapshot.size();
        try {
            final int threads = linear$resolveFlushThreads();
            if (threads <= 1 || snapshot.size() <= 1) {
                for (AbstractRegionFile file : snapshot) {
                    this.flushOne(file);
                }
                return;
            }
            // Parallel path (>1): ONE shared pool, caller participates as a worker
            // and joins before returning (durability barrier). NOT invokeAll
            // (cancels tasks on interrupt, truncating the barrier); individual
            // Future.get preserves every task. flushOne already records failures,
            // so ExecutionException needs no extra counting, just barrier progress.
            final java.util.concurrent.ExecutorService pool;
            try {
                pool = linear$sharedPool(threads);
            } catch (RuntimeException | Error fallback) {
                // Pool-create fallback (Error included): run serially inline
                // so durability is not lost.
                for (AbstractRegionFile file : snapshot) {
                    this.flushOne(file);
                }
                return;
            }
            final java.util.List<java.util.concurrent.Future<?>> futures =
                new java.util.ArrayList<>(snapshot.size() - 1);
            for (int i = 1; i < snapshot.size(); i++) {
                final AbstractRegionFile f = snapshot.get(i);
                try {
                    futures.add(pool.submit(() -> {
                        this.flushOne(f);
                        return null;
                    }));
                } catch (RuntimeException | Error submitFailed) {
                    // Queue/reject fallback (Error included): run inline so
                    // durability is not lost.
                    this.flushOne(f);
                }
            }
            // Caller work: first file inline (participation).
            this.flushOne(snapshot.get(0));
            boolean interrupted = false;
            for (java.util.concurrent.Future<?> fut : futures) {
                while (true) {
                    try {
                        fut.get();
                        break;
                    } catch (InterruptedException ie) {
                        // Do NOT cancel others (invokeAll would); keep waiting so
                        // the barrier is not truncated, re-interrupt at the end.
                        interrupted = true;
                    } catch (java.util.concurrent.ExecutionException ee) {
                        break;
                    } catch (RuntimeException re) {
                        break;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            final long elapsedMicros = (System.nanoTime() - batchStartNanos) / 1000L;
            linear$notifyFlushSync(key(this.folder), filesAttempted, elapsedMicros);
        }
        // Linear - post-barrier net (B-RC): the static fire-and-forget path
        // may have submitted an async batch while the forced barrier ran;
        // join it so the barrier never returns with an async batch in flight.
        linear$joinInFlightAsync();
    }

    public synchronized boolean isEmpty() {
        return this.dirty.isEmpty();
    }

    /** For tests/metrics only. */
    public synchronized int dirtyCount() {
        return this.dirty.size();
    }

    /**
     * Linear - test-only view of first-dirty order (head =
     * longest-unflushed). Returns files in dirty-set iteration order.
     */
    synchronized java.util.List<AbstractRegionFile> linear$dirtyOrderForTests() {
        return new java.util.ArrayList<>(this.dirty.keySet());
    }

    private void flushOne(AbstractRegionFile file) {
        boolean ok = false;
        try {
            if (file.isMarkedToSave()) {
                file.flush();
            }
            // Cleared only after a successful flush; a write racing between
            // flush() and clear re-marks the file and is picked up next pass.
            file.clearMarkedToSave();
            ok = true;
        } catch (Throwable ex) {
            // Throwable (not just Exception): an Error mid-batch must count
            // and re-queue like any failure instead of aborting the remaining
            // snapshot untracked.
            LOGGER.warn("Failed to flush linear region file for folder {}, will retry on next save", this.folder, ex);
            this.recordFailure(); // Linear: failure count (lock-free)
        }
        // Linear: filesFlushed only on success (failures retry
        // via the re-queue below, so they must not inflate filesFlushed).
        if (ok) {
            this.filesFlushedCount.increment();
            // Linear: last-success timestamp (lock-free max).
            this.lastFlushSuccessNanos.accumulate(System.nanoTime());
        }
        if (!ok) {
            // Re-queue (bounded) so a transient failure is retried instead of
            // silently dropping durability. The flag was NOT cleared above,
            // so no data is lost even if the re-queue itself overflows.
            // First-dirty ordering: keep original timestamp when present.
            synchronized (this) {
                if (this.dirty.size() < MAX_DIRTY * 2) {
                    this.dirty.putIfAbsent(file, System.nanoTime());
                }
            }
        }
    }

    // Linear: package-private hot record methods (same-package file
    // paths use these directly; RegionFileStorage uses the public report bridges
    // below because it lives in another package). Lock-free (LongAdder only).
    void recordRead(long micros) {
        this.readCount.increment();
        this.readTotalMicros.add(micros);
        this.readMaxMicros.accumulate(micros);
    }

    void recordWrite(long micros) {
        this.writeCount.increment();
        this.writeTotalMicros.add(micros);
        this.writeMaxMicros.accumulate(micros);
    }

    void recordFlush(long micros) {
        recordFlush(micros, true);
    }

    /**
     * Linear: success-only bucketed latency. Lifetime since-start totals/max
     * ({@code flushCount}/{@code flushTotalMicros}/{@code flushMaxMicros})
     * count every I/O flush (preserves existing count semantics); the
     * windowed p50/p99 buckets take successes only, mirroring the
     * success-only byte counters ({@code recordFlushBytes} runs after the
     * file move). Failed flushes retry via re-queue and must not poison p99.
     */
    void recordFlush(long micros, boolean success) {
        this.flushCount.increment();
        this.flushTotalMicros.add(micros);
        this.flushMaxMicros.accumulate(micros);
        if (!success) {
            return;
        }
        // Linear: windowed bucketed latency (lock-free, one bucket).
        // No pool re-cut: same threading, just one more adder.
        maybeRotateFlushWindow();
        for (int i = 0; i < FLUSH_BUCKET_UPPER.length; i++) {
            if (micros <= FLUSH_BUCKET_UPPER[i]) {
                this.flushBucketsCur[i].increment();
                break;
            }
        }
        this.flushWindowSamples.increment();
    }

    /**
     * Linear: rotates the latency window when the current generation exceeds
     * {@link #FLUSH_WINDOW_SAMPLES} success samples or {@link #FLUSH_WINDOW_NANOS}
     * age. The fast path (below both bounds) is lock-free; only the actual
     * rotation takes {@code flushWindowLock}. Best-effort under races: a sample
     * racing the copy may land in either generation, never lost from totals.
     */
    private void maybeRotateFlushWindow() {
        if (this.flushWindowSamples.sum() < FLUSH_WINDOW_SAMPLES
                && System.nanoTime() - this.flushWindowStartNanos < FLUSH_WINDOW_NANOS) {
            return;
        }
        synchronized (this.flushWindowLock) {
            if (this.flushWindowSamples.sum() < FLUSH_WINDOW_SAMPLES
                    && System.nanoTime() - this.flushWindowStartNanos < FLUSH_WINDOW_NANOS) {
                return;
            }
            rotateFlushWindowLocked();
        }
    }

    private void rotateFlushWindowLocked() {
        for (int i = 0; i < FLUSH_BUCKET_UPPER.length; i++) {
            this.flushBucketsPrev[i].reset();
            this.flushBucketsPrev[i].add(this.flushBucketsCur[i].sumThenReset());
        }
        this.flushWindowSamples.reset();
        this.flushWindowStartNanos = System.nanoTime();
    }

    /** Test-only: forces a window rotation regardless of age/sample bounds. */
    void linear$forceRotateFlushWindowForTests() {
        synchronized (this.flushWindowLock) {
            rotateFlushWindowLocked();
        }
    }

    void recordFlushBytes(long rawBytes, long compressedBytes) {
        // Linear: summed image bytes (lock-free).
        if (rawBytes > 0L) {
            this.rawBytesTotal.add(rawBytes);
        }
        if (compressedBytes > 0L) {
            this.compressedBytesTotal.add(compressedBytes);
        }
    }

    void recordLoad(long micros) {
        this.loadCount.increment();
        this.loadTotalMicros.add(micros);
        this.loadMaxMicros.accumulate(micros);
    }

    void recordEvict() {
        this.evictCount.increment();
    }

    void recordFailure() {
        this.failureCount.increment();
    }

    void recordOversizeReject() {
        this.oversizeRejectCount.increment();
    }

    void recordCacheHit() {
        this.cacheHitCount.increment();
    }

    void recordCacheMiss() {
        this.cacheMissCount.increment();
    }

    // Linear: public cross-package bridges for RegionFileStorage
    // (different package; delegates reporting to this coordinator so storage
    // holds zero new fields). Only the wired bridges are kept; dead
    // reportRead/Write/Flush/Load/Failure bridges are deleted (file-level
    // record* already forwards, so storage-level duplicates would double-count).
    public void reportCacheHit() {
        recordCacheHit();
    }

    public void reportCacheMiss() {
        recordCacheMiss();
    }

    public void reportOversizeReject() {
        recordOversizeReject();
    }

    /**
     * Linear: folder snapshot. dirtyDepth is read under lock;
     * all counters are read outside the lock (lock-free LongAdder sums).
     * Adds bytes, p50/p99 and millis-since-flush (same pattern).
     */
    public LinearRegionTimings.LinearFolderSnapshot snapshot() {
        final int depth;
        synchronized (this) {
            depth = this.dirty.size();
        }
        long[] cur = new long[FLUSH_BUCKET_UPPER.length];
        long curTotal = 0L;
        for (int i = 0; i < cur.length; i++) {
            cur[i] = this.flushBucketsCur[i].sum();
            curTotal += cur[i];
        }
        // Linear: quantize the current generation; fall back to the previous
        // when the current window is too small (<10 samples) so a fresh
        // window does not report noisy p99. After the next rotation the old
        // slow samples are dropped entirely (stuck-p99 fix).
        long[] buckets = cur;
        long totalFlush = curTotal;
        if (curTotal < FLUSH_WINDOW_MIN_SAMPLES) {
            long[] prev = new long[FLUSH_BUCKET_UPPER.length];
            long prevTotal = 0L;
            for (int i = 0; i < prev.length; i++) {
                prev[i] = this.flushBucketsPrev[i].sum();
                prevTotal += prev[i];
            }
            if (prevTotal > 0L) {
                buckets = prev;
                totalFlush = prevTotal;
            }
        }
        long p50 = bucketQuantile(buckets, totalFlush, 0.50);
        long p99 = bucketQuantile(buckets, totalFlush, 0.99);
        long lastNanos = this.lastFlushSuccessNanos.get();
        long millisSince = lastNanos <= 0L ? -1L : (System.nanoTime() - lastNanos) / 1_000_000L;
        if (millisSince < -1L) {
            millisSince = -1L;
        }
        return new LinearRegionTimings.LinearFolderSnapshot(
            LinearRegionTimings.LinearTimings.of(this.readCount, this.readTotalMicros, this.readMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.writeCount, this.writeTotalMicros, this.writeMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.flushCount, this.flushTotalMicros, this.flushMaxMicros),
            LinearRegionTimings.LinearTimings.of(this.loadCount, this.loadTotalMicros, this.loadMaxMicros),
            this.markDirtyCount.sum(),
            this.filesFlushedCount.sum(),
            this.failureCount.sum(),
            this.oversizeRejectCount.sum(),
            this.evictCount.sum(),
            this.cacheHitCount.sum(),
            this.cacheMissCount.sum(),
            depth,
            this.rawBytesTotal.sum(),
            this.compressedBytesTotal.sum(),
            p50,
            p99,
            millisSince);
    }

    // Linear: bucket upper-bound as pXX estimate (lock-free
    // read; 0 when no flushes yet). Conservative (upper bound) by design.
    // Each bucket returns its own upper bound (1s vs 8s stay distinguishable);
    // only the unbounded top bucket uses a sentinel (5_000_001).
    private static long bucketQuantile(long[] buckets, long total, double q) {
        if (total <= 0L) {
            return 0L;
        }
        long need = (long) Math.ceil(total * q);
        if (need < 1L) {
            need = 1L;
        }
        long acc = 0L;
        for (int i = 0; i < buckets.length; i++) {
            acc += buckets[i];
            if (acc >= need) {
                long upper = FLUSH_BUCKET_UPPER[i];
                return upper == Long.MAX_VALUE ? FLUSH_TOP_SENTINEL_MICROS : upper;
            }
        }
        return 0L;
    }

    /**
     * Linear linearstats: per-folder snapshots keyed by absolute folder-path
     * string (same key as BY_FOLDER). Pull-only view for the linearstats
     * command; no new threads, no extra locking beyond snapshot()'s
     * dirty-depth read.
     */
    public static java.util.Map<String, LinearRegionTimings.LinearFolderSnapshot> snapshots() {
        java.util.Map<String, LinearRegionTimings.LinearFolderSnapshot> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, LinearFlushCoordinator> e : BY_FOLDER.entrySet()) {
            out.put(e.getKey(), e.getValue().snapshot());
        }
        return out;
    }

    /**
     * Linear: explicit test-only reset. The ONLY mutator that
     * clears counters (lifecycle/dirty set untouched).
     */
    public void resetForTests() {
        this.readCount.reset();
        this.readTotalMicros.reset();
        this.readMaxMicros.reset();
        this.writeCount.reset();
        this.writeTotalMicros.reset();
        this.writeMaxMicros.reset();
        this.flushCount.reset();
        this.flushTotalMicros.reset();
        this.flushMaxMicros.reset();
        this.loadCount.reset();
        this.loadTotalMicros.reset();
        this.loadMaxMicros.reset();
        this.markDirtyCount.reset();
        this.filesFlushedCount.reset();
        this.failureCount.reset();
        this.oversizeRejectCount.reset();
        this.evictCount.reset();
        this.cacheHitCount.reset();
        this.cacheMissCount.reset();
        this.rawBytesTotal.reset();
        this.compressedBytesTotal.reset();
        for (LongAdder b : this.flushBucketsCur) {
            b.reset();
        }
        for (LongAdder b : this.flushBucketsPrev) {
            b.reset();
        }
        this.flushWindowSamples.reset();
        this.flushWindowStartNanos = System.nanoTime();
        this.lastFlushSuccessNanos.reset();
    }
}
