package net.linear.flush;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.linear.AbstractRegionFile;
import net.linear.LinearFlushCoordinator;

/**
 * Shared pool + fan-out for flush snapshots (server-wide statics).
 *
 * <p>One shared bounded pool behind {@code flush-max-threads}, NOT per
 * coordinator, NOT ForkJoinPool, NOT Moonrise's pool. The joining barrier
 * runs serially when {@code <=1} or on the pool + caller-participation when
 * {@code >1}. Fire-and-forget submissions never join; forced barriers join
 * them instead. The coordinator keeps thin facades with identical signatures;
 * all state and bodies live here.</p>
 */
public final class ParallelFlushFanout {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelFlushFanout.class);

    private static final Object FLUSH_POOL_LOCK = new Object();
    private static java.util.concurrent.ExecutorService FLUSH_POOL = null;
    private static int FLUSH_POOL_THREADS = 0;
    private static volatile Integer FLUSH_THREADS_OVERRIDE_FOR_TESTS = null;
    private static volatile Integer FORCED_THREADS_OVERRIDE_FOR_TESTS = null;
    private static volatile java.util.function.IntSupplier FLUSH_THREADS_SUPPLIER = () -> 1;
    private static volatile java.util.function.LongSupplier FLUSH_FREQUENCY_SUPPLIER = () -> 10L;
    private static volatile Long FLUSH_FREQUENCY_SECONDS_OVERRIDE_FOR_TESTS = null;
    private static volatile long CACHED_FLUSH_FREQUENCY_NANOS_FOR_DIRTY = 10_000_000_000L;
    private static volatile long CACHED_FLUSH_FREQUENCY_AT_NANOS = 0L;

    private static final java.util.Set<java.util.concurrent.Future<?>> IN_FLIGHT_ASYNC =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private ParallelFlushFanout() {
    }

    /**
     * Effective worker count: {@code 1} means run serially inline.
     *
     * @param snapshotSize detached file count
     * @param configuredThreads resolved flush width
     * @param force true for barrier drains (applies {@code forcedFloor})
     * @param forcedFloor minimum width for barrier drains
     */
    public static int threadCount(int snapshotSize, int configuredThreads, boolean force, int forcedFloor) {
        int threads = force ? Math.max(configuredThreads, forcedFloor) : configuredThreads;
        if (threads <= 1 || snapshotSize <= 1) {
            return 1;
        }
        return threads;
    }

    public static int resolveFlushThreads() {
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

    public static void setFlushThreadsForTests(Integer threads) {
        FLUSH_THREADS_OVERRIDE_FOR_TESTS = threads;
    }

    public static void setFlushThreadsSupplier(java.util.function.IntSupplier supplier) {
        FLUSH_THREADS_SUPPLIER = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    public static int forcedThreadsFloor() {
        Integer override = FORCED_THREADS_OVERRIDE_FOR_TESTS;
        if (override != null) {
            return override;
        }
        return Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
    }

    public static void setForcedThreadsForTests(Integer threads) {
        FORCED_THREADS_OVERRIDE_FOR_TESTS = threads;
    }

    public static void resetFlushPoolForTests() {
        synchronized (FLUSH_POOL_LOCK) {
            if (FLUSH_POOL != null) {
                FLUSH_POOL.shutdown();
                FLUSH_POOL = null;
                FLUSH_POOL_THREADS = 0;
            }
            FLUSH_THREADS_OVERRIDE_FOR_TESTS = null;
        }
        FORCED_THREADS_OVERRIDE_FOR_TESTS = null;
        FLUSH_FREQUENCY_SECONDS_OVERRIDE_FOR_TESTS = null;
        CACHED_FLUSH_FREQUENCY_NANOS_FOR_DIRTY = 10_000_000_000L;
        CACHED_FLUSH_FREQUENCY_AT_NANOS = 0L;
    }

    public static long resolveFlushFrequencySeconds() {
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

    public static void setFlushFrequencySupplier(java.util.function.LongSupplier supplier) {
        FLUSH_FREQUENCY_SUPPLIER = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    public static void setFlushFrequencyForTests(Long seconds) {
        FLUSH_FREQUENCY_SECONDS_OVERRIDE_FOR_TESTS = seconds;
        long now = System.nanoTime();
        if (seconds == null) {
            CACHED_FLUSH_FREQUENCY_AT_NANOS = 0L;
        } else {
            CACHED_FLUSH_FREQUENCY_NANOS_FOR_DIRTY = seconds <= 0L ? 0L : seconds * 1_000_000_000L;
            CACHED_FLUSH_FREQUENCY_AT_NANOS = now;
        }
    }

    public static long cachedFlushFrequencyNanos() {
        long now = System.nanoTime();
        long at = CACHED_FLUSH_FREQUENCY_AT_NANOS;
        if (at != 0L && now - at < 5_000_000_000L) {
            return CACHED_FLUSH_FREQUENCY_NANOS_FOR_DIRTY;
        }
        long seconds = resolveFlushFrequencySeconds();
        long nanos = seconds <= 0L ? 0L : seconds * 1_000_000_000L;
        CACHED_FLUSH_FREQUENCY_NANOS_FOR_DIRTY = nanos;
        CACHED_FLUSH_FREQUENCY_AT_NANOS = now;
        return nanos;
    }

    public static java.util.concurrent.ExecutorService sharedPool(int threads) {
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
            FLUSH_POOL = new java.util.concurrent.ThreadPoolExecutor(
                threads, threads, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), factory);
            return FLUSH_POOL;
        }
    }

    public static java.util.concurrent.FutureTask<?> createFlushTask(
            final List<AbstractRegionFile> snapshot,
            final java.util.function.Consumer<AbstractRegionFile> flushOne,
            final String folderKey) {
        final long batchStartNanos = System.nanoTime();
        final int filesAttempted = snapshot.size();
        final java.util.concurrent.FutureTask<?>[] holder = new java.util.concurrent.FutureTask<?>[1];
        final Runnable task = () -> {
            try {
                for (AbstractRegionFile file : snapshot) {
                    flushOne.accept(file);
                }
            } finally {
                IN_FLIGHT_ASYNC.remove(holder[0]);
                final long elapsedMicros = (System.nanoTime() - batchStartNanos) / 1000L;
                LinearFlushCoordinator.linear$notifyFlushSync(folderKey, filesAttempted, elapsedMicros);
            }
        };
        holder[0] = new java.util.concurrent.FutureTask<Void>(task, null);
        IN_FLIGHT_ASYNC.add(holder[0]);
        return holder[0];
    }

    public static void submitFlushTask(java.util.concurrent.FutureTask<?> tracked) {
        final java.util.concurrent.ExecutorService pool;
        try {
            pool = sharedPool(Math.max(1, resolveFlushThreads()));
        } catch (RuntimeException | Error poolFailed) {
            tracked.run();
            return;
        }
        try {
            pool.execute(tracked);
        } catch (RuntimeException | Error submitFailed) {
            IN_FLIGHT_ASYNC.remove(tracked);
            tracked.run();
        }
    }

    public static void joinInFlightAsync() {
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

    public static void flushSnapshot(
            List<AbstractRegionFile> snapshot, boolean forceBarrier,
            java.util.function.Consumer<AbstractRegionFile> flushOne) {
        final int threads = threadCount(snapshot.size(), resolveFlushThreads(),
            forceBarrier, forcedThreadsFloor());
        if (threads <= 1) {
            for (AbstractRegionFile file : snapshot) {
                flushOne.accept(file);
            }
            return;
        }
        final java.util.concurrent.ExecutorService pool;
        try {
            pool = sharedPool(threads);
        } catch (RuntimeException | Error fallback) {
            for (AbstractRegionFile file : snapshot) {
                flushOne.accept(file);
            }
            return;
        }
        final java.util.List<java.util.concurrent.Future<?>> futures =
            new java.util.ArrayList<>(snapshot.size() - 1);
        for (int i = 1; i < snapshot.size(); i++) {
            final AbstractRegionFile f = snapshot.get(i);
            try {
                futures.add(pool.submit(() -> {
                    flushOne.accept(f);
                    return null;
                }));
            } catch (RuntimeException | Error submitFailed) {
                flushOne.accept(f);
            }
        }
        flushOne.accept(snapshot.get(0));
        boolean interrupted = false;
        for (java.util.concurrent.Future<?> fut : futures) {
            while (true) {
                try {
                    fut.get();
                    break;
                } catch (InterruptedException ie) {
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
    }

    public static void flushDirtyAsync(java.util.Map<String, LinearFlushCoordinator> byFolder, boolean force) {
        final long now = System.nanoTime();
        final long frequencyNanos;
        if (force) {
            frequencyNanos = 0L;
        } else {
            long seconds = resolveFlushFrequencySeconds();
            if (seconds < 0L) {
                seconds = 10L;
            }
            frequencyNanos = seconds * 1_000_000_000L;
        }
        for (String folderKey : new ArrayList<>(byFolder.keySet())) {
            try {
                LinearFlushCoordinator coordinator = byFolder.get(folderKey);
                if (coordinator == null) {
                    continue;
                }
                java.util.concurrent.FutureTask<?> tracked =
                    coordinator.linear$detachAsyncBatch(force, now, frequencyNanos);
                if (tracked == null) {
                    continue;
                }
                submitFlushTask(tracked);
            } catch (RuntimeException e) {
                LOGGER.error("Failed to flush linear folder {}", folderKey, e);
            }
        }
    }
}
