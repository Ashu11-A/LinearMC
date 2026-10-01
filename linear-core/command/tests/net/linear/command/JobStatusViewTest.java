package net.linear.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;

import net.linear.LinearRegionConverter.FileResult;
import net.linear.LinearRegionConverter.Status;

import org.junit.Test;

/** command: plain-words JobStatusView (widths, vocabulary, next actions). */
public class JobStatusViewTest {

    private static ConvertJob job(int totalFiles) {
        return new ConvertJob(ConvertDirection.MCA_TO_LINEAR, "world", 6, 1, false, totalFiles);
    }

    private static FileResult fileResult(String name, Status status) {
        return new FileResult(Path.of(name + ".mca"), Path.of(name + ".linear"), status, "ok");
    }

    @Test
    public void statusLineShapeAndWidth() {
        ConvertJob job = job(200);
        for (int i = 0; i < 90; i++) {
            job.recordFile(fileResult("r." + i, Status.DELETED));
        }
        String line = JobStatusView.statusLine(job, job.runStartedAtMs() + 120_000L);
        assertTrue(line, line.contains("Working"));
        assertTrue(line, line.contains("45%"));
        assertTrue(line, line.contains("90/200"));
        assertTrue(line, line.contains("Tidying old files"));
        assertTrue(line, line.endsWith("left"));
        assertTrue("too wide: " + line, line.length() <= 55);
    }

    @Test
    public void everyStateStaysWithinWidthAndPlainWords() {
        ConvertJob waiting = job(10);
        ConvertJob working = job(10);
        working.recordFile(fileResult("r.0.0", Status.CONVERTED));
        ConvertJob paused = job(10);
        paused.start();
        paused.pause();
        ConvertJob finished = job(2);
        finished.recordFile(fileResult("r.0.0", Status.DELETED));
        finished.recordFile(fileResult("r.0.1", Status.DELETED));
        finished.complete();
        ConvertJob stopped = job(2);
        stopped.start();
        stopped.fail("boom");
        ConvertJob cancelled = job(2);
        cancelled.cancel();
        ConvertJob[] jobs = {waiting, working, paused, finished, stopped, cancelled};
        String[] states = {"Waiting", "Working", "Paused", "Finished", "Stopped", "Cancelled"};
        for (int i = 0; i < jobs.length; i++) {
            long now = Math.max(System.currentTimeMillis(), jobs[i].runStartedAtMs() + 1000L);
            String line = JobStatusView.statusLine(jobs[i], now);
            assertTrue(line, line.startsWith(states[i]));
            assertTrue("too wide: " + line, line.length() <= 55);
            assertPlain(line);
            List<String> details = JobStatusView.detailLines(jobs[i], now);
            assertTrue(details.size() <= 4);
            for (String detail : details) {
                assertTrue("too wide: " + detail, detail.length() <= 55);
                assertPlain(detail);
            }
            String list = JobStatusView.queueListLine(jobs[i], now);
            assertTrue("too wide: " + list, list.length() <= 55);
            assertPlain(list);
        }
    }

    @Test
    public void detailLinesStepTryAndNextAction() {
        ConvertJob job = job(4);
        job.recordFile(fileResult("r.0.0", Status.CONVERTED));
        job.recordRetry();
        job.recordRetry();
        List<String> lines =
            JobStatusView.detailLines(job, job.runStartedAtMs() + 1000L);
        assertTrue(lines.size() <= 4);
        assertTrue(lines.get(0), lines.get(0).contains("Old saves to new faster saves"));
        assertTrue(lines.get(1), lines.get(1).contains("Copying files"));
        assertTrue(lines.get(1), lines.get(1).contains("r.0.0"));
        assertTrue(lines.toString(), lines.contains("Try 3 of 3"));
        assertTrue(lines.toString(), lines.contains("Your old files are still there"));
    }

