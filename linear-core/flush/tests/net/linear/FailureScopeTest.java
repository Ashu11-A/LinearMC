package net.linear;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Barrier failure scopes count only the coordinators the barrier flushed
 * on its own thread: a failure in another folder, before or during the
 * barrier, does not fail it; a failure in a flushed folder does.
 */
public class FailureScopeTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** Test seam (also used by CoupledSaveTest): records a flush failure as a failed file flush would. */
    public static void recordFailure(final Path folder) {
        LinearFlushCoordinator.forFolder(folder).recordFailure();
    }

    @After
    public void reset() {
        LinearFlushCoordinator.evictAll();
    }

    @Test
    public void onlyFlushedFoldersCount() throws Exception {
        final Path mine = this.temporaryFolder.newFolder("overworld").toPath();
        final Path other = this.temporaryFolder.newFolder("other-world").toPath();
        recordFailure(other); // before the scope: never counted
        try (LinearFlushCoordinator.FailureScope scope = LinearFlushCoordinator.openFailureScope()) {
            LinearFlushCoordinator.forFolder(mine).flushDirty(true);
            assertEquals(1, scope.coordinators());
            // A failure in a folder this barrier never flushed, from another thread.
            final CountDownLatch done = new CountDownLatch(1);
            Thread.ofPlatform().start(() -> {
                recordFailure(other);
                done.countDown();
            });
            done.await(10, TimeUnit.SECONDS);
            assertEquals("unrelated folder failure must not fail the barrier", 0L, scope.failures());
            assertEquals("global counter still sees it", 2L, LinearFlushCoordinator.totalFailures());
            recordFailure(mine);
            assertEquals("a failure in a flushed folder does", 1L, scope.failures());
        }
    }

    @Test
    public void scopesNestAndRestore() throws Exception {
        final Path a = this.temporaryFolder.newFolder("a").toPath();
        final Path b = this.temporaryFolder.newFolder("b").toPath();
        try (LinearFlushCoordinator.FailureScope outer = LinearFlushCoordinator.openFailureScope()) {
            try (LinearFlushCoordinator.FailureScope inner = LinearFlushCoordinator.openFailureScope()) {
                LinearFlushCoordinator.forFolder(a).flushDirty(true);
                recordFailure(a);
                assertEquals(1L, inner.failures());
            }
            LinearFlushCoordinator.forFolder(b).flushDirty(true);
            assertEquals("outer sees folders entered in the inner scope", 1L, outer.failures());
            assertEquals(2, outer.coordinators());
        }
        try (LinearFlushCoordinator.FailureScope fresh = LinearFlushCoordinator.openFailureScope()) {
            assertEquals("closed scopes leave no state on the thread", 0, fresh.coordinators());
        }
    }
}
