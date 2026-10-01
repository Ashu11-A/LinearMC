package net.linear.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.linear.LinearRegionConverter.FileResult;
import net.linear.LinearRegionConverter.Status;

import org.junit.Test;

/** command: in-memory convert queue, job lifecycle, permission/format constants. */
public class JobQueueTest {

    private static JobQueue.Spec spec(int totalFiles) {
        return new JobQueue.Spec(ConvertDirection.MCA_TO_LINEAR, "world", 3, 2, false, totalFiles);
    }

    private static FileResult fileResult(String name, Status status) {
        Path source = Path.of(name + ".mca");
        Path target = Path.of(name + ".linear");
        return new FileResult(source, target, status, "detail for " + name);
    }

    @Test
    public void submitListGet() {
        JobQueue queue = new JobQueue();
        ConvertJob job = queue.submit(spec(4));

        assertNotNull(job.id());
        assertEquals(JobState.QUEUED, job.state());
        assertEquals(ConvertDirection.MCA_TO_LINEAR, job.direction());
        assertEquals("world", job.world());
        assertEquals(4, job.totalFiles());

        assertEquals(job, queue.get(job.id()));
        assertNull(queue.get(UUID.randomUUID()));

        List<ConvertJob> listed = queue.list();
        assertEquals(1, listed.size());
        assertTrue(listed.contains(job));
    }

    @Test
    public void specFieldsRoundTrip() {
        JobQueue queue = new JobQueue();
        ConvertJob job = queue.submit(
            new JobQueue.Spec(ConvertDirection.LINEAR_TO_MCA, "nether", 22, 4, true, 7));
        assertEquals(ConvertDirection.LINEAR_TO_MCA, job.direction());
        assertEquals("nether", job.world());
        assertEquals(22, job.level());
        assertEquals(4, job.threads());
        assertEquals(true, job.dryRun());
        assertEquals(7, job.totalFiles());
    }

    @Test
    public void specValidationRejectsBadValues() {
        expectIllegal(() -> new JobQueue.Spec(null, "world", 3, 2, false, 1));
        expectIllegal(() -> new JobQueue.Spec(ConvertDirection.MCA_TO_LINEAR, null, 3, 2, false, 1));
        expectIllegal(() -> new JobQueue.Spec(ConvertDirection.MCA_TO_LINEAR, "world", 0, 2, false, 1));
        expectIllegal(() -> new JobQueue.Spec(ConvertDirection.MCA_TO_LINEAR, "world", 23, 2, false, 1));
        expectIllegal(() -> new JobQueue.Spec(ConvertDirection.MCA_TO_LINEAR, "world", 3, 0, false, 1));
        expectIllegal(() -> new JobQueue.Spec(ConvertDirection.MCA_TO_LINEAR, "world", 3, 2, false, -1));
        expectIllegal(() -> new ConvertJob(ConvertDirection.MCA_TO_LINEAR, "world", 3, 2, false, -1));
        expectIllegal(() -> new ConvertJob(null, "world", 3, 2, false, 1));
        expectIllegal(() -> new ConvertJob(ConvertDirection.MCA_TO_LINEAR, null, 3, 2, false, 1));
        expectIllegal(() -> new ConvertJob(ConvertDirection.MCA_TO_LINEAR, "world", 0, 2, false, 1));
        expectIllegal(() -> new ConvertJob(ConvertDirection.MCA_TO_LINEAR, "world", 23, 2, false, 1));
        expectIllegal(() -> new ConvertJob(ConvertDirection.MCA_TO_LINEAR, "world", 3, 0, false, 1));
        expectIllegal(() -> new LinearAction.ConvertAction(null, "world", 3, 2, false));
        expectIllegal(() -> new LinearAction.ConvertAction(ConvertDirection.MCA_TO_LINEAR, null, 3, 2, false));
        expectIllegal(() -> new LinearAction.ConvertAction(ConvertDirection.MCA_TO_LINEAR, "world", 0, 2, false));
        expectIllegal(() -> new LinearAction.ConvertAction(ConvertDirection.MCA_TO_LINEAR, "world", 3, 0, false));
    }

    @Test
    public void pauseResumeCancelTransitions() {
        JobQueue queue = new JobQueue();
        ConvertJob job = queue.submit(spec(2));

        job.start();
        assertEquals(JobState.RUNNING, job.state());

        queue.pause(job.id());
        assertEquals(JobState.PAUSED, job.state());

        queue.resume(job.id());
        assertEquals(JobState.RUNNING, job.state());

        queue.cancel(job.id());
        assertEquals(JobState.CANCELLED, job.state());
    }