    @Test
    public void dryRunAndFailedNextActions() {
        ConvertJob dry = new ConvertJob(
            ConvertDirection.MCA_TO_LINEAR, "world", 6, 1, true, 2);
        dry.start();
        List<String> dryLines = JobStatusView.detailLines(dry, System.currentTimeMillis());
        assertTrue(dryLines.toString(), dryLines.contains("Practice run, nothing changed"));

        ConvertJob failed = job(2);
        failed.start();
        failed.fail("ConversionProtectionException: file is protected");
        List<String> failedLines = JobStatusView.detailLines(failed, System.currentTimeMillis());
        assertTrue(failedLines.toString(), failedLines.contains("A file is locked. Try again when it is quiet."));
    }

    @Test
    public void failedActionMapsEveryCause() {
        assertEquals("A file is locked. Try again when it is quiet.",
            JobStatusView.failedAction("ConversionProtectionException: protected region"));
        assertEquals("Files are still busy. Wait a bit, then try again.",
            JobStatusView.failedAction("quiesce failed: dirty folders remain"));
        assertEquals("A back-to-old file failed checks. Try again.",
            JobStatusView.failedAction("linear2mca reverse failed, use offline rollback"));
        assertEquals("A back-to-old file failed checks. Try again.",
            JobStatusView.failedAction("Reverse chunk-count mismatch source=1 target=2"));
        assertEquals("A copy lost its shadow. Try again.",
            JobStatusView.failedAction("shadow copy missing"));
        assertEquals("Jobs were forgot on restart. Start again.",
            JobStatusView.failedAction("jobs dropped by restart, not persisted"));
        assertEquals("Try again. Ask for help if it keeps failing.",
            JobStatusView.failedAction("something odd happened"));
        assertEquals("Try again. Ask for help if it keeps failing.",
            JobStatusView.failedAction(null));
        for (String reason : new String[]{"protected", "dirty", "reverse", "shadow",
            "restart", null, "other"}) {
            String action = JobStatusView.failedAction(reason);
            assertTrue("too wide: " + action, action.length() <= 55);
        }
    }

    @Test
    public void detailLinesTracksPerFileAttempt() {
        ConvertJob job = job(4);
        job.recordFile(fileResult("r.0.0", Status.CONVERTED));
        // Converter reports the failed attempt number; the view shows next try.
        job.recordRetry(1);
        List<String> retry1 =
            JobStatusView.detailLines(job, job.runStartedAtMs() + 1000L);
        assertTrue(retry1.toString(), retry1.contains("Try 2 of 3"));
        job.recordRetry(2);
        List<String> retry2 =
            JobStatusView.detailLines(job, job.runStartedAtMs() + 1000L);
        assertTrue(retry2.toString(), retry2.contains("Try 3 of 3"));
        // A fresh file retry does not accumulate the global sum: attempt 1
        // on the next file drops back to Try 2.
        job.recordRetry(1);
        List<String> fresh =
            JobStatusView.detailLines(job, job.runStartedAtMs() + 1000L);
        assertTrue(fresh.toString(), fresh.contains("Try 2 of 3"));
    }

    @Test
    public void queueListLineStartsWithShortId() {
        ConvertJob job = job(200);
        for (int i = 0; i < 90; i++) {
            job.recordFile(fileResult("r." + i, Status.DELETED));
        }
        String line =
            JobStatusView.queueListLine(job, job.runStartedAtMs() + 1000L);
        assertTrue(line, line.startsWith(job.shortId() + " Working 45% 90/200"));
        assertTrue("too wide: " + line, line.length() <= 55);
    }

    private static void assertPlain(String line) {
        for (String banned : new String[]{"QUEUED", "RUNNING", "PAUSED", "DONE", "FAILED",
            "CANCELLED", "ETA", "CONVERTED", "VALIDATED", "DELETED", "SKIPPED",
            ".mca", ".linear"}) {
            assertTrue("jargon in '" + line + "': " + banned, !line.contains(banned));
        }
    }
}
