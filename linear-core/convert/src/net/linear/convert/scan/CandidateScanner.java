package net.linear.convert.scan;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.linear.AbstractRegionFile;
import net.linear.LinearRegionConverter;
import net.linear.RegionFileFormat;
import net.linear.versions.RegionFormatDetector;

/**
 * Candidate scanning for convert jobs: pure path helpers plus the
 * folder-listing candidate collectors moved verbatim out of
 * {@code LinearRegionConverter} (crash-resume step (b) for the forward,
 * reverse and explicit pipelines).
 *
 * <p>Ownership split: this file owns directory listing, name matching,
 * shadow-pair resolution and skip-gating. Listener callbacks, validation,
 * opens and skip predicates route back into
 * {@code LinearRegionConverter} (same package) so behavior stays
 * byte-identical to the facade's former static implementation.</p>
 */
public final class CandidateScanner {

    private static final java.util.logging.Logger LOGGER =
        java.util.logging.Logger.getLogger(CandidateScanner.class.getName());

    private static final Pattern REGION_NAME =
        Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(mca|linear|b_linear)$");
    private static final String NEW_DIRECTORY_PREFIX = "new_";

    /** Region coords parsed from a region file name. */
    public record RegionName(int x, int z) {}

    /** Explicit per-file candidate: source, target, and whether they are one inode (FR7). */
    public record ExplicitCandidate(Path source, Path target, boolean samePath) {}

    private CandidateScanner() {
    }