    @Test
    public void allIllegalTransitionsThrow() {
        JobQueue queue = new JobQueue();

        // start: QUEUED-only.
        ConvertJob running = queue.submit(spec(1));
        running.start();
        expectIllegal(running::start);
        running.cancel();

        // pause: RUNNING-only.
        ConvertJob queued = queue.submit(spec(1));
        expectIllegal(queued::pause);
        ConvertJob paused = queue.submit(spec(1));
        paused.start();
        paused.pause();
        expectIllegal(paused::pause);
        expectIllegal(paused::start);
        paused.cancel();

        // resume: PAUSED-only.
        ConvertJob queued2 = queue.submit(spec(1));
        expectIllegal(queued2::resume);
        ConvertJob running2 = queue.submit(spec(1));
        running2.start();
        expectIllegal(running2::resume);
        ConvertJob done = queue.submit(spec(1));
        done.start();
        done.complete();
        expectIllegal(done::resume);
        expectIllegal(done::pause);
        expectIllegal(done::cancel);
        expectIllegal(done::start);
        expectIllegal(done::complete);
        expectIllegal(() -> done.fail("x"));

        // complete/fail: RUNNING-or-PAUSED only.
        ConvertJob queued3 = queue.submit(spec(1));
        expectIllegal(queued3::complete);
        expectIllegal(() -> queued3.fail("x"));

        ConvertJob failed = queue.submit(spec(1));
        failed.start();
        failed.fail("boom");
        expectIllegal(failed::complete);
        expectIllegal(failed::cancel);
        expectIllegal(() -> failed.recordFile(fileResult("r.0.0", Status.DELETED)));

        ConvertJob cancelled = queue.submit(spec(1));
        cancelled.cancel();
        expectIllegal(cancelled::cancel);
        expectIllegal(cancelled::resume);
        expectIllegal(cancelled::start);
        expectIllegal(cancelled::complete);
        expectIllegal(() -> cancelled.fail("x"));

        // recordFile: RUNNING (or first-record QUEUED start) only.
        ConvertJob done2 = queue.submit(spec(1));
        done2.start();
        done2.complete();
        expectIllegal(() -> done2.recordFile(fileResult("r.0.0", Status.DELETED)));

        ConvertJob paused2 = queue.submit(spec(1));
        paused2.start();
        paused2.pause();
        expectIllegal(() -> paused2.recordFile(fileResult("r.0.0", Status.DELETED)));
    }

    @Test
    public void recordFileDerivesStatusAndStartsQueued() {
        JobQueue queue = new JobQueue();
        ConvertJob job = queue.submit(spec(2));
        assertEquals(JobState.QUEUED, job.state());
        job.recordFile(fileResult("r.0.0", Status.DELETED));
        assertEquals(JobState.RUNNING, job.state());
        assertEquals(1, job.doneFiles());
        job.recordFile(fileResult("r.0.1", Status.FAILED));
        assertEquals(1, job.failedFiles());
        assertEquals(1, job.doneFiles());
    }

    @Test
    public void clearTerminalKeepsRunningJobs() {
        JobQueue queue = new JobQueue();
        ConvertJob running = queue.submit(spec(3));
        running.start();
        ConvertJob queued = queue.submit(spec(1));
        ConvertJob paused = queue.submit(spec(1));
        paused.start();
        paused.pause();
        ConvertJob finished = queue.submit(spec(1));
        finished.start();
        finished.complete();
        ConvertJob failed = queue.submit(spec(1));
        failed.start();
        failed.fail("boom");
        ConvertJob cancelled = queue.submit(spec(1));
        cancelled.cancel();

        int removed = queue.clearTerminal();
        assertEquals(3, removed);

        assertNotNull(queue.get(running.id()));
        assertNotNull(queue.get(queued.id()));
        assertNotNull(queue.get(paused.id()));
        assertNull(queue.get(finished.id()));
        assertNull(queue.get(failed.id()));
        assertNull(queue.get(cancelled.id()));
        assertEquals(3, queue.list().size());
    }

    @Test
    public void concurrencySmoke() throws Exception {
        JobQueue queue = new JobQueue();
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<ConvertJob> jobs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            ConvertJob job = queue.submit(spec(perThread));
            job.start();
            jobs.add(job);
        }
        for (ConvertJob job : jobs) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    job.recordFile(fileResult("r." + i, Status.DELETED));
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        for (ConvertJob job : jobs) {
            assertEquals(perThread, job.doneFiles());
            assertEquals(JobState.RUNNING, job.state());
        }
        assertEquals(threads, queue.list().size());

