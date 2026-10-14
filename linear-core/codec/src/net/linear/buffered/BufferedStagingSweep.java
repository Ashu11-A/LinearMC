package net.linear.buffered;

import java.nio.file.Path;

/**
 * Staging-sweep helper for buffered-linear files.
 *
 * <p>Deletes the crash orphans beside {@code master}: the forced-but-unmoved
 * master {@code .tmp} and the compact-staging {@code .swp.tmp}. The master is
 * old truth until the move lands and the swap table decides, so neither tmp
 * may survive to confuse a later open. Best-effort; never throws.</p>
 */
public final class BufferedStagingSweep {

    private BufferedStagingSweep() {
    }

    /** Deletes {@code master.tmp} and {@code master.swp.tmp} siblings. */
    public static void sweepTmpSiblings(Path master) {
        if (master == null) {
            return;
        }
        BufferedSwapFile.deleteQuietly(master.resolveSibling(master.getFileName() + ".tmp"));
        Path swap = master.resolveSibling(master.getFileName() + ".swp");
        BufferedSwapFile.deleteQuietly(swap.resolveSibling(swap.getFileName() + ".tmp"));
    }

    /** Deletes the swap sidecar plus staging tmps; safe only when clean. */
    public static void pruneSwapSidecar(Path master, Path swap) {
        BufferedSwapFile.deleteQuietly(swap);
        sweepTmpSiblings(master);
    }
}
