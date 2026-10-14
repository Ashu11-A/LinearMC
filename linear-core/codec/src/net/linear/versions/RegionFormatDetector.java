package net.linear.versions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.linear.probe.LinearBucketGrid;
import net.linear.compression.LinearXxHash64;
import net.linear.versions.base.V2LinearBase;

/**
 * Self-aware format detection for {@code .linear} files (requirements section 4).
 *
 * <p>Every {@code .linear} file is identified by try-parse with <b>full structural
 * validation</b> per candidate — never by header sniffing (byte 17 alone cannot
 * discriminate: a V1 compression level 1..22 overlaps the Pumpkin/V2 grid
 * whitelist 1/2/4/8/16/32). No payload bytes are decompressed and none are
 * reinterpreted across layouts; only xxhash64 over the still-compressed bucket
 * bytes is computed.</p>
 *
 * <ul>
 *   <li>V1: magic + wire 1/2 + 32 B header sane + {@code chunkCount <= 1024} +
 *       {@code fileLength == 32 + dataCount + 8} + footer magic.</li>
 *   <li>V2 (wire 3): magic + grid whitelist + features terminate + every table
 *       entry in-bounds + every bucket xxhash verifies + footer.</li>
 *   <li>V3 (wire 4): magic + grid whitelist + table bounds + bucket hash chain
 *       verifies + footer.</li>
 *   <li>PUMPKIN (wire 2): magic + grid whitelist + header {@code regionX/Z}
 *       equal the {@code r.X.Z} filename coordinates + features terminate +
 *       per-bucket xxhash verifies + footer. A wire-2 file satisfying both
 *       the V1 length equation and this structure validates as both V1 and
 *       PUMPKIN and therefore errors as ambiguous.</li>
 * </ul>
 *
 * <p>Exactly one candidate must validate. Zero yields a corrupt/unknown-file
 * error; two or more yields an ambiguous-layout error naming the candidates.
 * Neither case falls through into another format's reader. An absent bucket
 * ({@code size 0}) validates only with a zero stored hash, matching what all
 * three bucketed writers emit.</p>
 *
 * <p>Stateless shared singleton, thread-safe: detection works on duplicates
 * and never mutates the caller's buffer.</p>
 */
public final class RegionFormatDetector {

    /** Formats a {@code .linear} file image can structurally be. */
    public enum DetectedFormat {
        V1,
        V2,
        V3,
        PUMPKIN
    }

    private static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;
    private static final int V1_HEADER_SIZE = 32;
    private static final int FOOTER_SIZE = 8;

    private static final RegionFormatDetector SHARED = new RegionFormatDetector();

    private RegionFormatDetector() {
    }

    /** Shared stateless instance. */
    public static RegionFormatDetector shared() {
        return SHARED;
    }

    /**
     * Identifies {@code fileImage} (whole file, position 0; not mutated).
     *
     * @param fileImage entire file bytes, positioned at 0
     * @param fileRegionX region X parsed from the {@code r.X.Z} file name
     * @param fileRegionZ region Z parsed from the {@code r.X.Z} file name
     * @return the single validating format
     * @throws IOException when no candidate validates (corrupt/unknown) or
     *         more than one does (ambiguous layout, candidates named)
     */
    public DetectedFormat detect(ByteBuffer fileImage, int fileRegionX, int fileRegionZ)
            throws IOException {
        List<DetectedFormat> validated = new ArrayList<>(4);
        String rV1 = v1Reason(fileImage);
        if (rV1 == null) {
            validated.add(DetectedFormat.V1);
        }
        String rV2 = bucketedReason(fileImage, 3, false, fileRegionX, fileRegionZ);
        if (rV2 == null) {
            validated.add(DetectedFormat.V2);
        }
        String rV3 = bucketedReason(fileImage, 4, false, fileRegionX, fileRegionZ);
        if (rV3 == null) {
            validated.add(DetectedFormat.V3);
        }
        String rP = pumpkinReason(fileImage, fileRegionX, fileRegionZ);
        if (rP == null) {
            validated.add(DetectedFormat.PUMPKIN);
        }
        if (validated.isEmpty()) {
            throw new IOException("Unrecognized region file (corrupt/unknown file): V1: " + rV1
                + "; V2: " + rV2 + "; V3: " + rV3 + "; PUMPKIN: " + rP);
        }
        if (validated.size() > 1) {
            throw new IOException("Ambiguous linear region layout: validates as " + validated);
        }
        return validated.get(0);
    }