        // Pause racing records: a PAUSED job rejects records.
        ConvertJob pausable = queue.submit(spec(2));
        pausable.start();
        pausable.pause();
        try {
            pausable.recordFile(fileResult("r.x", Status.DELETED));
            fail("recordFile on PAUSED must throw");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void progressAndEtaLines() {
        JobQueue queue = new JobQueue();
        ConvertJob job = queue.submit(spec(4));

        assertEquals("0/4 files", job.progressLine());
        assertEquals("no ETA", job.etaLine(job.startedAtMs() + 1000L));

        job.recordFile(fileResult("r.0.0", Status.DELETED));
        assertEquals("1/4 files", job.progressLine());
        // runStartedAtMs is the ETA base: elapsed=1000ms, remaining=3,
        // done=1 -> 3000ms -> 3.00s.
        assertEquals("ETA 3.00s", job.etaLine(job.runStartedAtMs() + 1000L));

        job.recordFile(fileResult("r.0.1", Status.FAILED));
        assertEquals("2/4 files (1 failed)", job.progressLine());

        job.recordFile(fileResult("r.0.2", Status.DELETED));
        job.recordFile(fileResult("r.0.3", Status.DELETED));
        assertEquals("done", job.etaLine(job.runStartedAtMs() + 5000L));
        assertEquals(1, job.failedFiles());
        assertEquals(3, job.doneFiles());
    }

    @Test
    public void terminalOutcomesCountOnce() {
        JobQueue queue = new JobQueue();
        ConvertJob job = queue.submit(spec(2));
        // One file emits CONVERTED + VALIDATED + DELETED: still one done file.
        job.recordFile(fileResult("r.0.0", Status.CONVERTED));
        assertEquals(0, job.doneFiles());
        assertEquals(0, job.failedFiles());
        assertEquals("Copying files", job.currentStep());
        assertEquals("r.0.0", job.lastFileShort());
        job.recordFile(fileResult("r.0.0", Status.VALIDATED));
        assertEquals(0, job.doneFiles());
        assertEquals(0, job.failedFiles());
        assertEquals("Checking files", job.currentStep());
        job.recordFile(fileResult("r.0.0", Status.DELETED));
        assertEquals(1, job.doneFiles());
        assertEquals(0, job.failedFiles());
        assertEquals("Tidying old files", job.currentStep());
        assertEquals(1, job.completedFiles());
        // SKIPPED advances done, never failed.
        job.recordFile(fileResult("r.0.1", Status.SKIPPED));
        assertEquals(2, job.doneFiles());
        assertEquals(0, job.failedFiles());
        assertEquals("r.0.1", job.lastFileShort());
    }

    @Test
    public void percentAndHumanEtaEdgeCases() {
        JobQueue queue = new JobQueue();
        ConvertJob unknown = queue.submit(spec(0));
        assertEquals(-1, unknown.percent());
        assertEquals("calculating", unknown.humanEta(unknown.startedAtMs() + 5000L));

        ConvertJob job = queue.submit(spec(4));
        assertEquals(0, job.percent());
        assertEquals(0L, job.runStartedAtMs());
        assertEquals("calculating", job.humanEta(job.startedAtMs() + 5000L));

        job.recordFile(fileResult("r.0.0", Status.DELETED));
        assertEquals(25, job.percent());
        assertTrue(job.runStartedAtMs() > 0L);
        String eta = job.humanEta(job.runStartedAtMs() + 60_000L);
        assertTrue(eta, eta.endsWith(" left"));

        job.complete();
        assertEquals("finished", job.humanEta(job.runStartedAtMs() + 999_999L));

        ConvertJob failed = queue.submit(spec(2));
        failed.start();
        failed.fail("boom");
        assertEquals("finished", failed.humanEta(System.currentTimeMillis()));
        assertEquals("Stopped", failed.plainState());
    }

    @Test
    public void plainStateShortIdAndDirectionWords() {
        JobQueue queue = new JobQueue();
        ConvertJob job = queue.submit(spec(1));
        assertEquals("Waiting", job.plainState());
        assertEquals(8, job.shortId().length());
        assertTrue(job.id().toString().startsWith(job.shortId()));
        assertEquals("Old saves to new faster saves",
            ConvertDirection.MCA_TO_LINEAR.plainDirection());
        assertEquals("New saves back to old",
            ConvertDirection.LINEAR_TO_MCA.plainDirection());
        job.start();
        assertEquals("Working", job.plainState());
        job.pause();
        assertEquals("Paused", job.plainState());
        job.resume();
        job.complete();
        assertEquals("Finished", job.plainState());
        ConvertJob cancelled = queue.submit(spec(1));
        cancelled.cancel();
        assertEquals("Cancelled", cancelled.plainState());
    }

    @Test
    public void detailsRingBufferAndRepeatFail() {
        JobQueue queue = new JobQueue();
        ConvertJob job = queue.submit(spec(30));
        job.start();
        for (int i = 0; i < 25; i++) {
            job.recordFile(fileResult("r." + i, Status.DELETED));
        }
        assertEquals(ConvertJob.MAX_DETAILS, job.details().size());
        assertTrue(job.details().get(ConvertJob.MAX_DETAILS - 1).contains("r.24"));
        for (String line : job.details()) {
            assertTrue("full path leaked: " + line, !line.contains("/"));
        }
        job.fail("first");
        job.fail("second");
        assertEquals(JobState.FAILED, job.state());
        assertTrue(job.lastPlainReason().contains("second"));
        assertTrue(job.details().size() <= ConvertJob.MAX_DETAILS);
    }

    @Test
    public void retryCountStartsAtZero() {
        JobQueue queue = new JobQueue();
        ConvertJob job = queue.submit(spec(2));
        assertEquals(0, job.retryCount());
        job.start();
        job.recordRetry();
        job.recordRetry();
        assertEquals(2, job.retryCount());
    }

    @Test
    public void listIsOldestFirstAndPrefixFinds() {
        JobQueue queue = new JobQueue();
        ConvertJob first = queue.submit(spec(1));
        ConvertJob second = queue.submit(spec(1));
        List<ConvertJob> listed = queue.list();
        assertEquals(2, listed.size());
        assertTrue(listed.get(0).startedAtMs() <= listed.get(1).startedAtMs());
        assertEquals(first, queue.findByPrefix(first.shortId()));
        assertEquals(second, queue.findByPrefix(second.id().toString()));
        assertNull(queue.findByPrefix("zzzzzzzz"));
        assertNull(queue.findByPrefix(""));
        assertNull(queue.findByPrefix(null));
    }

    @Test
    public void ambiguousPrefixReturnsNull() {
        JobQueue queue = new JobQueue();
        for (int i = 0; i < 20; i++) {
            queue.submit(spec(1));
        }
        // 20 random ids over 16 hex leading chars guarantee a 1-char collision.
        String ambiguous = null;
        for (char c = '0'; c <= 'f'; c++) {
            if (queue.countByPrefix(String.valueOf(c)) > 1) {
                ambiguous = String.valueOf(c);
                break;
            }
        }
        assertNotNull("expected a colliding 1-char prefix among 20 jobs", ambiguous);
        assertNull(queue.findByPrefix(ambiguous));
        // Full ids stay unambiguous.
        for (ConvertJob job : queue.list()) {
            assertEquals(job, queue.findByPrefix(job.id().toString()));
        }
    }

    @Test
    public void shortPrefixIsRejectedFailClosed() {
        JobQueue queue = new JobQueue();
        ConvertJob job = queue.submit(spec(1));
        String full = job.id().toString();
        // 1-3 chars can never resolve, even when they match: too little
        // entropy across random UUIDs, so fail-closed null (the caller
        // reports ambiguous/use-full-id instead of guessing).
        assertNull(queue.findByPrefix(full.substring(0, 1)));
        assertNull(queue.findByPrefix(full.substring(0, 2)));
        assertNull(queue.findByPrefix(full.substring(0, 3)));
        // At the minimum length a unique prefix still resolves, and matching
        // stays case-insensitive after trimming.
        assertEquals(job, queue.findByPrefix(full.substring(0, 4)));
        assertEquals(job, queue.findByPrefix(full.substring(0, 8).toUpperCase(java.util.Locale.ROOT)));
        assertEquals(job, queue.findByPrefix("  " + job.shortId() + "  "));
    }

    @Test
    public void longAmbiguousPrefixReturnsNull() {
        // 5000 random ids over 65536 4-hex prefixes collide near-certainly
        // (birthday bound); scan for one instead of assuming it.
        JobQueue queue = new JobQueue();
        for (int i = 0; i < 5000; i++) {
            queue.submit(spec(1));
        }
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (ConvertJob job : queue.list()) {
            String head = job.id().toString().substring(0, 4);
            counts.put(head, counts.getOrDefault(head, 0) + 1);
        }
        String ambiguous = null;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                ambiguous = e.getKey();
                break;
            }
        }
        assertNotNull("expected a colliding 4-char prefix among 5000 jobs", ambiguous);
        assertNull(queue.findByPrefix(ambiguous));
        assertTrue(queue.countByPrefix(ambiguous) > 1);
    }

    private static String commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && Character.toLowerCase(a.charAt(i)) == Character.toLowerCase(b.charAt(i))) {
            i++;
        }
        return a.substring(0, i);
    }

    @Test
    public void droppedByRestartLineIsExact() {
        String line = JobQueue.droppedByRestartLine(3);
        String[] parts = line.split("\n", -1);
        assertEquals(2, parts.length);
        for (String part : parts) {
            assertTrue("too wide (" + part.length() + "): " + part, part.length() <= 55);
        }
        assertTrue(line, line.contains("Your old files are still there"));
        assertEquals(
            "Jobs were lost on restart. Start them again (3).\n"
                + "Your old files are still there. Nothing changed.",
            line);
    }

    @Test
    public void permissionConstantsAndDenial() {
        assertEquals("linear.command.linearstats", LinearPermissions.STATS);
        assertEquals("linear.command.linear", LinearPermissions.BASE);
        assertEquals("linear.command.convert", LinearPermissions.CONVERT);
        assertEquals("linear.command.queue", LinearPermissions.QUEUE);
        assertEquals("You do not have permission to use linearstats.",
            LinearPermissions.denied("linearstats"));
        assertEquals("linearstats: no Linear folders tracked (no linear I/O yet).",
            LinearPermissions.EMPTY_STATE);
    }

    @Test
    public void humanBytesEdgeCases() {
        assertEquals("0B", LinearStatsFormat.humanBytes(0L));
        assertEquals("1023B", LinearStatsFormat.humanBytes(1023L));
        assertEquals("1.0KiB", LinearStatsFormat.humanBytes(1024L));
        assertEquals("1.0MiB", LinearStatsFormat.humanBytes(1024L * 1024L));
    }

    @Test
    public void humanMicrosCases() {
        assertEquals("—", LinearStatsFormat.humanMicros(-1L));
        assertEquals("0us", LinearStatsFormat.humanMicros(0L));
        assertEquals("999us", LinearStatsFormat.humanMicros(999L));
        assertEquals("1.0ms", LinearStatsFormat.humanMicros(1000L));
        assertEquals("1.50s", LinearStatsFormat.humanMicros(1_500_000L));
    }

    @Test
    public void humanMillisSinceCases() {
        assertEquals("never", LinearStatsFormat.humanMillisSince(-1L));
        assertEquals("0ms", LinearStatsFormat.humanMillisSince(0L));
        assertEquals("999ms", LinearStatsFormat.humanMillisSince(999L));
        assertEquals("1.0s", LinearStatsFormat.humanMillisSince(1000L));
        assertEquals("59.9s", LinearStatsFormat.humanMillisSince(59_900L));
        assertEquals("1m 0s", LinearStatsFormat.humanMillisSince(60_000L));
        assertEquals("2m 5s", LinearStatsFormat.humanMillisSince(125_000L));
    }

    @Test
    public void savedPercentAndDirtyBar() {
        assertEquals(0L, LinearStatsFormat.savedPercent(0L, 0L));
        assertEquals(0L, LinearStatsFormat.savedPercent(-5L, 10L));
        assertEquals(0L, LinearStatsFormat.savedPercent(100L, 100L));
        assertEquals(0L, LinearStatsFormat.savedPercent(100L, 120L));
        assertEquals(50L, LinearStatsFormat.savedPercent(100L, 50L));
        assertEquals(100L, LinearStatsFormat.savedPercent(100L, -10L));
        String bar = LinearStatsFormat.dirtyBar(0, 10);
        assertEquals("[░░░░░░░░░░] 0/10", bar);
        assertEquals("[██████████] 10/10", LinearStatsFormat.dirtyBar(10, 10));
        assertTrue(LinearStatsFormat.dirtyBar(5, 10).startsWith("[█████░░░░░]"));
    }

    private static void expectIllegal(Runnable r) {
        try {
            r.run();
            fail("expected IllegalArgumentException/IllegalStateException");
        } catch (IllegalArgumentException | IllegalStateException expected) {
        }
    }
}
