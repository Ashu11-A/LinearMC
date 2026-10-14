package net.linear.convert.cleanup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.linear.convert.scan.CandidateScanner;
import net.linear.LinearRegionConverter;
import net.linear.RegionFileFormat;

/**
 * Additive convert shim: best-effort staging/sidecar cleanup.
 *
 * <p>Facade-adoption note: {@code LinearRegionConverter} still owns attempt
 * cleanup. When wiring delegates, the facade adopts these methods wholesale
 * (logic copied byte-identically from the facade's static implementation).</p>
 */
public final class ConversionCleanup {

    private ConversionCleanup() {
    }

    /**
     * Deletes leftover staging plus the partial final target between attempts.
     * Best-effort: a torn final target must never linger where a later crash
     * could mistake it for a finished conversion. The source is never touched.
     */
    public static void cleanAttemptArtifacts(Path source, Path target) {
        try {
            Path staging =
                CandidateScanner.resolveRecreateDirectory(source.getParent()).resolve(target.getFileName().toString());
            Files.deleteIfExists(staging);
            Files.deleteIfExists(staging.resolveSibling(staging.getFileName() + ".swp"));
            Files.deleteIfExists(staging.resolveSibling(staging.getFileName() + ".tmp"));
            Files.deleteIfExists(staging.resolveSibling(staging.getFileName() + ".swp.tmp"));
            Files.deleteIfExists(target);
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".swp"));
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".tmp"));
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".swp.tmp"));
        } catch (Exception ignored) {
        }
    }

    /**
     * WAL-at-rest prune for finished B_LINEAR targets: validation reopened
     * the target (recreating its swap sidecar), but the master holds the
     * full snapshot, so the sidecar is scratch. Best-effort; the next open
     * rebuilds swap state from the master. Never called on live sources.
     */
    public static void pruneFinishedBufferedTarget(Path target) {
        try {
            if (target.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                deleteBufferedSidecars(target);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Deletes every scratch sibling of a B_LINEAR master: the swap sidecar
     * plus both staging suffixes. Best-effort; used for dead sources (never
     * opened again) and finished targets (master-only ship).
     */
    public static void deleteBufferedSidecars(Path master) {
        try {
            Files.deleteIfExists(master.resolveSibling(master.getFileName() + ".swp"));
            Files.deleteIfExists(master.resolveSibling(master.getFileName() + ".tmp"));
            Files.deleteIfExists(master.resolveSibling(master.getFileName() + ".swp.tmp"));
        } catch (Exception ignored) {
        }
    }

    /**
     * Crash-resume step (a): delete orphan {@code new_<folder>} content left by a
     * crashed run, plus stale {@code *.pumpkin-tmp} beside the source left by a
     * crashed same-path Pumpkin swap. Crash-before-move is safe to retry (source
     * intact, fragment ignored); without this cleanup a stale staged file could
     * be mistaken for fresh work on the next attempt. {@code *.pumpkin-tmp}
     * files never match the region pattern so collection stays blind to them;
     * the preamble still wipes them here.
     *
     * @return number of deleted entries
     */
    public static int cleanRecreateDirectory(Path regionFolder) {
        int removed = 0;
        // Stale same-path Pumpkin tmps beside the source (invisible to REGION_NAME).
        // Region-scoped like the staging sweep below so unrelated files kept.
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder, "r.*.pumpkin-tmp")) {
            for (Path p : ds) {
                try {
                    if (Files.isRegularFile(p) && Files.deleteIfExists(p)) {
                        removed++;
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
            // Missing/unreadable folder: staging cleanup below still runs (or no-ops).
        }
        // Stale flush/compact staging beside live region files (killed
        // mid-flush/compact). Runs under the startup quiesce gate, so no live
        // flush can be authoring these; a forced-but-unmoved tmp is garbage
        // by definition (the master is old truth until the move lands).
        // Globs are region-scoped (r.*) so unrelated operator tmps are kept.
        for (String glob : new String[]{"r.*.tmp", "r.*.swp.tmp"}) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder, glob)) {
                for (Path p : ds) {
                    try {
                        if (Files.isRegularFile(p) && Files.deleteIfExists(p)) {
                            removed++;
                        }
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
        Path staging = CandidateScanner.resolveRecreateDirectory(regionFolder);
        if (!Files.isDirectory(staging)) {
            return removed;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(staging)) {
            List<Path> entries = new ArrayList<>();
            for (Path p : ds) {
                entries.add(p);
            }
            for (Path p : entries) {
                try {
                    if (Files.isDirectory(p)) {
                        try (java.util.stream.Stream<Path> walk = Files.walk(p)) {
                            List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
                            for (Path q : all) {
                                Files.deleteIfExists(q);
                                removed++;
                            }
                        }
                    } else {
                        Files.deleteIfExists(p);
                        removed++;
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return removed;
    }
}