    /**
     * Returns the whitelisted bucket grid encoded in {@code fileImage}.
     *
     * @param fileImage entire file bytes, positioned at 0; not mutated
     * @param format result of {@link #detect} for the same image
     * @return grid size (1/2/4/8/16/32); 0 for V1, which has no grid
     * @throws IOException when the header grid is off-whitelist (unreachable
     *         after a successful {@code detect} of a bucketed format)
     */
    public int gridOf(ByteBuffer fileImage, DetectedFormat format) throws IOException {
        ByteBuffer raw = fileImage.duplicate();
        require(raw, 8 + 1, "header");
        raw.getLong(); // magic (validated by detect; gridOf re-checks pairing only)
        int wire = raw.get() & 0xFF;
        switch (format) {
            case V1:
                if (wire != 1 && wire != 2) {
                    throw new IOException("Grid requested for non-V1 wire " + wire);
                }
                return 0;
            case V2:
                if (wire != 3) {
                    throw new IOException("Grid requested for non-V2 wire " + wire);
                }
                break;
            case V3:
                if (wire != 4) {
                    throw new IOException("Grid requested for non-V3 wire " + wire);
                }
                break;
            case PUMPKIN:
                if (wire != 2) {
                    throw new IOException("Grid requested for non-PUMPKIN wire " + wire);
                }
                break;
            default:
                throw new IOException("Grid requested for unknown format " + format);
        }
        if (format == DetectedFormat.V3) {
            require(raw, 1, "V3 grid");
            return checkedGrid(raw.get() & 0xFF);
        }
        // V2 and PUMPKIN share the superblock shape: grid sits after the u64 timestamp.
        require(raw, 8 + 1, "superblock tail");
        raw.getLong();
        return checkedGrid(raw.get() & 0xFF);
    }

    private static int checkedGrid(int grid) throws IOException {
        if (!LinearBucketGrid.isValid(grid)) {
            throw new IOException("Invalid grid size: " + grid);
        }
        return grid;
    }

    private static String v1Reason(ByteBuffer fileImage) {
        try {
            ByteBuffer raw = fileImage.duplicate();
            long fileLength = raw.remaining();
            try {
                require(raw, V1_HEADER_SIZE, "V1 header");
            } catch (IOException e) {
                return "truncated header";
            }
            if (raw.getLong() != SUPERBLOCK) {
                return "bad magic";
            }
            int wire = raw.get() & 0xFF;
            if (wire != 1 && wire != 2) {
                return "bad wire " + wire;
            }
            raw.getLong(); // newestTimestamp (informational)
            raw.get(); // compressionLevel (informational)
            int chunkCount = raw.getShort() & 0xFFFF;
            if (chunkCount > 1024) {
                return "chunkCount " + chunkCount + " >1024";
            }
            long dataCount = raw.getInt() & 0xFFFFFFFFL;
            raw.getLong(); // reserved
            if (fileLength != (long) V1_HEADER_SIZE + dataCount + FOOTER_SIZE) {
                return "length mismatch";
            }
            try {
                require(raw, dataCount + FOOTER_SIZE, "V1 body+footer");
            } catch (IOException e) {
                return "truncated body+footer";
            }
            raw.position(raw.position() + (int) dataCount);
            if (raw.getLong() != SUPERBLOCK) {
                return "bad footer";
            }
            if (raw.hasRemaining()) {
                return "trailing bytes after footer";
            }
            return null;
        } catch (RuntimeException e) {
            return "truncated";
        }
    }

    private static String pumpkinReason(ByteBuffer fileImage, int fileRegionX, int fileRegionZ) {
        try {
            ByteBuffer raw = fileImage.duplicate();
            try {
                require(raw, 8 + 1, "superblock");
            } catch (IOException e) {
                return "truncated superblock";
            }
            if (raw.getLong() != SUPERBLOCK) {
                return "bad magic";
            }
            if ((raw.get() & 0xFF) != 2) {
                return "bad wire";
            }
            // NOTE: no V1-exclusion here on purpose. A wire-2 file satisfying
            // both the V1 length equation and the Pumpkin structure is
            // genuinely ambiguous and must error naming both candidates
            // (never guess); genuine files never overlap because every
            // bucket xxhash would have to verify under both layouts.
            return validateBucketedBodyReason(raw, 2, true, fileRegionX, fileRegionZ);
        } catch (RuntimeException e) {
            return "truncated";
        }
    }

