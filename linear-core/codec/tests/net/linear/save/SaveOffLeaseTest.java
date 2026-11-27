package net.linear.save;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/** The save-off lease: arm, disarm, expiry, config resolution. */
public class SaveOffLeaseTest {

    @After
    public void reset() {
        SaveOffLease.resetForTests();
    }

    @Test
    public void expiredLeaseRunsTheReleaseOnce() throws Exception {
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        SaveOffLease.armForTests(50, () -> {
            runs.incrementAndGet();
            released.countDown();
        });
        assertTrue(SaveOffLease.armed());
        assertTrue(released.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(1, runs.get());
        assertFalse(SaveOffLease.armed());
    }

    @Test
    public void saveOnDisarmsTheLease() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        SaveOffLease.armForTests(100, runs::incrementAndGet);
        SaveOffLease.onAutoSaveChanged(true, true, null);
        assertFalse(SaveOffLease.armed());
        Thread.sleep(250);
        assertEquals(0, runs.get());
    }

    @Test
    public void unchangedStateNeverArmsOrDisarms() {
        SaveOffLease.onAutoSaveChanged(false, false, () -> { });
        assertFalse("save-off that changed nothing must not arm", SaveOffLease.armed());
        SaveOffLease.armForTests(60_000, () -> { });
        SaveOffLease.onAutoSaveChanged(true, false, null);
        assertTrue("save-on that changed nothing must not disarm", SaveOffLease.armed());
    }

    @Test
    public void saveOffArmsWithTheConfiguredDurationAndZeroDisables() {
        SaveOffLease.setMaxDurationSeconds(120);
        SaveOffLease.onAutoSaveChanged(false, true, () -> { });
        assertTrue(SaveOffLease.armed());
        SaveOffLease.onAutoSaveChanged(true, true, null);
        SaveOffLease.setMaxDurationSeconds(0);
        SaveOffLease.onAutoSaveChanged(false, true, () -> { });
        assertFalse("0 disables the lease", SaveOffLease.armed());
    }

    @Test
    public void negativeConfigWarnsAndFallsBack() {
        final List<String> warnings = new ArrayList<>();
        assertEquals(120L, SaveOffLease.resolveMaxDurationSeconds(-5, warnings::add));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("save-off-max-duration"));
        assertEquals(0L, SaveOffLease.resolveMaxDurationSeconds(0, warnings::add));
        assertEquals(300L, SaveOffLease.resolveMaxDurationSeconds(300, warnings::add));
        assertEquals(1, warnings.size());
    }

    @Test
    public void releaseMessageNamesTheKey() {
        SaveOffLease.setMaxDurationSeconds(120);
        final String message = SaveOffLease.releaseMessage(121);
        assertTrue(message.contains("linear.save-off-max-duration"));
        assertTrue(message.contains("121 s"));
    }
}
