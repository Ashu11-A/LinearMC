package net.linear.versions;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.LinearImplementation;

/**
 * DivineMC-style per-version region codec.
 *
 * <p>Method names mirror DivineMC's {@code Version}: {@code implementation()}
 * selects the on-disk layout, {@code version()} is its wire byte,
 * {@code parse} inflates a file image into slot tables, {@code flush}
 * renders slot tables back into a file image.</p>
 *
 * <h2>Adaptation to this codebase</h2>
 * <ul>
 *   <li>Stateless codecs: implementations hold no per-file state and are
 *       shared across threads (see {@code LinearBase.getShared}); all
 *       instances are thread-safe.</li>
 *   <li>IO, locks and coordinator reporting stay in
 *       {@code LinearRegionFile}. {@code parse} and {@code flush} are pure
 *       buffer transforms: they never touch files, locks, threads, config
 *       suppliers or metrics, and they carry no file path, so their error
 *       texts name slots/buckets without a file suffix — the caller
 *       re-attaches file context when rethrowing.</li>
 *   <li>{@code parse} takes the whole file image (position 0) and validates
 *       its own magic/version/footer; it reads via duplicates and never
 *       mutates the caller's buffer position.</li>
 *   <li>{@code flush} returns the whole file image (position 0, ready to
 *       write); the caller owns tmp-file + fsync + atomic move.</li>
 * </ul>
 */
public interface Version {

    /** On-disk implementation this codec reads and writes. */
    LinearImplementation implementation();

    /** Wire version byte written by this codec. */
    default byte version() {
        return implementation().version();
    }

    /**
     * Inflates {@code fileImage} (whole file, position 0) into {@code out}.
     *
     * @param fileImage entire file bytes, positioned at 0; not mutated
     * @param out slot tables to fill
     */
    void parse(ByteBuffer fileImage, ChunkSlotTable out) throws IOException;

    /**
     * Renders {@code slots} into a whole file image (position 0).
     *
     * @param slots snapshot tables to read (not mutated)
     * @param params write inputs (level, grid, coords, timestamps, zstd tuning)
     * @return complete file bytes to persist verbatim
     */
    ByteBuffer flush(ChunkSlotView slots, VersionWriteParams params) throws IOException;
}
