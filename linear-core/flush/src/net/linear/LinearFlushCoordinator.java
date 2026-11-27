package net.linear;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.linear.flush.FlushLatencyWindow;
import net.linear.flush.FlushSnapshotRegistry;
import net.linear.flush.ParallelFlushFanout;

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
 *
 * <p>Thin facade: pool/fan-out state lives in {@link ParallelFlushFanout},
 * timing state in {@link FlushLatencyWindow} (field {@code timings}), and the
 * requeue policy in {@link FlushSnapshotRegistry}. Every signature below is
 * unchanged; facades delegate.</p>
 */
public final class LinearFlushCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(LinearFlushCoordinator.class);

    /** Max tracked dirty files per storage folder before off-thread drain of the eldest. */
    public static final int MAX_DIRTY = 512;

    /** Window aliases (canonical values in {@link FlushLatencyWindow}; kept for compat). */
    static final int FLUSH_WINDOW_SAMPLES = FlushLatencyWindow.FLUSH_WINDOW_SAMPLES;
    static final int FLUSH_WINDOW_MIN_SAMPLES = FlushLatencyWindow.FLUSH_WINDOW_MIN_SAMPLES;

    private static final ConcurrentHashMap<String, LinearFlushCoordinator> BY_FOLDER = new ConcurrentHashMap<>();

    /** Fast-path cache for {@link #forFolder}; entries die with their coordinator. */
    private static final ConcurrentHashMap<Path, LinearFlushCoordinator> BY_PATH = new ConcurrentHashMap<>();

    /**
     * Shutdown mode: set once from the server stop barrier before the final
     * drains. While set, markDirty only tracks (never spawns pressure/age
     * victim batches). Idempotent; cleared by test reset.
     */
    private static volatile boolean SHUTDOWN_MODE = false;

    /** Stop-barrier hook: defer all hygiene drains to the final forced drain. */
    public static void linear$beginShutdown() {
        SHUTDOWN_MODE = true;
        // The stop barrier is the only drainer from here on.
        net.linear.flush.FlushHeartbeat.stop();
    }

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
        ParallelFlushFanout.setFlushThreadsSupplier(supplier);
    }

    /** Adapter wiring: live {@code flush-frequency} reader (registered once at startup). */
    public static void linear$setFlushFrequencySupplier(java.util.function.LongSupplier supplier) {
        ParallelFlushFanout.setFlushFrequencySupplier(supplier);
    }

    /** Adapter wiring: flush-completion event sink (registered once at startup). */
    public static void linear$setFlushListener(FlushListener listener) {
        FLUSH_LISTENER = java.util.Objects.requireNonNull(listener, "listener");
    }

    /**
     * Single notify choke point (both flush paths + tests). Listener failures
     * never propagate: durability is always complete before notification.
     */
    public static void linear$notifyFlushSync(String folderKey, int filesAttempted, long elapsedMicros) {
        try {
            FLUSH_LISTENER.onFlushSync(folderKey, filesAttempted, elapsedMicros);
        } catch (RuntimeException | Error ignored) {
        }
    }

    /** Per-folder singleton; creates on first use for a storage folder. */
    public static LinearFlushCoordinator forFolder(Path folder) {
        // Hot path (every chunk I/O and region-cache lookup): Path-keyed fast
        // map first (Path caches its hash), the normalized string key only on
        // a miss. A coordinator dropped by evict/evictAll is never returned.
        final LinearFlushCoordinator fast = BY_PATH.get(folder);
        if (fast != null && !fast.evicted) {
            return fast;
        }
        final LinearFlushCoordinator coordinator =
            BY_FOLDER.computeIfAbsent(key(folder), key -> new LinearFlushCoordinator(folder));
        BY_PATH.put(folder, coordinator);
        // Background durability (crash/kill safety): first tracked Linear
        // folder starts the shared heartbeat; idempotent and cheap after.
        if (!SHUTDOWN_MODE) {
            net.linear.flush.FlushHeartbeat.ensureStarted(LinearFlushCoordinator::linear$heartbeatTick);
        }
        return coordinator;
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
            coordinator.evicted = true;
            BY_PATH.values().removeIf(c -> c == coordinator);
            coordinator.flushDirty(true);
            for (int i = 0; i < 2 && !coordinator.isEmpty(); i++) {
                coordinator.flushDirty(true);
            }
            coordinator.recordEvict();
        }
    }

    /**
     * Drains every tracked folder. Called from the server save path:
     * {@code force} mirrors vanilla flushStorage semantics (explicit
     * save/stop forces everything; autosave stays age-gated). Per-folder
     * failures are logged, never thrown.
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
     * Force-drains and drops every tracked folder. Called from
     * {@code ServerLevel.close()}. Idempotent.
     */
    public static void evictAll() {
        List<LinearFlushCoordinator> all =
            new ArrayList<>(BY_FOLDER.values());
        BY_FOLDER.clear();
        for (LinearFlushCoordinator coordinator : all) {
            coordinator.evicted = true;
        }
        BY_PATH.clear();
        for (LinearFlushCoordinator coordinator : all) {
            try {
                coordinator.flushDirty(true);
                for (int i = 0; i < 2 && !coordinator.isEmpty(); i++) {
                    coordinator.flushDirty(true);
                }
                coordinator.recordEvict();
            } catch (RuntimeException e) {
                LOGGER.error("Failed to evict linear folder {}", coordinator.folder, e);
            }
        }
        linear$joinInFlightAsync();
    }

    private static String key(Path folder) {
        return folder.toAbsolutePath().normalize().toString();
    }

    static int linear$resolveFlushThreads() {
        return ParallelFlushFanout.resolveFlushThreads();
    }

    /** Test-only: forces the pool size (null clears back to GlobalConfiguration). */
    static void linear$setFlushThreadsForTests(Integer threads) {
        ParallelFlushFanout.setFlushThreadsForTests(threads);
    }

    static int linear$forcedThreadsFloor() {
        return ParallelFlushFanout.forcedThreadsFloor();
    }

    /** Test-only: forces the forced-drain floor (null clears back to default). */
    static void linear$setForcedThreadsForTests(Integer threads) {
        ParallelFlushFanout.setForcedThreadsForTests(threads);
    }

    /** Test-only: shuts the shared pool down and clears the overrides. */
    static void linear$resetFlushPoolForTests() {
        net.linear.flush.FlushHeartbeat.resetForTests();
        ParallelFlushFanout.resetFlushPoolForTests();
        SHUTDOWN_MODE = false;
    }

    static long linear$resolveFlushFrequencySeconds() {
        return ParallelFlushFanout.resolveFlushFrequencySeconds();
    }

    /** Test-only: forces flush-frequency seconds (null clears). */
    static void linear$setFlushFrequencyForTests(Long seconds) {
        if (seconds != null) {
            // A pinned test frequency means deterministic drain counts: the
            // background heartbeat stays off unless a test re-enables it.
            net.linear.flush.FlushHeartbeat.stop();
        }
        ParallelFlushFanout.setFlushFrequencyForTests(seconds);
    }

    static long linear$cachedFlushFrequencyNanos() {
        return ParallelFlushFanout.cachedFlushFrequencyNanos();
    }

    private static java.util.concurrent.ExecutorService linear$sharedPool(int threads) {
        return ParallelFlushFanout.sharedPool(threads);
    }

    /**
     * Wraps a batch already detached from {@link #dirty} (and registered in
     * {@link #inFlight} by the caller, in the same critical section).
     */
    private java.util.concurrent.FutureTask<?> linear$createFlushTask(
            final List<AbstractRegionFile> snapshot) {
        return ParallelFlushFanout.createFlushTask(snapshot, this.flushDetached, key(this.folder));
    }

    private void linear$submitFlushTask(java.util.concurrent.FutureTask<?> tracked) {
        ParallelFlushFanout.submitFlushTask(tracked);
    }

    static void linear$joinInFlightAsync() {
        ParallelFlushFanout.joinInFlightAsync();
    }

    /** Fire-and-forget drain across every tracked folder (async split, B-RC). */
    public static void flushDirtyAsync(boolean force) {
        ParallelFlushFanout.flushDirtyAsync(BY_FOLDER, force);
    }

    /**
     * Detaches the async batch for one folder under the coordinator monitor
     * (register-before-untrack, same critical section as before) and returns
     * the tracked task, or {@code null} when nothing is eligible. Called by
     * {@link ParallelFlushFanout#flushDirtyAsync}; submission happens outside
     * the monitor via {@link #linear$submitFlushTask}.
     */
    public java.util.concurrent.FutureTask<?> linear$detachAsyncBatch(
            boolean force, long now, long frequencyNanos) {
        synchronized (this) {
            if (this.dirty.isEmpty()) {
                return null;
            }
            final List<AbstractRegionFile> batch;
            if (force) {
                batch = new ArrayList<>(this.dirty.keySet());
            } else {
                // Lazy: an idle heartbeat tick allocates nothing.
                List<AbstractRegionFile> eligible = null;
                for (java.util.Map.Entry<AbstractRegionFile, Long> e : this.dirty.entrySet()) {
                    if (now - e.getValue() >= frequencyNanos) {
                        if (eligible == null) {
                            eligible = new ArrayList<>();
                        }
                        eligible.add(e.getKey());
                    }
                }
                if (eligible == null) {
                    return null;
                }
                batch = eligible;
            }
            registerInFlightLocked(batch);
            java.util.concurrent.FutureTask<?> tracked = linear$createFlushTask(batch);
            if (force) {
                this.dirty.clear();
            } else {
                for (AbstractRegionFile file : batch) {
                    this.dirty.remove(file);
                }
            }
            return tracked;
        }
    }

    /** Test-only: re-arms and starts the heartbeat after a pinned frequency. */
    static void linear$enableHeartbeatForTests() {
        net.linear.flush.FlushHeartbeat.resetForTests();
        net.linear.flush.FlushHeartbeat.ensureStarted(LinearFlushCoordinator::linear$heartbeatTick);
    }

    /** Heartbeat tick: age-gated fire-and-forget drain, skipped once stopping. */
    static void linear$heartbeatTick() {
        if (!SHUTDOWN_MODE && !BY_FOLDER.isEmpty()) {
            flushDirtyAsync(false);
        }
    }

    /** Production pool shutdown for the stop path (async split, B-RC). */
    public static void shutdownFlushPool() {
        net.linear.flush.FlushHeartbeat.stop();
        ParallelFlushFanout.shutdownFlushPool();
    }

    private final Path folder;
    /** Set once this instance left the registry (evict/evictAll): the fast path skips it. */
    private volatile boolean evicted;
    private final java.util.LinkedHashMap<AbstractRegionFile, Long> dirty = new java.util.LinkedHashMap<>();
    /**
     * Files detached from {@link #dirty} whose flush has not finished yet,
     * with a count (one file can sit in two batches). A forced barrier joins
     * every file listed here, so a flush that another thread started before
     * the barrier (dirty already cleared, rename not done) cannot make the
     * barrier return early. Guarded by this monitor. Identity map: open
     * addressing (no per-entry node) and cached small Integer counts, so
     * registering and releasing allocate nothing in steady state.
     */
    private final java.util.IdentityHashMap<AbstractRegionFile, Integer> inFlight =
        new java.util.IdentityHashMap<>();
    /** Pre-built flush actions: no per-batch lambda allocation. */
    private final java.util.function.Consumer<AbstractRegionFile> flushDetached = f -> this.flushDetached(f, false);
    private final java.util.function.Consumer<AbstractRegionFile> flushDetachedForced = f -> this.flushDetached(f, true);
    private final java.util.function.Consumer<AbstractRegionFile> flushForced = f -> this.flushOne(f, true);
    private final java.util.function.Consumer<AbstractRegionFile> flushPlain = f -> this.flushOne(f, false);
    /** Folder-wide timing + windowed latency (all counters live here). */
    private final FlushLatencyWindow timings = new FlushLatencyWindow();

    private LinearFlushCoordinator(Path folder) {
        this.folder = folder;
    }

    /**
     * Records a file with unflushed writes. Over-capacity evicts the eldest
     * entry for an off-thread drain (bounded memory), so write bursts cannot
     * grow the tracked set without bound and the caller never blocks on file
     * I/O. FIRST-dirty ordering (no reorder on repeat marks). When no
     * pressure victim exists, the head drains off-thread if already
     * age-eligible (at most one file per call, production frequencies only).
     */
    public void markDirty(AbstractRegionFile file) {
        final long now = System.nanoTime();
        final long frequencyNanos = linear$cachedFlushFrequencyNanos();
        java.util.concurrent.FutureTask<?> drain = null;
        synchronized (this) {
            if (!this.dirty.containsKey(file)) {
                this.dirty.put(file, now);
            }
            if (!SHUTDOWN_MODE && this.dirty.size() > MAX_DIRTY) {
                java.util.Iterator<java.util.Map.Entry<AbstractRegionFile, Long>> it =
                    this.dirty.entrySet().iterator();
                AbstractRegionFile eldest = it.next().getKey();
                registerInFlightLocked(eldest);
                drain = linear$createFlushTask(Collections.singletonList(eldest));
                it.remove();
            } else if (!SHUTDOWN_MODE && frequencyNanos > 0L && !this.dirty.isEmpty()) {
                java.util.Iterator<java.util.Map.Entry<AbstractRegionFile, Long>> head =
                    this.dirty.entrySet().iterator();
                java.util.Map.Entry<AbstractRegionFile, Long> e = head.next();
                if (now - e.getValue() >= frequencyNanos) {
                    AbstractRegionFile aged = e.getKey();
                    registerInFlightLocked(aged);
                    drain = linear$createFlushTask(Collections.singletonList(aged));
                    head.remove();
                }
            }
        }
        this.timings.recordMarkDirty();
        if (drain != null) {
            linear$submitFlushTask(drain);
        }
    }

    /** Flushes dirty files old enough per flush-frequency. Called from storage save. */
    public void flushDirty() {
        this.flushDirty(false);
    }

    /**
     * REAL age-based flush behind flush-frequency. Only files whose
     * first-dirty age {@code >=} frequency seconds are flushed; younger files
     * stay tracked for the next save. {@code force} (evict/unload/save
     * barrier) flushes everything regardless of age and is a durability
     * barrier: it returns only after every flush of this folder that was
     * already running when it started (fire-and-forget drains and other
     * threads' batches) has renamed its file and synced the directory, even
     * when nothing is dirty any more. Public so the legs' storage flush can
     * call the forced barrier directly.
     */
    public void flushDirty(boolean force) {
        final FailureScope scope = FAILURE_SCOPE.get();
        if (scope != null) {
            scope.enter(this);
        }
        if (force) {
            linear$joinInFlightAsync();
        } else if (this.isEmpty()) {
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
        List<AbstractRegionFile> snapshot = null;
        AbstractRegionFile[] runningElsewhere = null;
        synchronized (this) {
            if (force) {
                // Taken before this barrier registers its own batch, so it
                // lists exactly the flushes started by someone else.
                if (!this.inFlight.isEmpty()) {
                    runningElsewhere = this.inFlight.keySet().toArray(new AbstractRegionFile[0]);
                }
                if (!this.dirty.isEmpty()) {
                    snapshot = new ArrayList<>(this.dirty.keySet());
                    this.dirty.clear();
                }
            } else {
                if (this.dirty.isEmpty()) {
                    return;
                }
                java.util.Iterator<java.util.Map.Entry<AbstractRegionFile, Long>> it =
                    this.dirty.entrySet().iterator();
                while (it.hasNext()) {
                    java.util.Map.Entry<AbstractRegionFile, Long> e = it.next();
                    long age = now - e.getValue();
                    if (age >= frequencyNanos) {
                        if (snapshot == null) {
                            snapshot = new ArrayList<>();
                        }
                        snapshot.add(e.getKey());
                        it.remove();
                    }
                }
                if (snapshot == null) {
                    return;
                }
            }
            if (snapshot != null) {
                registerInFlightLocked(snapshot);
            }
        }
        if (snapshot != null) {
            final long batchStartNanos = System.nanoTime();
            final int filesAttempted = snapshot.size();
            try {
                ParallelFlushFanout.flushSnapshot(snapshot, force,
                    force ? this.flushDetachedForced : this.flushDetached);
            } finally {
                final long elapsedMicros = (System.nanoTime() - batchStartNanos) / 1000L;
                linear$notifyFlushSync(key(this.folder), filesAttempted, elapsedMicros);
            }
        }
        if (runningElsewhere != null) {
            for (AbstractRegionFile file : runningElsewhere) {
                this.flushOne(file, true);
            }
        }
        linear$joinInFlightAsync();
    }

    /** Caller holds this monitor. */
    private void registerInFlightLocked(AbstractRegionFile file) {
        final Integer count = this.inFlight.get(file);
        this.inFlight.put(file, count == null ? 1 : count + 1);
    }

    /** Caller holds this monitor. */
    private void registerInFlightLocked(List<AbstractRegionFile> files) {
        for (int i = 0, n = files.size(); i < n; i++) {
            registerInFlightLocked(files.get(i));
        }
    }

    private synchronized void releaseInFlight(AbstractRegionFile file) {
        final Integer count = this.inFlight.get(file);
        if (count == null || count <= 1) {
            this.inFlight.remove(file);
        } else {
            this.inFlight.put(file, count - 1);
        }
    }

    /** Test-only: files whose detached flush has not finished. */
    synchronized int linear$inFlightCountForTests() {
        return this.inFlight.size();
    }

    public synchronized boolean isEmpty() {
        return this.dirty.isEmpty();
    }

    /** For tests/metrics only. */
    public synchronized int dirtyCount() {
        return this.dirty.size();
    }

    /** Test-only view of first-dirty order (head = longest-unflushed). */
    synchronized List<AbstractRegionFile> linear$dirtyOrderForTests() {
        return new ArrayList<>(this.dirty.keySet());
    }

    /**
     * Shared fan-out for an already-detached snapshot: serial when
     * single-threaded or trivially small, else ONE shared pool with caller
     * participation and join-before-return (durability barrier).
     */
    public void linear$flushSnapshot(List<AbstractRegionFile> snapshot, boolean forceBarrier) {
        ParallelFlushFanout.flushSnapshot(snapshot, forceBarrier,
            forceBarrier ? this.flushForced : this.flushPlain);
    }

    /**
     * Flushes a file this coordinator detached, then releases its in-flight
     * entry. A non-forced flush of a file that looks clean may race a flush
     * that runs outside the coordinator (an eviction {@code close()}, which
     * clears the dirty flag and then renames under {@code flushGuard}): the
     * entry is released only after {@code awaitDurable()}, so a forced
     * barrier that starts meanwhile still finds the file in {@link #inFlight}
     * and joins that flush. Runs on flush/drain threads only (never a region
     * thread); a lock/unlock pair, no allocation.
     */
    private void flushDetached(AbstractRegionFile file, boolean force) {
        try {
            this.flushOne(file, force);
            if (!force) {
                file.awaitDurable();
            }
        } finally {
            this.releaseInFlight(file);
        }
    }

    /**
     * Flushes one file when dirty. {@code force}: a file that looks clean
     * may have a flush running on another thread that already cleared its
     * dirty flag but has not renamed yet; join it ({@code awaitDurable}),
     * then flush again if that flush failed and re-armed the flag.
     */
    private void flushOne(AbstractRegionFile file, boolean force) {
        boolean ok = false;
        boolean requeueClean = false;
        try {
            if (force && !file.isMarkedToSave()) {
                file.awaitDurable();
            }
            if (file.isMarkedToSave()) {
                file.flush();
            }
            if (file.isMarkedToSave()) {
                requeueClean = true;
            }
            ok = true;
        } catch (Throwable ex) {
            LOGGER.warn("Failed to flush linear region file for folder {}, will retry on next save", this.folder, ex);
            this.recordFailure();
        }
        if (ok) {
            this.timings.recordFileFlushed(System.nanoTime());
        }
        if (!ok || FlushSnapshotRegistry.shouldRequeue(requeueClean)) {
            synchronized (this) {
                if (FlushSnapshotRegistry.boundedRequeue(this.dirty.size(), MAX_DIRTY)) {
                    this.dirty.putIfAbsent(file, System.nanoTime());
                }
            }
        }
    }

    void recordRead(long micros) {
        this.timings.recordRead(micros);
    }

    void recordWrite(long micros) {
        this.timings.recordWrite(micros);
    }

    void recordFlush(long micros) {
        this.timings.recordFlush(micros);
    }

    void recordFlush(long micros, boolean success) {
        this.timings.recordFlush(micros, success);
    }

    void recordFlushBytes(long rawBytes, long compressedBytes) {
        this.timings.recordFlushBytes(rawBytes, compressedBytes);
    }

    void recordLoad(long micros) {
        this.timings.recordLoad(micros);
    }

    void recordEvict() {
        this.timings.recordEvict();
    }

    void recordFailure() {
        this.timings.recordFailure();
    }

    void recordOversizeReject() {
        this.timings.recordOversizeReject();
    }

    /** An existing region file could not be used as found (moved aside or copied out). */
    public void recordQuarantine() {
        this.timings.recordQuarantine();
    }

    /** A region file that failed to open was copied to the conflicts folder (original kept). */
    public void recordConflict() {
        this.timings.recordConflict();
    }

    void recordCacheHit() {
        this.timings.recordCacheHit();
    }

    void recordCacheMiss() {
        this.timings.recordCacheMiss();
    }

    /** Test-only: forces a window rotation regardless of age/sample bounds. */
    void linear$forceRotateFlushWindowForTests() {
        this.timings.forceRotateForTests();
    }

    public void reportCacheHit() {
        recordCacheHit();
    }

    public void reportCacheMiss() {
        recordCacheMiss();
    }

    public void reportOversizeReject() {
        recordOversizeReject();
    }

    /** Folder snapshot (dirty depth under lock; counters lock-free). */
    public LinearRegionTimings.LinearFolderSnapshot snapshot() {
        final int depth;
        synchronized (this) {
            depth = this.dirty.size();
        }
        return this.timings.snapshot(depth);
    }

    private static final ThreadLocal<FailureScope> FAILURE_SCOPE = new ThreadLocal<>();

    /**
     * Failure count scoped to one barrier: every coordinator whose
     * {@link #flushDirty(boolean)} is called on the opening thread while the
     * scope is open is remembered with its failure count at that moment, and
     * {@link #failures()} sums only the growth of those coordinators. A flush
     * failure in a folder the barrier never touched (another world, a
     * dimension the barrier does not cover) does not fail it. Within a
     * touched folder the count is per coordinator, not per file: a failed
     * flush of an unrelated region file in the same folder during the scope
     * (a fire-and-forget drain on another thread) also counts. That is the
     * conservative direction on purpose: the capture is withheld and retried,
     * never written over a possibly missing chunk. Scopes nest
     * (the inner one restores the outer on close); the outer scope also sees
     * coordinators first entered inside an inner one. Not thread-safe: one
     * scope belongs to the thread that opened it.
     */
    public static final class FailureScope implements AutoCloseable {
        private final FailureScope outer;
        private final java.util.IdentityHashMap<LinearFlushCoordinator, Long> base = new java.util.IdentityHashMap<>();
        private boolean closed;

        private FailureScope(final FailureScope outer) {
            this.outer = outer;
        }

        void enter(final LinearFlushCoordinator coordinator) {
            if (!this.base.containsKey(coordinator)) {
                this.base.put(coordinator, coordinator.timings.failureCount());
            }
            if (this.outer != null) {
                this.outer.enter(coordinator);
            }
        }

        /** Failures recorded since entry by the coordinators flushed in this scope. */
        public long failures() {
            long total = 0L;
            for (final java.util.Map.Entry<LinearFlushCoordinator, Long> e : this.base.entrySet()) {
                total += Math.max(0L, e.getKey().timings.failureCount() - e.getValue());
            }
            return total;
        }

        /** Number of distinct coordinators flushed in this scope (tests, diagnostics). */
        public int coordinators() {
            return this.base.size();
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                if (FAILURE_SCOPE.get() == this) {
                    if (this.outer == null) {
                        FAILURE_SCOPE.remove();
                    } else {
                        FAILURE_SCOPE.set(this.outer);
                    }
                }
            }
        }
    }

    /** Opens a {@link FailureScope} on this thread (close it with try-with-resources). */
    public static FailureScope openFailureScope() {
        final FailureScope scope = new FailureScope(FAILURE_SCOPE.get());
        FAILURE_SCOPE.set(scope);
        return scope;
    }

    /**
     * Flush failures recorded so far over every tracked folder (best effort,
     * 0 on any counter error). Barriers compare it before and after to see a
     * failure that the coordinator logged instead of throwing.
     */
    public static long totalFailures() {
        long total = 0L;
        try {
            for (final LinearRegionTimings.LinearFolderSnapshot snapshot : snapshots().values()) {
                total += snapshot.failures();
            }
        } catch (RuntimeException ignored) {
            // Counters are best effort.
        }
        return total;
    }

    /** Per-folder snapshots keyed by absolute folder-path string. */
    public static java.util.Map<String, LinearRegionTimings.LinearFolderSnapshot> snapshots() {
        java.util.Map<String, LinearRegionTimings.LinearFolderSnapshot> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, LinearFlushCoordinator> e : BY_FOLDER.entrySet()) {
            out.put(e.getKey(), e.getValue().snapshot());
        }
        return out;
    }

    /** Explicit test-only reset (counters only; lifecycle/dirty set untouched). */
    public void resetForTests() {
        this.timings.reset();
    }
}