    /** Region coords parsed from {@code fileName}, or {@code null} when it does not match. */
    public static RegionName parseRegionName(String fileName) {
        Matcher m = REGION_NAME.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        try {
            return new RegionName(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Target path for a forward conversion toward {@code .linear}. */
    public static Path targetForSource(Path source) {
        return targetForSource(source, RegionFileFormat.LINEAR);
    }

    /** Target path for a forward conversion toward {@code targetFormat}. */
    public static Path targetForSource(Path source, RegionFileFormat targetFormat) {
        String ext = targetFormat == RegionFileFormat.B_LINEAR
            ? RegionFileFormat.B_LINEAR_EXTENSION
            : RegionFileFormat.LINEAR_EXTENSION;
        String name = source.getFileName().toString();
        for (String known : new String[] {RegionFileFormat.ANVIL_EXTENSION, RegionFileFormat.LINEAR_EXTENSION,
                RegionFileFormat.B_LINEAR_EXTENSION}) {
            if (name.endsWith(known)) {
                name = name.substring(0, name.length() - known.length()) + ext;
                return source.resolveSibling(name);
            }
        }
        return source.resolveSibling(name + ext);
    }

    /** Target path for a reverse conversion (linear-family toward {@code .mca}). */
    public static Path targetForSourceReverse(Path source) {
        String name = source.getFileName().toString();
        if (name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            name = name.substring(0, name.length() - RegionFileFormat.LINEAR_EXTENSION.length())
                + RegionFileFormat.ANVIL_EXTENSION;
        } else if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
            name = name.substring(0, name.length() - RegionFileFormat.B_LINEAR_EXTENSION.length())
                + RegionFileFormat.ANVIL_EXTENSION;
        } else {
            name = name + RegionFileFormat.ANVIL_EXTENSION;
        }
        return source.resolveSibling(name);
    }

    /** Target path for an explicit job (same-folder; {@code .linear} targets share the name). */
    public static Path targetForSourceExplicit(Path source, LinearRegionConverter.ConvertFormat to) {
        String ext = to == null ? RegionFileFormat.LINEAR_EXTENSION : to.extension();
        String name = source.getFileName().toString();
        for (String known : new String[] {RegionFileFormat.ANVIL_EXTENSION, RegionFileFormat.LINEAR_EXTENSION,
                RegionFileFormat.B_LINEAR_EXTENSION}) {
            if (name.endsWith(known)) {
                name = name.substring(0, name.length() - known.length()) + ext;
                return source.resolveSibling(name);
            }
        }
        return source.resolveSibling(name + ext);
    }

    /** Sibling {@code new_<folder>} staging directory for {@code directoryPath}. */
    public static Path resolveRecreateDirectory(Path directoryPath) {
        return directoryPath.resolveSibling(NEW_DIRECTORY_PREFIX + directoryPath.getFileName().toString());
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
     * Reverse crash-resume step (b): collect {@code .linear} candidates and
     * resolve shadow pairs.
     *
     * <p>Shadow policy (mirror of the forward path, reversed): a
     * {@code .linear + .mca} pair with the same coordinates means the anvil
     * target already exists. A pair that VALIDATES under the reverse checks
     * is NEVER auto-deleted: after a one-way upgrade both sides can hold
     * unique chunks at equal counts, and count-parity validation cannot prove
     * either side redundant. Valid-shadow pairs escalate to FAILED /
     * protection with NEITHER side deleted; the operator compares chunk
     * contents before touching either file. An INVALID shadow beside a
     * readable linear source is an orphan: drop the bad {@code .mca} and
     * requeue the {@code .linear} for a full convert (escalating instead when
     * the linear source itself is unreadable, so a corrupt source is never
     * destroyed — FAILED, both kept). Linear-only files queue for a full
     * convert; {@code .mca}-only files are already anvil and report SKIPPED.</p>
     */
    public static List<Path> collectReverseCandidates(Path regionFolder, LinearRegionConverter.Listener events,
            List<LinearRegionConverter.FileResult> preDone) {
        Map<String, Path> mca = new HashMap<>();
        Map<String, List<Path>> lin = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                Matcher m = REGION_NAME.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                String key = m.group(1) + "," + m.group(2);
                String ext = m.group(3);
                if ("mca".equals(ext)) {
                    mca.put(key, p);
                } else {
                    lin.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Linear reverse conversion: cannot list " + regionFolder + ": " + e.getMessage());
            return List.of();
        }

        List<Path> out = new ArrayList<>();
        for (Map.Entry<String, List<Path>> e : lin.entrySet()) {
            List<Path> sameCoords = e.getValue();
            if (sameCoords.size() > 1) {
                // Ambiguous same-coordinates linear sources (e.g.
                // r.0.0.linear beside r.0.0.b_linear): both map onto one
                // .mca target, so queueing either silently drops the other.
                // Escalate with everything kept; the operator keeps one
                // file, moves the other aside, and re-runs.
                sameCoords.sort(Comparator.comparing(p -> p.getFileName().toString()));
                preDone.add(new LinearRegionConverter.FileResult(sameCoords.get(0),
                    targetForSourceReverse(sameCoords.get(0)),
                    LinearRegionConverter.Status.FAILED,
                    "ambiguous same-coordinates reverse sources ("
                        + sameCoords.stream().map(p -> p.getFileName().toString())
                            .reduce((a, b) -> a + ", " + b).orElse("?")
                        + "); all files left untouched: keep one, move the rest aside, re-run"));
                LOGGER.severe("Linear reverse conversion: ambiguous same-coordinates sources left untouched: "
                    + sameCoords);
                continue;
            }
            Path source = sameCoords.get(0);
            Path shadow = mca.get(e.getKey());
            Path targetMca = shadow != null ? shadow : targetForSourceReverse(source);
            boolean skip;
            try {
                skip = LinearRegionConverter.REVERSE_SKIP.test(source);
            } catch (RuntimeException predicateFailed) {
                // Fail-closed: a throwing skip predicate never queues the file.
                // Report SKIPPED so the operator re-runs after the transient
                // predicate failure clears, instead of converting a possibly
                // loaded/dirty file.
                LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(source, targetMca,
                    LinearRegionConverter.Status.SKIPPED,
                    "skip check failed, deferred: " + String.valueOf(predicateFailed.getMessage())));
                continue;
            }
            if (skip) {
                LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(source, targetMca,
                    LinearRegionConverter.Status.SKIPPED,
                    "loaded or recently written, re-run to finish"));
                continue;
            }
            if (shadow == null) {
                out.add(source);
                continue;
            }
            boolean pairValid;
            try {
                LinearRegionConverter.validateReversePair(source, shadow);
                pairValid = true;
            } catch (Exception invalid) {
                pairValid = false;
            }
            if (pairValid) {
                // NEVER auto-delete a valid reverse shadow. One-way-upgrade caveat:
                // a post-upgrade pair can hold UNIQUE chunks on both sides at
                // equal counts, so count parity cannot prove either side
                // redundant and deleting either could destroy the only copy of
                // some chunks. Escalate to protection with NEITHER side deleted;
                // the operator compares chunk contents before touching either file.
                preDone.add(new LinearRegionConverter.FileResult(source, shadow,
                    LinearRegionConverter.Status.FAILED,
                    "reverse shadow pair: valid .mca shadow beside readable source; one-way upgrade may leave"
                        + " unique chunks on either side (count parity cannot prove redundancy);"
                        + " both files left untouched for operator comparison"));
                LOGGER.severe("Linear reverse conversion: valid shadow pair left untouched for inspection: source="
                    + source + " shadow=" + shadow);
                continue;
            }
            boolean sourceReadable;
            try {
                try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, source.getParent(),
                    RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                    RegionName coords = parseRegionName(source.getFileName().toString());
                    LinearRegionConverter.countChunks(src, coords.x(), coords.z());
                }
                sourceReadable = true;
            } catch (Exception corrupt) {
                sourceReadable = false;
            }
            if (!sourceReadable) {
                preDone.add(new LinearRegionConverter.FileResult(source, shadow,
                    LinearRegionConverter.Status.FAILED,
                    "reverse shadow pair: source unreadable; shadow left untouched for inspection"));
                LOGGER.severe("Linear reverse conversion: shadow pair with corrupt source left untouched: " + source);
                continue;
            }
            try {
                Files.deleteIfExists(shadow);
                LOGGER.info("Linear reverse conversion: removed invalid anvil shadow " + shadow.getFileName()
                    + "; requeueing " + source.getFileName());
            } catch (IOException deleteFail) {
                preDone.add(new LinearRegionConverter.FileResult(source, shadow,
                    LinearRegionConverter.Status.FAILED,
                    "reverse shadow pair: cannot remove invalid orphan target " + shadow + ": "
                    + deleteFail.getMessage()));
                continue;
            }
            out.add(source);
        }
        for (Map.Entry<String, Path> e : mca.entrySet()) {
            if (!lin.containsKey(e.getKey())) {
                Path t = e.getValue();
                Path pseudoSource = t.resolveSibling(
                    t.getFileName().toString().substring(0, t.getFileName().toString().length() - ".mca".length())
                        + RegionFileFormat.LINEAR_EXTENSION);
                LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(pseudoSource, t,
                    LinearRegionConverter.Status.SKIPPED, "already anvil; no linear source"));
            }
        }
        return out;
    }

    /**
     * Crash-resume step (b): collect conversion candidates and resolve shadow pairs.
     *
     * <p>Shadow policy: the live dual-read prefers {@code .mca}, so a
     * {@code .mca + .linear} pair with the same coordinates can only be a crash
     * remnant (live conversion deletes sources post-validation) or operator
     * action. A {@code .linear} shadow that FAILS validation is an orphan:
     * delete it and requeue the {@code .mca} for conversion (escalating instead
     * when the source itself is unreadable, so a corrupt source is never
     * destroyed). A shadow that VALIDATES is NEVER auto-deleted: after a
     * one-way downgrade both sides can hold unique chunks at equal counts, and
     * count-parity validation cannot prove the shadow redundant. Valid-shadow
     * pairs escalate to FAILED / protection with neither side deleted; the
     * operator compares chunk contents before touching either file.</p>
     */
    public static List<Path> collectCandidates(Path regionFolder, LinearRegionConverter.Listener events,
            List<LinearRegionConverter.FileResult> preFailures) {
        return collectForwardCandidates(regionFolder, events, preFailures, RegionFileFormat.LINEAR);
    }

    /**
     * Forward crash-resume step: collect convertible non-target candidates for
     * {@code targetFormat} and resolve shadow pairs. Union semantics: every
     * convertible format other than the target converts (e.g. toward LINEAR,
     * both {@code .mca} and {@code .b_linear} sources queue).
     *
     * <p>Shadow policy mirrors the legacy path per target: a target-format
     * file beside a convertible source is suspect. A pair that VALIDATES is
     * NEVER auto-deleted (one-way caveat: unique chunks can hide on both
     * sides at equal counts); it escalates to FAILED with NEITHER side
     * deleted. An INVALID shadow beside a readable source is an orphan: drop
     * the bad target and requeue the source (escalating instead when the
     * source itself is unreadable). Target-only files report SKIPPED.</p>
     */
    public static List<Path> collectForwardCandidates(Path regionFolder, LinearRegionConverter.Listener events,
            List<LinearRegionConverter.FileResult> preFailures, RegionFileFormat targetFormat) {
        String targetExt = targetFormat == RegionFileFormat.B_LINEAR
            ? RegionFileFormat.B_LINEAR_EXTENSION
            : RegionFileFormat.LINEAR_EXTENSION;
        Map<String, List<Path>> sourcesByCoords = new HashMap<>();
        Map<String, Path> targets = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                Matcher m = REGION_NAME.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                String key = m.group(1) + "," + m.group(2);
                String fileName = p.getFileName().toString();
                if (fileName.endsWith(targetExt)) {
                    targets.put(key, p);
                } else {
                    sourcesByCoords.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Linear conversion: cannot list " + regionFolder + ": " + e.getMessage());
            return List.of();
        }

        List<Path> out = new ArrayList<>();
        for (Map.Entry<String, List<Path>> e : sourcesByCoords.entrySet()) {
            List<Path> sameCoords = e.getValue();
            if (sameCoords.size() > 1) {
                // Ambiguous same-coordinates sources (e.g. r.0.0.mca beside
                // r.0.0.b_linear): queueing both would map them onto one
                // target path, and picking one silently drops the other.
                // Escalate with everything kept; the operator keeps one
                // file, moves the other aside, and re-runs.
                sameCoords.sort(Comparator.comparing(p -> p.getFileName().toString()));
                preFailures.add(new LinearRegionConverter.FileResult(sameCoords.get(0),
                    targetForSource(sameCoords.get(0), targetFormat), LinearRegionConverter.Status.FAILED,
                    "ambiguous same-coordinates sources ("
                        + sameCoords.stream().map(p -> p.getFileName().toString())
                            .reduce((a, b) -> a + ", " + b).orElse("?")
                        + "); all files left untouched: keep one, move the rest aside, re-run"));
                LOGGER.severe("Linear conversion: ambiguous same-coordinates sources left untouched: "
                    + sameCoords);
                continue;
            }
            Path source = sameCoords.get(0);
            Path shadow = targets.get(e.getKey());
            Path target = targetForSource(source, targetFormat);
            // F1: startup with a non-Pumpkin target must never convert Pumpkin sources.
            // A Pumpkin .linear beside a B_LINEAR target would otherwise queue as a
            // convertible source, convert, then delete the Pumpkin original. Skip it
            // (SKIPPED, source untouched) and name explicit convert as the remedy.
            // LINEAR targets never reach here (.linear files are target occupants,
            // handled as shadows below and left alone), so this only fires for B_LINEAR.
            if (LinearRegionConverter.isPumpkinLinearFile(source)) {
                LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(source, target,
                    LinearRegionConverter.Status.SKIPPED,
                    "Pumpkin layout, startup never converts Pumpkin;"
                        + " use /linear convert --from pumpkin --to <target> explicit migration"));
                LOGGER.info("Linear conversion: Pumpkin source skipped (startup never converts Pumpkin): "
                    + source.getFileName()
                    + "; use /linear convert --from pumpkin --to <target> explicit migration");
                continue;
            }
            if (shadow == null) {
                // Soak parity (d-load, Oct 2026): never queue a file the
                // server is actively writing blindly, or the copy races live
                // writes and the delete can orphan a live handle.
                boolean skip;
                try {
                    skip = LinearRegionConverter.FORWARD_SKIP.test(source);
                } catch (RuntimeException predicateFailed) {
                    // Fail-closed like reverse: a throwing skip check defers
                    // the file instead of converting a possibly loaded one.
                    LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(source, target,
                        LinearRegionConverter.Status.SKIPPED,
                        "skip check failed, deferred: " + String.valueOf(predicateFailed.getMessage())));
                    continue;
                }
                if (skip) {
                    LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(source, target,
                        LinearRegionConverter.Status.SKIPPED,
                        "loaded or recently written, re-run to finish"));
                    continue;
                }
                out.add(source);
                continue;
            }
            String shadowExt = shadow.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)
                ? ".b_linear"
                : ".linear";
            boolean targetValid;
            try {
                LinearRegionConverter.validatePair(source, shadow);
                targetValid = true;
            } catch (Exception invalid) {
                targetValid = false;
            }
            if (targetValid) {
                // NEVER auto-delete a valid shadow. One-way caveat: a pair
                // can hold UNIQUE chunks on both sides at equal counts, so
                // count parity cannot prove the shadow redundant and deleting
                // it could destroy the only copy of some chunks. Escalate to
                // protection with NEITHER side deleted; the operator compares
                // chunk contents before touching either file.
                preFailures.add(new LinearRegionConverter.FileResult(source, target,
                    LinearRegionConverter.Status.FAILED,
                    "shadow pair: valid " + shadowExt + " shadow beside readable source; one-way conversion may leave"
                        + " unique chunks on either side (count parity cannot prove redundancy);"
                        + " both files left untouched for operator comparison"));
                LOGGER.severe("Linear conversion: valid shadow pair left untouched for inspection: source="
                    + source + " shadow=" + shadow);
                continue;
            }
            // Orphan target is invalid: drop it and requeue the source.
            // If the source itself is corrupt, conversion attempts will fail
            // 3x and enter protection there (nothing deleted meanwhile).
            // Startup gate (FR3/FR7): a VALID Pumpkin (.linear wire-2 bucketed) shadow is
            // NEVER auto-deleted here. Startup auto-conversion only processes .mca
            // (+ .b_linear union) sources toward LINEAR and must not rewrite Pumpkin
            // files: a count-invalid but structurally-valid Pumpkin shadow could still
            // hold unique chunks (one-way caveat) that only an explicit --from/--to
            // job may touch. Escalate with both kept instead. Corrupt/ambiguous
            // shadows keep the legacy orphan path (a 10-byte torn header was never a
            // valid Pumpkin file and is safe to drop when the source is readable).
            try {
                String shadowName = shadow.getFileName().toString();
                if (shadowName.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                    boolean shadowIsPumpkin = false;
                    boolean shadowAmbiguousPumpkin = false;
                    try {
                        shadowIsPumpkin = LinearRegionConverter.detectLinearFormat(shadow)
                            == net.linear.versions.RegionFormatDetector.DetectedFormat.PUMPKIN;
                    } catch (IOException detectFail) {
                        String msg = String.valueOf(detectFail.getMessage());
                        // Ambiguous layouts naming PUMPKIN could be genuine Pumpkin bytes
                        // (both V1 length equation and Pumpkin structure verify): escalate.
                        // Pure corrupt/unknown (zero candidates, e.g. 10-byte torn header)
                        // keeps the legacy orphan path below.
                        shadowAmbiguousPumpkin = msg.contains("Ambiguous")
                            && msg.contains("PUMPKIN");
                    }
                    if (shadowIsPumpkin || shadowAmbiguousPumpkin) {
                        preFailures.add(new LinearRegionConverter.FileResult(source, target,
                            LinearRegionConverter.Status.FAILED,
                            "shadow pair: Pumpkin shadow beside readable source;"
                                + " startup auto-conversion never rewrites Pumpkin files;"
                                + " both files left untouched for operator comparison;"
                                + " use /linear convert --from pumpkin --to <target> explicit migration"));
                        LOGGER.severe("Linear conversion: Pumpkin shadow left untouched (no auto-rewrite): source="
                            + source + " shadow=" + shadow
                            + "; use /linear convert --from pumpkin --to <target> explicit migration");
                        continue;
                    }
                }
            } catch (RuntimeException gateFail) {
                // Fail-closed: a throwing gate never queues the file for overwrite.
                preFailures.add(new LinearRegionConverter.FileResult(source, target,
                    LinearRegionConverter.Status.FAILED,
                    "shadow pair: Pumpkin gate failed; both files left untouched for inspection"));
                LOGGER.severe("Linear conversion: Pumpkin gate failure left untouched: source="
                    + source + " shadow=" + shadow);
                continue;
            }
            boolean sourceReadable;
            try {
                try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, source.getParent(),
                    RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                    RegionName coords = parseRegionName(source.getFileName().toString());
                    LinearRegionConverter.countChunks(src, coords.x(), coords.z());
                }
                sourceReadable = true;
            } catch (Exception corrupt) {
                sourceReadable = false;
            }
            if (!sourceReadable) {
                // Source unreadable; shadow left untouched for inspection:
                // conversion cannot succeed, so escalate now rather than
                // burning 3 doomed attempts. Neither side is deleted.
                preFailures.add(new LinearRegionConverter.FileResult(source, target,
                    LinearRegionConverter.Status.FAILED,
                    "shadow pair: source unreadable; shadow left untouched for inspection"));
                LOGGER.severe("Linear conversion: shadow pair with corrupt source left untouched: " + source);
                continue;
            }
            try {
                Files.deleteIfExists(shadow);
                Files.deleteIfExists(shadow.resolveSibling(shadow.getFileName() + ".swp"));
                LOGGER.info("Linear conversion: removed invalid shadow " + shadow.getFileName()
                    + "; requeueing " + source.getFileName());
            } catch (IOException deleteFail) {
                preFailures.add(new LinearRegionConverter.FileResult(source, target,
                    LinearRegionConverter.Status.FAILED,
                    "shadow pair: cannot remove invalid orphan target " + shadow + ": " + deleteFail.getMessage()));
                continue;
            }
            out.add(source);
        }
        // Already-converted (target-only) files are reported as SKIPPED for visibility.
        for (Map.Entry<String, Path> e : targets.entrySet()) {
            if (!sourcesByCoords.containsKey(e.getKey())) {
                Path t = e.getValue();
                String base = t.getFileName().toString();
                base = base.substring(0, base.length() - targetExt.length());
                Path pseudoSource = t.resolveSibling(base + RegionFileFormat.ANVIL_EXTENSION);
                LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(pseudoSource, t,
                    LinearRegionConverter.Status.SKIPPED, "already converted; no source"));
            }
        }
        // No lock file is used: conversion runs exactly once on the single boot
        // thread before worlds load, so no second converter can race it. A lock
        // file would itself need crash-resume (stale-lock detection), adding
        // failure modes for no concurrency benefit.
        return out;
    }

    /**
     * Explicit crash-resume step: collect filtered candidates and resolve shadow pairs.
     *
     * <p>Real rule (FR7): a same-path {@code .linear} convert
     * ({@code source.equals(target)}, same coords, same folder) is one inode,
     * never two files — it swaps via tmp + pre-swap validation + atomic swap.
     * A cross-extension shadow (e.g. an MCA source whose Pumpkin target path
     * is already occupied by an existing V1 {@code .linear} file — same target
     * path per FR7, different source extension) escalates to FAILED with both
     * files kept, never deleted, never silently overwritten. Valid shadows of
     * the SAME layout follow the legacy valid-shadow escalation; invalid
     * shadows of the same layout follow the legacy orphan-delete + requeue
     * (except {@code .linear} shadows, which are never auto-deleted).
     * Pumpkin shadows are NEVER deleted (startup auto-conversion must not
     * rewrite Pumpkin files): a Pumpkin shadow beside a convertible source
     * always escalates, even when count-invalid, so no silent cross-format
     * rewrite can happen.
     */
    public static List<ExplicitCandidate> collectExplicitCandidates(Path regionFolder,
            LinearRegionConverter.Listener events, List<LinearRegionConverter.FileResult> preFailures,
            LinearRegionConverter.ConvertFormat from, LinearRegionConverter.ConvertFormat to) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                Matcher m = REGION_NAME.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                files.add(p);
            }
        } catch (IOException e) {
            LOGGER.warning("Linear explicit conversion: cannot list " + regionFolder + ": " + e.getMessage());
            return List.of();
        }

        // Group convertible files by coords to detect same-target ambiguity
        // (two sources mapping onto one target path would silently drop one).
        Map<String, List<Path>> byCoords = new HashMap<>();
        for (Path p : files) {
            Matcher m = REGION_NAME.matcher(p.getFileName().toString());
            if (!m.matches()) {
                continue;
            }
            String key = m.group(1) + "," + m.group(2);
            byCoords.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<ExplicitCandidate> out = new ArrayList<>();
        for (Map.Entry<String, List<Path>> e : byCoords.entrySet()) {
            List<Path> group = e.getValue();
            // Determine actual formats (detector for .linear; fail-closed entries escalate).
            Map<Path, LinearRegionConverter.ConvertFormat> actualByFile = new HashMap<>();
            Map<Path, String> detectError = new HashMap<>();
            for (Path p : group) {
                String name = p.getFileName().toString();
                try {
                    if (name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                        actualByFile.put(p, LinearRegionConverter.actualFormatOf(p));
                    } else if (name.endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                        actualByFile.put(p, LinearRegionConverter.ConvertFormat.MCA);
                    } else if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                        actualByFile.put(p, LinearRegionConverter.ConvertFormat.B_LINEAR);
                    }
                } catch (IOException corrupt) {
                    detectError.put(p, corrupt.getMessage());
                }
            }
            // Corrupt/ambiguous .linear files never queue: escalate with everything kept.
            for (Map.Entry<Path, String> bad : detectError.entrySet()) {
                Path p = bad.getKey();
                Path pseudoTarget = targetForSourceExplicit(p, to);
                preFailures.add(new LinearRegionConverter.FileResult(p, pseudoTarget,
                    LinearRegionConverter.Status.FAILED,
                    "explicit detect failed (corrupt/ambiguous layout, both kept): " + bad.getValue()));
                LOGGER.severe("Linear explicit conversion: corrupt layout left untouched: " + p
                    + " (" + bad.getValue() + ")");
            }
            if (!detectError.isEmpty()) {
                continue;
            }

            // Filter by --from (AUTO accepts any, LINEAR accepts any linear family);
            // already-target layouts report SKIPPED.
            // F1: explicit jobs with a non-Pumpkin target must not silently convert
            // Pumpkin sources via --from auto (detect). Require an explicit
            // --from pumpkin (or --from linear family) so no silent cross-format
            // rewrite happens; SKIPPED here names that remedy, never queues.
            List<Path> matching = new ArrayList<>();
            for (Path p : group) {
                LinearRegionConverter.ConvertFormat actual = actualByFile.get(p);
                if (actual == null) {
                    continue;
                }
                if (actual == to) {
                    Path t = targetForSourceExplicit(p, to);
                    LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(p, t,
                        LinearRegionConverter.Status.SKIPPED,
                        "already " + to + "; no conversion"));
                    continue;
                }
                if (actual == LinearRegionConverter.ConvertFormat.PUMPKIN && to != LinearRegionConverter.ConvertFormat.PUMPKIN
                        && from == LinearRegionConverter.ConvertFormat.AUTO) {
                    Path t = targetForSourceExplicit(p, to);
                    LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(p, t,
                        LinearRegionConverter.Status.SKIPPED,
                        "Pumpkin layout, explicit conversion needs --from pumpkin"
                            + " (or --from linear) for Pumpkin sources;"
                            + " use /linear convert --from pumpkin --to " + to.name().toLowerCase()
                            + " explicit migration"));
                    LOGGER.info("Linear explicit conversion: Pumpkin source skipped"
                        + " (needs explicit --from pumpkin): " + p.getFileName()
                        + "; use /linear convert --from pumpkin --to " + to.name().toLowerCase()
                        + " explicit migration");
                    continue;
                }
                if (!LinearRegionConverter.matchesFrom(actual, from)) {
                    Path t = targetForSourceExplicit(p, to);
                    LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(p, t,
                        LinearRegionConverter.Status.SKIPPED,
                        "source is " + actual + ", job requires --from " + from));
                    continue;
                }
                matching.add(p);
            }
            if (matching.isEmpty()) {
                continue;
            }
            if (matching.size() > 1) {
                // Ambiguous same-coordinates sources mapping onto one target path
                // (e.g. r.0.0.mca beside r.0.0.b_linear toward Pumpkin .linear):
                // escalate with everything kept (extends the legacy ambiguous rule).
                matching.sort(Comparator.comparing(p -> p.getFileName().toString()));
                Path firstTarget = targetForSourceExplicit(matching.get(0), to);
                // Same-target check: only escalate when they actually collide on one target.
                // Different-extension sources toward different-extension targets cannot collide
                // here (all share one explicit to), so any >1 matching group collides.
                preFailures.add(new LinearRegionConverter.FileResult(matching.get(0), firstTarget,
                    LinearRegionConverter.Status.FAILED,
                    "ambiguous same-coordinates explicit sources ("
                        + matching.stream().map(p -> p.getFileName().toString())
                            .reduce((a, b) -> a + ", " + b).orElse("?")
                        + "); all files left untouched: keep one, move the rest aside, re-run"));
                LOGGER.severe("Linear explicit conversion: ambiguous same-coordinates sources left untouched: "
                    + matching);
                continue;
            }
            Path source = matching.get(0);
            Path target = targetForSourceExplicit(source, to);
            boolean samePath = source.equals(target);
            if (samePath) {
                // Same-file .linear -> .linear (V1<->Pumpkin, V1->V3, ...): allowed via
                // tmp + validate-before-swap + atomic-swap (never in-place overwrite).
                // Skip-gate parity with the legacy path (never queue a live file blindly).
                boolean skip;
                try {
                    skip = LinearRegionConverter.FORWARD_SKIP.test(source);
                } catch (RuntimeException predicateFailed) {
                    LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(source, target,
                        LinearRegionConverter.Status.SKIPPED,
                        "skip check failed, deferred: " + String.valueOf(predicateFailed.getMessage())));
                    continue;
                }
                if (skip) {
                    LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(source, target,
                        LinearRegionConverter.Status.SKIPPED,
                        "loaded or recently written, re-run to finish"));
                    continue;
                }
                out.add(new ExplicitCandidate(source, target, true));
                continue;
            }
            // Different paths: shadow handling with layout awareness (extends the legacy rule).
            Path shadow = null;
            for (Path p : group) {
                if (!p.equals(source) && p.equals(target)) {
                    shadow = p;
                    break;
                }
            }
            // Also consider any same-coords file already at the target path (target exists).
            if (shadow == null && Files.isRegularFile(target)) {
                shadow = target;
            }
            if (shadow == null) {
                boolean skip;
                try {
                    skip = LinearRegionConverter.FORWARD_SKIP.test(source);
                } catch (RuntimeException predicateFailed) {
                    LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(source, target,
                        LinearRegionConverter.Status.SKIPPED,
                        "skip check failed, deferred: " + String.valueOf(predicateFailed.getMessage())));
                    continue;
                }
                if (skip) {
                    LinearRegionConverter.safeOnFile(events, new LinearRegionConverter.FileResult(source, target,
                        LinearRegionConverter.Status.SKIPPED,
                        "loaded or recently written, re-run to finish"));
                    continue;
                }
                out.add(new ExplicitCandidate(source, target, false));
                continue;
            }
            // A target-path file exists: cross-extension shadow (same target path per
            // FR7, different source extension) escalates. Same-path .linear converts
            // are one inode (swap path above), never two files sharing a name.
            LinearRegionConverter.ConvertFormat shadowLayout = null;
            String shadowDetectError = null;
            try {
                String sname = shadow.getFileName().toString();
                if (sname.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                    shadowLayout = LinearRegionConverter.actualFormatOf(shadow);
                } else if (sname.endsWith(RegionFileFormat.ANVIL_EXTENSION)) {
                    shadowLayout = LinearRegionConverter.ConvertFormat.MCA;
                } else if (sname.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                    shadowLayout = LinearRegionConverter.ConvertFormat.B_LINEAR;
                }
            } catch (IOException corrupt) {
                shadowDetectError = corrupt.getMessage();
            }
            if (shadowDetectError != null) {
                // Corrupt shadow beside a readable source: never delete a possibly-Pumpkin
                // shadow blindly. If the source itself is also unreadable, escalate with both
                // kept; else the shadow is an orphan only when it is provably non-Pumpkin?
                // Fail-closed: a corrupt .linear shadow could be a torn Pumpkin write —
                // never auto-delete .linear shadows here; escalate for operator comparison.
                // (.mca/.b_linear corrupt shadows keep the legacy orphan path below.)
                String sname = shadow.getFileName().toString();
                if (sname.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                    preFailures.add(new LinearRegionConverter.FileResult(source, target,
                        LinearRegionConverter.Status.FAILED,
                        "explicit shadow pair: target-path shadow corrupt (" + shadowDetectError
                            + "); both files left untouched for operator comparison"));
                    LOGGER.severe("Linear explicit conversion: corrupt shadow left untouched: source="
                        + source + " shadow=" + shadow);
                    continue;
                }
            }
            if (shadowLayout != null && shadowLayout != to) {
                // Cross-extension shadow (e.g. V1 file at the desired Pumpkin target
                // path beside an MCA source): escalate FAILED, keep both, never
                // delete, never silently overwrite. Same-path .linear converts are
                // one inode (swap path), never two same-name files.
                preFailures.add(new LinearRegionConverter.FileResult(source, target,
                    LinearRegionConverter.Status.FAILED,
                    "explicit shadow pair: valid " + shadowLayout + " shadow where " + to
                        + " is desired; target path already holds a different layout (FR7);"
                        + " both files left untouched for operator comparison"));
                LOGGER.severe("Linear explicit conversion: cross-extension shadow left untouched: source="
                    + source + " shadow=" + shadow + " (shadow=" + shadowLayout + " desired=" + to + ")");
                continue;
            }
            // Same layout at the target path: legacy valid-shadow vs orphan logic.
            // Pumpkin shadows are NEVER auto-deleted (no silent cross-format rewrite).
            boolean targetValid;
            try {
                if (to == LinearRegionConverter.ConvertFormat.PUMPKIN) {
                    LinearRegionConverter.validateExplicitPair(source, shadow, from, to);
                } else {
                    LinearRegionConverter.validatePair(source, shadow);
                }
                targetValid = true;
            } catch (Exception invalid) {
                targetValid = false;
            }
            if (targetValid) {
                preFailures.add(new LinearRegionConverter.FileResult(source, target,
                    LinearRegionConverter.Status.FAILED,
                    "explicit shadow pair: valid " + to + " shadow beside readable source;"
                        + " one-way conversion may leave unique chunks on either side;"
                        + " both files left untouched for operator comparison"));
                LOGGER.severe("Linear explicit conversion: valid shadow pair left untouched: source="
                    + source + " shadow=" + shadow);
                continue;
            }
            String sname2 = shadow.getFileName().toString();
            if (sname2.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                // Fail-closed for .linear shadows: a count-invalid .linear shadow could still
                // hold unique chunks (one-way caveat) or be a torn Pumpkin write; the legacy
                // orphan-delete would silently destroy it. Escalate for operator comparison.
                preFailures.add(new LinearRegionConverter.FileResult(source, target,
                    LinearRegionConverter.Status.FAILED,
                    "explicit shadow pair: target-path .linear shadow invalid but source readable;"
                        + " both files left untouched for operator comparison (never auto-delete .linear shadows)"));
                LOGGER.severe("Linear explicit conversion: invalid .linear shadow left untouched: source="
                    + source + " shadow=" + shadow);
                continue;
            }
            boolean sourceReadable;
            try {
                try (AbstractRegionFile src = LinearRegionConverter.openProbed(source, source.getParent(),
                    RegionFileFormat.DEFAULT_COMPRESSION_LEVEL)) {
                    RegionName c = parseRegionName(source.getFileName().toString());
                    LinearRegionConverter.countChunks(src, c.x(), c.z());
                }
                sourceReadable = true;
            } catch (Exception corrupt) {
                sourceReadable = false;
            }
            if (!sourceReadable) {
                preFailures.add(new LinearRegionConverter.FileResult(source, target,
                    LinearRegionConverter.Status.FAILED,
                    "explicit shadow pair: source unreadable; shadow left untouched for inspection"));
                LOGGER.severe("Linear explicit conversion: shadow pair with corrupt source left untouched: "
                    + source);
                continue;
            }
            try {
                Files.deleteIfExists(shadow);
                if (shadow.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                    LinearRegionConverter.deleteBufferedSidecars(shadow);
                } else {
                    Files.deleteIfExists(shadow.resolveSibling(shadow.getFileName() + ".swp"));
                }
                LOGGER.info("Linear explicit conversion: removed invalid shadow " + shadow.getFileName()
                    + "; requeueing " + source.getFileName());
            } catch (IOException deleteFail) {
                preFailures.add(new LinearRegionConverter.FileResult(source, target,
                    LinearRegionConverter.Status.FAILED,
                    "explicit shadow pair: cannot remove invalid orphan target " + shadow + ": "
                        + deleteFail.getMessage()));
                continue;
            }
            out.add(new ExplicitCandidate(source, target, false));
        }
        return out;
    }
}