    private static String bucketedReason(ByteBuffer fileImage, int wire,
            boolean checkCoords, int fileRegionX, int fileRegionZ) {
        try {
            ByteBuffer raw = fileImage.duplicate();
            try {
                require(raw, 8 + 1, "superblock");
            } catch (IOException e) {
                return "truncated superblock";
            }
            if (raw.getLong() != SUPERBLOCK) {
                return "bad magic";
            }
            if ((raw.get() & 0xFF) != wire) {
                return "bad wire";
            }
            return validateBucketedBodyReason(raw, wire, checkCoords, fileRegionX, fileRegionZ);
        } catch (RuntimeException e) {
            return "truncated";
        }
    }

    /**
     * Validates everything after the version byte without decompressing any
     * bucket: header shape, grid whitelist, (Pumpkin) filename coords,
     * features termination, table bounds, per-bucket xxhash over the
     * compressed bytes, footer magic, exact file length.
     *
     * @return null when valid, otherwise a short failure reason.
     */
    private static String validateBucketedBodyReason(ByteBuffer raw, int wire,
            boolean checkCoords, int fileRegionX, int fileRegionZ) {
        try {
            int grid;
            if (wire == 4) {
                try {
                    require(raw, 1, "V3 grid");
                } catch (IOException e) {
                    return "truncated V3 grid";
                }
                grid = raw.get() & 0xFF;
                if (!LinearBucketGrid.isValid(grid)) {
                    return "bad grid " + grid;
                }
            } else {
                try {
                    require(raw, 8 + 1 + 4 + 4 + 128 + 1, "bucketed header");
                } catch (IOException e) {
                    return "truncated header";
                }
                raw.getLong(); // file/newest timestamp (informational)
                grid = raw.get() & 0xFF;
                if (!LinearBucketGrid.isValid(grid)) {
                    return "bad grid " + grid;
                }
                int regionX = raw.getInt();
                int regionZ = raw.getInt();
                if (checkCoords && (regionX != fileRegionX || regionZ != fileRegionZ)) {
                    return "coords mismatch";
                }
                raw.position(raw.position() + 128); // existence bitmap (informational)
                try {
                    V2LinearBase.skipFeatures(raw);
                } catch (IOException e) {
                    return e.getMessage();
                }
            }
            boolean hasLevel = wire != 4;
            int entrySize = hasLevel ? 13 : 12;
            long bucketCount = (long) grid * grid;
            long tableBytes = bucketCount * entrySize;
            if (raw.remaining() < tableBytes) {
                return "truncated bucket table";
            }
            long[] sizes = new long[(int) bucketCount];
            long[] hashes = new long[(int) bucketCount];
            for (int i = 0; i < (int) bucketCount; i++) {
                sizes[i] = raw.getInt() & 0xFFFFFFFFL;
                if (hasLevel) {
                    raw.get(); // per-bucket level byte (written, never negotiated)
                }
                hashes[i] = raw.getLong();
                if (sizes[i] > raw.remaining()) {
                    return "bucket " + i + " out of bounds";
                }
            }
            long bodyBytes = 0L;
            for (long size : sizes) {
                bodyBytes += size;
                if (bodyBytes > raw.remaining()) {
                    return "bucket payload too large";
                }
            }
            if (raw.remaining() != bodyBytes + FOOTER_SIZE) {
                return "length mismatch";
            }
            for (int i = 0; i < (int) bucketCount; i++) {
                if (sizes[i] == 0) {
                    // All three bucketed writers store hash 0 for absent buckets.
                    if (hashes[i] != 0L) {
                        return "hash incorrect in bucket " + i;
                    }
                    continue;
                }
                int len = (int) sizes[i];
                ByteBuffer slice = raw.duplicate();
                slice.limit(slice.position() + len);
                if (LinearXxHash64.hashBuffer(slice) != hashes[i]) {
                    return "hash incorrect in bucket " + i;
                }
                raw.position(raw.position() + len);
            }
            try {
                require(raw, FOOTER_SIZE, "footer");
            } catch (IOException e) {
                return "truncated footer";
            }
            if (raw.getLong() != SUPERBLOCK) {
                return "bad footer";
            }
            if (raw.hasRemaining()) {
                return "trailing bytes after footer";
            }
            return null;
        } catch (RuntimeException e) {
            return "truncated";
        }
    }

    private static void require(ByteBuffer buf, long bytes, String what) throws IOException {
        if (bytes < 0 || buf.remaining() < bytes) {
            throw new IOException("Truncated " + what);
        }
    }
}
