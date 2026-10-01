package net.linear.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plain-words convert-job view (mirrors the {@link LinearStatsCompact}
 * pattern: pure JDK text, legs only send the strings, no colour codes here).
 *
 * <p>Every emitted line is at most {@value #WIDTH} chars and uses plain
 * words only: {@code Waiting/Working/Paused/Finished/Stopped/Cancelled} for
 * states, {@code Copying files/Checking files/Tidying old files} for steps.
 */
public final class JobStatusView {

    /** Max chars per emitted line (chat width). */
    static final int WIDTH = 55;

    private JobStatusView() {
    }

    /**
     * One-line summary, e.g. {@code Working 45% 90/200 Checking files 2m left}.
     * Never longer than {@value #WIDTH} chars.
     */
    public static String statusLine(ConvertJob job, long nowMs) {
        if (job == null) {
            throw new IllegalArgumentException("job must not be null");
        }
        String pct = job.percent() < 0 ? "--%" : job.percent() + "%";
        String progress = job.completedFiles() + "/" + job.totalFiles();
        StringBuilder sb = new StringBuilder();
        sb.append(job.plainState()).append(' ').append(pct).append(' ').append(progress);
        String step = job.currentStep();
        if (step != null && !step.isEmpty()) {
            sb.append(' ').append(step);
        }
        sb.append(' ').append(job.humanEta(nowMs));
        return fit(sb.toString());
    }

    /**
     * Up to 4 plain-words lines: what the job is doing, which file it is on,
     * which try it is on, and what to do next. Each line is at most
     * {@value #WIDTH} chars.
     */
    public static List<String> detailLines(ConvertJob job, long nowMs) {
        if (job == null) {
            throw new IllegalArgumentException("job must not be null");
        }
        List<String> lines = new ArrayList<>(4);
        lines.add(fit(job.direction().plainDirection() + " " + job.world()));
        String step = job.currentStep();
        String file = job.lastFileShort();
        if (step == null || step.isEmpty()) {
            if (job.state() == JobState.QUEUED) {
                lines.add("Waiting to start");
            } else {
                lines.add(job.plainState());
            }
        } else if (file == null || file.isEmpty()) {
            lines.add(fit(step));
        } else {
            lines.add(fit(step + " " + file));
        }
        if (job.runStartedAtMs() > 0L) {
            lines.add("Try " + job.currentTry() + " of 3");
        }
        lines.add(fit(nextAction(job)));
        while (lines.size() > 4) {
            lines.remove(1);
        }
        return lines;
    }

    /**
     * One short line per job for {@code queue list}, e.g.
     * {@code a1b2c3d4 Working 45% 90/200}. Never longer than
     * {@value #WIDTH} chars.
     */
    public static String queueListLine(ConvertJob job, long nowMs) {
        if (job == null) {
            throw new IllegalArgumentException("job must not be null");
        }
        String pct = job.percent() < 0 ? "--%" : job.percent() + "%";
        return fit(job.shortId() + " " + job.plainState() + " " + pct
            + " " + job.completedFiles() + "/" + job.totalFiles());
    }

    private static String nextAction(ConvertJob job) {
        if (job.state() == JobState.FAILED) {
            return failedAction(job.lastPlainReason());
        }
        if (job.state() == JobState.CANCELLED) {
            return "Stopped by you. Start again if you want.";
        }
        if (job.dryRun()) {
            return "Practice run, nothing changed";
        }
        return "Your old files are still there";
    }

    /**
     * Next action for a failure reason, matched by cause: protection (locked
     * file), quiesce (busy files), reverse back-to-old checks, shadow
     * (lost copy), restart-drop (forgotten queue). Plain words only, at most
     * {@value #WIDTH} chars.
     */
    public static String failedAction(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return "Try again. Ask for help if it keeps failing.";
        }
        String low = reason.toLowerCase(Locale.ROOT);
        if (low.contains("protect")) {
            return "A file is locked. Try again when it is quiet.";
        }
        if (low.contains("quiesce") || low.contains("dirty")
            || low.contains("busy") || low.contains("unflushed")) {
            return "Files are still busy. Wait a bit, then try again.";
        }
        if (low.contains("reverse") || low.contains("linear2mca")
            || low.contains("back-to-old")) {
            return "A back-to-old file failed checks. Try again.";
        }
        if (low.contains("shadow")) {
            return "A copy lost its shadow. Try again.";
        }
        if (low.contains("restart") || low.contains("dropped")
            || low.contains("persist")) {
            return "Jobs were forgot on restart. Start again.";
        }
        if (low.contains("cancel")) {
            return "Stopped by you. Start again if you want.";
        }
        return "Try again. Ask for help if it keeps failing.";
    }

    static String fit(String line) {
        if (line == null) {
            return "";
        }
        return line.length() <= WIDTH ? line : line.substring(0, WIDTH);
    }
}
