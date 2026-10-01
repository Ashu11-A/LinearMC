package net.linear;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;

/** Flush-coordinator supplier + listener seams (owned by flush). */
public class FlushSupplierTest {

    @After
    public void reset() {
        LinearFlushCoordinator.linear$setFlushThreadsSupplier(() -> 1);
        LinearFlushCoordinator.linear$setFlushFrequencySupplier(() -> 10L);
        LinearFlushCoordinator.linear$setFlushListener((folderKey, filesAttempted, elapsedMicros) -> {
        });
    }

    @Test
    public void supplierDefaultsMatchOldNullConfigFallbacks() {
        assertEquals(1, LinearFlushCoordinator.linear$resolveFlushThreads());
        assertEquals(10L, LinearFlushCoordinator.linear$resolveFlushFrequencySeconds());
    }

    @Test
    public void suppliersAreLive() {
        AtomicInteger threads = new AtomicInteger(4);
        AtomicLong freq = new AtomicLong(30L);
        LinearFlushCoordinator.linear$setFlushThreadsSupplier(threads::get);
        LinearFlushCoordinator.linear$setFlushFrequencySupplier(freq::get);
        assertEquals(4, LinearFlushCoordinator.linear$resolveFlushThreads());
        assertEquals(30L, LinearFlushCoordinator.linear$resolveFlushFrequencySeconds());
        threads.set(8);
        assertEquals(8, LinearFlushCoordinator.linear$resolveFlushThreads());
    }

    @Test
    public void throwingSupplierFallsBack() {
        LinearFlushCoordinator.linear$setFlushThreadsSupplier(() -> {
            throw new RuntimeException("config gone");
        });
        assertEquals(1, LinearFlushCoordinator.linear$resolveFlushThreads());
    }

    @Test
    public void listenerReceivesAndSurvivesThrow() {
        AtomicInteger calls = new AtomicInteger(0);
        LinearFlushCoordinator.linear$setFlushListener((folderKey, filesAttempted, elapsedMicros) -> {
            calls.incrementAndGet();
            assertEquals("folder", folderKey);
            assertEquals(2, filesAttempted);
            throw new RuntimeException("bridge down");
        });
        // Listener exceptions must never propagate: durability is complete already.
        LinearFlushCoordinator.linear$notifyFlushSync("folder", 2, 99L);
        assertEquals(1, calls.get());
    }
}
