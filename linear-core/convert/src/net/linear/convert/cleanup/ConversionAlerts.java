package net.linear.convert.cleanup;

import java.nio.file.Path;
import net.linear.LinearRegionConverter;
import net.linear.RegionFileFormat;

/**
 * Additive convert shim: log formatting for protection alerts.
 *
 * <p>Facade-adoption note: {@code LinearRegionConverter} still owns the
 * protection alerts. When wiring delegates, the facade adopts these helpers
 * wholesale (logic copied byte-identically from the facade's static
 * implementation).</p>
 */
public final class ConversionAlerts {

    private ConversionAlerts() {
    }

    /**
     * One-line source-to-target extension summary for alerts
     * (e.g. {@code .mca -> .b_linear}); heterogeneous failure sets report
     * {@code mixed} rather than guessing.
     */
    public static String describePairExtensions(java.util.List<LinearRegionConverter.FileResult> failures) {
        String pair = null;
        for (LinearRegionConverter.FileResult f : failures) {
            String current = extensionOf(f.source()) + " -> " + extensionOf(f.target());
            if (pair == null) {
                pair = current;
            } else if (!pair.equals(current)) {
                return "mixed";
            }
        }
        return pair != null ? pair : "unknown";
    }

    /** On-disk extension label for log lines ({@code .mca/.linear/.b_linear}, else unknown). */
    public static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
            return ".b_linear";
        }
        if (name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            return ".linear";
        }
        if (name.endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
            return ".mca";
        }
        return "(unknown)";
    }

    /**
     * Formats the forward protection-mode message (verbatim facade logic;
     * caller logs via its own logger to preserve logger identity).
     */
    public static String formatProtectionMessage(Path regionFolder,
            LinearRegionConverter.ConversionSummary summary, boolean haltsServer, String explicitRemedy) {
        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("LINEAR CONVERSION PROTECTION MODE\n");
        sb.append("============================================================\n");
        sb.append("Folder: ").append(regionFolder.toAbsolutePath()).append("\n");
        sb.append("Direction: ").append(describePairExtensions(summary.failures())).append("\n");
        sb.append("Converted=").append(summary.converted())
            .append(" Validated=").append(summary.validated())
            .append(" Deleted=").append(summary.deleted())
            .append(" Failed=").append(summary.failed()).append("\n");
        sb.append("Guarantee: sources are NEVER deleted before validation passes.\n");
        sb.append("All failed files below still have their original sources in place.\n");
        sb.append("Partial targets (if any) were left beside the source.\n");
        sb.append("------------------------------------------------------------\n");
        for (LinearRegionConverter.FileResult f : summary.failures()) {
            sb.append("FAILED source=").append(f.source())
                .append(" target=").append(f.target())
                .append(" detail=").append(f.detail()).append("\n");
        }
        sb.append("------------------------------------------------------------\n");
        sb.append("Operator next steps:\n");
        sb.append(" 1. Inspect each FAILED source above (file size, readability,\n");
        sb.append("    disk space, permissions). Common causes: corrupt region\n");
        sb.append("    header, truncated chunk, full disk, read-only mount.\n");
        sb.append(" 2. Restore the file from backup, or free disk / fix perms.\n");
        sb.append(" 3. Do NOT manually delete source files; re-run the server and\n");
        sb.append("    conversion resumes automatically (unconverted sources remain).\n");
        sb.append(" 4. If a converted shadow exists beside a failed source (shadow-pair\n");
        sb.append("    escalation), compare chunk CONTENTS before touching either file:\n");
        sb.append("    equal counts do not prove redundancy after a one-way conversion.\n");
        if (explicitRemedy != null) {
            sb.append(" 5. ").append(explicitRemedy).append("\n");
        }
        if (haltsServer) {
            sb.append("Server halts for inspection (caller halts on this exception).\n");
        } else {
            sb.append("The convert job recorded this failure and keeps running;"
                + " sources are untouched, re-run resumes automatically.\n");
        }
        sb.append("============================================================");
        return sb.toString();
    }

    /**
     * Formats the reverse protection-mode message (verbatim facade logic;
     * caller logs via its own logger to preserve logger identity).
     */
    public static String formatReverseProtectionMessage(Path regionFolder,
            LinearRegionConverter.ConversionSummary summary, boolean haltsServer) {
        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("LINEAR REVERSE CONVERSION PROTECTION MODE\n");
        sb.append("============================================================\n");
        sb.append("Folder: ").append(regionFolder.toAbsolutePath()).append("\n");
        sb.append("Direction: ").append(describePairExtensions(summary.failures()))
            .append(" (reverse, downgrade to anvil)\n");
        sb.append("Converted=").append(summary.converted())
            .append(" Validated=").append(summary.validated())
            .append(" Deleted=").append(summary.deleted())
            .append(" Failed=").append(summary.failed()).append("\n");
        sb.append("Guarantee: linear sources are NEVER deleted before reverse validation passes.\n");
        sb.append("All failed files below still have their original sources in place.\n");
        sb.append("Partial .mca targets (if any) were left beside the source.\n");
        sb.append("------------------------------------------------------------\n");
        for (LinearRegionConverter.FileResult f : summary.failures()) {
            sb.append("FAILED reverse source=").append(f.source())
                .append(" target=").append(f.target())
                .append(" detail=").append(f.detail()).append("\n");
        }
        sb.append("------------------------------------------------------------\n");
        sb.append("Operator next steps:\n");
        sb.append(" 1. Inspect each FAILED reverse source above (file size, readability,\n");
        sb.append("    disk space, permissions). Common causes: corrupt linear\n");
        sb.append("    header, truncated chunk, full disk, read-only mount.\n");
        sb.append(" 2. Restore the file from backup, or free disk / fix perms.\n");
        sb.append(" 3. Do NOT manually delete source files; re-run and reverse\n");
        sb.append("    conversion resumes automatically (unconverted sources remain).\n");
        sb.append(" 4. If a .mca shadow exists beside a failed linear source (reverse\n");
        sb.append("    shadow-pair escalation), compare chunk CONTENTS before touching\n");
        sb.append("    either file: equal counts do not prove redundancy.\n");
        if (haltsServer) {
            sb.append("Server halts for inspection (caller halts on this exception).\n");
        } else {
            sb.append("The convert job recorded this failure and keeps running;"
                + " sources are untouched, re-run resumes automatically.\n");
        }
        sb.append("============================================================");
        return sb.toString();
    }

    /** Forward alert delegate entry: logs via the caller's logger (facade identity preserved). */
    public static void logProtectionAlert(Path regionFolder, LinearRegionConverter.ConversionSummary summary,
            boolean haltsServer, String explicitRemedy, java.util.logging.Logger logger) {
        logger.severe(formatProtectionMessage(regionFolder, summary, haltsServer, explicitRemedy));
    }

    /** Reverse alert delegate entry: logs via the caller's logger (facade identity preserved). */
    public static void logReverseProtectionAlert(Path regionFolder, LinearRegionConverter.ConversionSummary summary,
            boolean haltsServer, java.util.logging.Logger logger) {
        logger.severe(formatReverseProtectionMessage(regionFolder, summary, haltsServer));
    }
}
