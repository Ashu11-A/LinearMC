package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Conversion pipeline tests.
 *
 * <p>Two tiers: pure-logic tests (state machine, retry counter, parity rule,
 * name parsing, staging cleanup) run WITHOUT NMS bootstrap; NMS tests
 * (validation parity, shadow policy, retry-3-then-throw) use TempDir +
 * bootstrap, mirroring {@code LinearMeasurementCountersTest} style.</p>
 */
public class LinearConversionPipelineTest {

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // Pure logic: no bootstrap needed.

    @Test
    public void stateMachineTransitions() {
        assertEquals(LinearRegionConverter.FileState.CONVERTED,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.CONVERTING, true));
        assertEquals(LinearRegionConverter.FileState.VALIDATING,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.CONVERTED, true));
        assertEquals(LinearRegionConverter.FileState.VALIDATED,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.VALIDATING, true));
        assertEquals(LinearRegionConverter.FileState.DELETING,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.VALIDATED, true));
        assertEquals(LinearRegionConverter.FileState.DELETED,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.DELETING, true));
        assertEquals(LinearRegionConverter.FileState.FAILED,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.CONVERTING, false));
        assertEquals(LinearRegionConverter.FileState.FAILED,
            LinearRegionConverter.transition(LinearRegionConverter.FileState.VALIDATING, false));
    }

    @Test
    public void retryCounterAllowsThreeAttempts() {
        assertTrue(LinearRegionConverter.shouldRetry(0));
        assertTrue(LinearRegionConverter.shouldRetry(1));
        assertTrue(LinearRegionConverter.shouldRetry(2));
        assertFalse(LinearRegionConverter.shouldRetry(3));
        assertFalse(LinearRegionConverter.shouldRetry(4));
        assertEquals(3, LinearRegionConverter.MAX_ATTEMPTS);
    }

    @Test
    public void parityRuleEmptyIsValid() {
        assertTrue(LinearRegionConverter.countsMatch(0, 0), "0-chunk files convert trivially (VALID)");
        assertTrue(LinearRegionConverter.countsMatch(5, 5));
        assertFalse(LinearRegionConverter.countsMatch(5, 4));
        assertFalse(LinearRegionConverter.countsMatch(0, 1));
    }

    @Test
    public void nameParsingAndTargets() {
        assertNotNull(LinearRegionConverter.parseRegionName("r.0.0.mca"));
        assertNotNull(LinearRegionConverter.parseRegionName("r.-1.2.linear"));
        assertNull(LinearRegionConverter.parseRegionName("r.0.0.mcc"));
        assertNull(LinearRegionConverter.parseRegionName("level.dat"));
        Path src = Path.of("region", "r.1.2.mca");
        assertEquals("r.1.2.linear", LinearRegionConverter.targetForSource(src).getFileName().toString());
        Path folder = Path.of("world", "region");
        assertEquals("new_region",
            LinearRegionConverter.resolveRecreateDirectory(folder).getFileName().toString());
    }

    @Test
    public void orphanNewCleanupDeletesFragments() throws Exception {
        Path region = this.tempDir.resolve("region-clean");
        Files.createDirectories(region);
        Path staging = LinearRegionConverter.resolveRecreateDirectory(region);
        Files.createDirectories(staging);
        Files.write(staging.resolve("r.0.0.linear"), new byte[]{1, 2, 3});
        Files.write(staging.resolve("r.0.1.linear"), new byte[]{4, 5});
        int removed = LinearRegionConverter.cleanRecreateDirectory(region);
        assertEquals(2, removed);
        assertEquals(0, LinearRegionConverter.cleanRecreateDirectory(region), "second clean is empty");
    }

    /**
     * MED-1 pin: the copy-exact counter skips degenerate slots (hasChunk true
     * but null/empty payload) that the copy loop also skips, while the raw
     * hasChunk counter still sees them (the old mismatch source).
     */
    @Test
    public void copyExactCountSkipsDegenerateSlots() throws Exception {
        AbstractRegionFile fake = new AbstractRegionFile() {
            @Override
            public boolean hasChunk(long chunk) {
                return ChunkKey.z(chunk) == 0 && (ChunkKey.x(chunk) == 0 || ChunkKey.x(chunk) == 1 || ChunkKey.x(chunk) == 2);
            }

            @Override
            public DataInputStream getChunkDataInputStream(long chunk) {
                if (ChunkKey.x(chunk) == 1) {
                    return null;
                }
                byte[] payload = ChunkKey.x(chunk) == 2 ? new byte[0] : new byte[]{1, 2, 3};
                return new DataInputStream(new ByteArrayInputStream(payload));
            }

            @Override
            public void write(long chunk, ByteBuffer data) {
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isMarkedToSave() {
                return false;
            }

            @Override
            public void clearMarkedToSave() {
            }
        };
        assertEquals(3, LinearRegionConverter.countChunks(fake, 0, 0),
            "raw hasChunk counter sees all three slots (old mismatch source)");
        assertEquals(1, LinearRegionConverter.countCopyableChunks(fake, 0, 0),
            "copy-exact counter skips the null-stream and empty-payload slots");
    }

    // NMS tier: TempDir + bootstrap.

    private static void writeAnvilChunk(Path file, int regionX, int regionZ, int localX, int localZ) throws Exception {
        RegionStorageInfo info = new RegionStorageInfo("converter", Level.OVERWORLD, "chunk");
        Path folder = file.getParent();
        Files.createDirectories(folder);
        try (RegionFile rf = new RegionFile(info, file, folder, false)) {
            ChunkPos pos = new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ);
            byte[] payload = new byte[128];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0xFF);
            }
            // Anvil chunk framing: int32 length + u8 version + payload.
            // Version 3 ("none") passes the payload through uncompressed, so
            // real 26.1.x chunk I/O reads it back (raw pattern bytes are NOT
            // legal payloads: the first 4 bytes would parse as a ~64KB length
            // and fail with "stream is truncated").
            ByteBuffer framed = ByteBuffer.allocate(5 + payload.length);
            framed.putInt(payload.length + 1);
            framed.put((byte) 3);
            framed.put(payload);
            framed.flip();
            rf.write(pos, framed);
            rf.flush();
        }
    }

    private static void writeLinearChunk(Path file, int regionX, int regionZ, int localX, int localZ, int level)
        throws Exception {
        Files.createDirectories(file.getParent());
        try (LinearRegionFile lf = new LinearRegionFile(file, level)) {
            long pos = ChunkKey.of(regionX * 32 + localX, regionZ * 32 + localZ);
            byte[] payload = new byte[128];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0xFF);
            }
            lf.write(pos, ByteBuffer.wrap(payload));
            lf.flush();
        }
    }

    private static LinearRegionConverter.Listener quietListener(List<LinearRegionConverter.FileResult> seen,
        List<String> retries) {
        return new LinearRegionConverter.Listener() {
            @Override
            public void onFile(LinearRegionConverter.FileResult r) {
                synchronized (seen) {
                    seen.add(r);
                }
            }

            @Override
            public void onRetry(Path source, int attempt, String reason) {
                synchronized (retries) {
                    retries.add(source + "#" + attempt);
                }
            }
        };
    }

    @Test
    public void validationParityOnCraftedPairs() throws Exception {
        Path region = this.tempDir.resolve("region-parity");
        Files.createDirectories(region);
        Path src = region.resolve("r.0.0.mca");
        Path dst = region.resolve("r.0.0.linear");
        writeAnvilChunk(src, 0, 0, 0, 0);
        writeAnvilChunk(src, 0, 0, 1, 1);
        writeLinearChunk(dst, 0, 0, 0, 0, 3);
        writeLinearChunk(dst, 0, 0, 1, 1, 3);
        String detail = LinearRegionConverter.validatePair(src, dst);
        assertTrue(detail.contains("chunks=2"), "parity detail names count: " + detail);

        Path bad = region.resolve("r.0.0.bad.linear");
        writeLinearChunk(bad, 0, 0, 0, 0, 3);
        Path badTarget = region.resolve("r.9.9.linear");
        Files.move(bad, badTarget);
        // Mismatched counts must fail.
        Path src2 = region.resolve("r.9.9.mca");
        writeAnvilChunk(src2, 9, 9, 0, 0);
        writeAnvilChunk(src2, 9, 9, 1, 0);
        writeAnvilChunk(src2, 9, 9, 2, 0);
        assertThrows(Exception.class, () -> LinearRegionConverter.validatePair(src2, badTarget));
    }

    /**
     * HIGH-2: a torn middle (valid header/footer/size/count, corrupt chunk
     * frame) must fail validation even though every framing check passes.
     */
    @Test
    public void tornMiddleTargetFailsValidation() throws Exception {
        Path region = this.tempDir.resolve("region-torn");
        Files.createDirectories(region);
        Path src = region.resolve("r.2.2.mca");
        Path dst = region.resolve("r.2.2.linear");
        writeAnvilChunk(src, 2, 2, 0, 0);
        writeAnvilChunk(src, 2, 2, 1, 1);
        writeLinearChunk(dst, 2, 2, 0, 0, 3);
        writeLinearChunk(dst, 2, 2, 1, 1, 3);
        assertDoesNotThrow(() -> LinearRegionConverter.validatePair(src, dst), "pristine pair validates");

        byte[] bytes = Files.readAllBytes(dst);
        assertTrue(bytes.length > 64, "target big enough to tear the middle");
        int mid = bytes.length / 2;
        for (int i = 0; i < 32; i++) {
            bytes[mid + i] ^= (byte) 0xFF;
        }
        Files.write(dst, bytes);
        assertEquals(bytes.length, (int) Files.size(dst), "tear keeps size identical");
        assertThrows(Exception.class, () -> LinearRegionConverter.validatePair(src, dst),
            "torn middle must fail validation despite valid framing");
        assertTrue(Files.exists(src), "failed validation leaves the source alone");
    }

    /**
     * HIGH-3: a valid shadow is NEVER auto-deleted. Same-slot parity pair
     * escalates to protection with neither side deleted and no requeue.
     */
    @Test
    public void validShadowEscalatesToProtection() throws Exception {
        Path region = this.tempDir.resolve("region-shadow-valid");
        Files.createDirectories(region);
        Path src = region.resolve("r.0.0.mca");
        Path shadow = region.resolve("r.0.0.linear");
        writeAnvilChunk(src, 0, 0, 3, 3);
        writeLinearChunk(shadow, 0, 0, 3, 3, 3);
        List<LinearRegionConverter.FileResult> pre = new ArrayList<>();
        List<LinearRegionConverter.FileResult> seen = new ArrayList<>();
        List<String> retries = new ArrayList<>();
        List<Path> candidates =
            LinearRegionConverter.collectCandidates(region, quietListener(seen, retries), pre);
        assertEquals(1, pre.size(), "valid shadow escalates: " + pre);
        assertTrue(pre.get(0).detail().contains("operator comparison"), "caveat surfaced: " + pre.get(0).detail());
        assertTrue(Files.exists(shadow), "valid shadow is never auto-deleted");
        assertTrue(Files.exists(src), "source is never deleted on escalation");
        assertFalse(candidates.contains(src), "escalated source is not requeued");

        LinearRegionConverter.ConversionProtectionException thrown = assertThrows(
            LinearRegionConverter.ConversionProtectionException.class,
            () -> LinearRegionConverter.convertRegionFolder(region, 3, quietListener(seen, retries)));
        assertEquals(1, thrown.getSummary().failed());
        assertTrue(Files.exists(shadow), "end-to-end protection deletes neither side");
        assertTrue(Files.exists(src), "end-to-end protection deletes neither side");
    }

    /**
     * HIGH-3 downgrade divergence: unique chunks on each side at equal counts
     * still validate by parity, so the pair must escalate with neither side
     * deleted (parity cannot prove redundancy).
     */
    @Test
    public void divergentShadowSameCountEscalates() throws Exception {
        Path region = this.tempDir.resolve("region-shadow-divergent");
        Files.createDirectories(region);
        Path src = region.resolve("r.1.1.mca");
        Path shadow = region.resolve("r.1.1.linear");
        writeAnvilChunk(src, 1, 1, 0, 0);
        writeLinearChunk(shadow, 1, 1, 7, 7, 3);
        List<LinearRegionConverter.FileResult> pre = new ArrayList<>();
        List<LinearRegionConverter.FileResult> seen = new ArrayList<>();
        List<String> retries = new ArrayList<>();
        List<Path> candidates =
            LinearRegionConverter.collectCandidates(region, quietListener(seen, retries), pre);
        assertEquals(1, pre.size(), "divergent same-count pair escalates: " + pre);
        assertTrue(Files.exists(shadow), "divergent shadow is never auto-deleted");
        assertTrue(Files.exists(src), "divergent source is never deleted");
        assertFalse(candidates.contains(src), "escalated source is not requeued");
    }

    @Test
    public void invalidShadowDeletedAndRequeued() throws Exception {
        Path region = this.tempDir.resolve("region-shadow-invalid");
        Files.createDirectories(region);
        Path src = region.resolve("r.1.1.mca");
        Path shadow = region.resolve("r.1.1.linear");
        writeAnvilChunk(src, 1, 1, 0, 0);
        writeAnvilChunk(src, 1, 1, 1, 0);
        writeLinearChunk(shadow, 1, 1, 0, 0, 3);
        List<LinearRegionConverter.FileResult> pre = new ArrayList<>();
        List<LinearRegionConverter.FileResult> seen = new ArrayList<>();
        List<String> retries = new ArrayList<>();
        List<Path> candidates =
            LinearRegionConverter.collectCandidates(region, quietListener(seen, retries), pre);
        assertTrue(pre.isEmpty(), "invalid shadow resolves without escalation: " + pre);
        assertFalse(Files.exists(shadow), "orphan invalid .linear crash remnant is deleted");
        assertTrue(candidates.contains(src), "source requeued for conversion");
    }

    @Test
    public void retryThreeThenProtection() throws Exception {
        Path region = this.tempDir.resolve("region-fail");
        Files.createDirectories(region);
        Path bad = region.resolve("r.5.5.mca");
        writeAnvilChunk(bad, 5, 5, 0, 0);
        // Block the staging directory: a regular file at the new_<folder> path
        // makes Files.createDirectories(staging) fail on every attempt.
        // Conversion can never stage, so all 3 attempts fail and nothing is deleted.
        Path stagingBlock = region.resolveSibling("new_region-fail");
        Files.write(stagingBlock, new byte[]{0x13, 0x37});
        List<LinearRegionConverter.FileResult> seen = new ArrayList<>();
        List<String> retries = new ArrayList<>();
        LinearRegionConverter.ConversionProtectionException thrown = assertThrows(
            LinearRegionConverter.ConversionProtectionException.class,
            () -> LinearRegionConverter.convertRegionFolder(region, 3, quietListener(seen, retries)));
        assertEquals(1, thrown.getSummary().failed());
        assertEquals(1, thrown.getSummary().failures().size());
        assertTrue(Files.exists(bad), "sources never deleted before validation (guarantee)");
        assertEquals(2, retries.size(), "attempts 1+2 report onRetry; attempt 3 is terminal");
    }

    /**
     * C2-F2: a listener that throws on every callback must neither reclassify
     * the file nor let its exception escape the pipeline.
     */
    @Test
    public void throwingListenerCannotReclassify() throws Exception {
        Path region = this.tempDir.resolve("region-loud-listener");
        Files.createDirectories(region);
        Path src = region.resolve("r.6.6.mca");
        writeAnvilChunk(src, 6, 6, 0, 0);
        LinearRegionConverter.Listener loud = new LinearRegionConverter.Listener() {
            @Override
            public void onFile(LinearRegionConverter.FileResult r) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void onRetry(Path source, int attempt, String reason) {
                throw new IllegalStateException("boom");
            }
        };
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolder(region, 3, loud);
        assertEquals(1, summary.deleted(), "throwing listener changes nothing");
        assertFalse(Files.exists(src));
        assertTrue(Files.isRegularFile(region.resolve("r.6.6.linear")));
    }

    @Test
    public void emptySourceConvertsTrivially() throws Exception {
        Path region = this.tempDir.resolve("region-empty");
        Files.createDirectories(region);
        RegionStorageInfo info = new RegionStorageInfo("converter", Level.OVERWORLD, "chunk");
        Path src = region.resolve("r.7.7.mca");
        try (RegionFile rf = new RegionFile(info, src, region, false)) {
            rf.flush();
        }
        List<LinearRegionConverter.FileResult> seen = new ArrayList<>();
        List<String> retries = new ArrayList<>();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolder(region, 3, quietListener(seen, retries));
        assertEquals(1, summary.deleted(), "empty file deletes source after VALID");
        assertFalse(Files.exists(src));
        // HIGH-1: a 0-chunk source still materializes a valid empty target —
        // the source is never deleted with nothing preserved.
        Path dst = region.resolve("r.7.7.linear");
        assertTrue(Files.isRegularFile(dst), "empty source materializes an empty .linear target");
        assertTrue(Files.size(dst) > 40L, "empty target is a real file, not a stub");
        assertDoesNotThrow(() -> LinearRegionConverter.checkHeaderSanity(dst), "empty target header/footer valid");
    }

    /**
     * HIGH-1: an empty source with NO target must FAIL validation (never
     * "trivially valid"), so no path can delete a source without a target.
     */
    @Test
    public void emptySourceWithoutTargetFailsValidation() throws Exception {
        Path region = this.tempDir.resolve("region-empty-nontarget");
        Files.createDirectories(region);
        RegionStorageInfo info = new RegionStorageInfo("converter", Level.OVERWORLD, "chunk");
        Path src = region.resolve("r.3.3.mca");
        try (RegionFile rf = new RegionFile(info, src, region, false)) {
            rf.flush();
        }
        Path missing = region.resolve("r.3.3.linear");
        assertFalse(Files.exists(missing));
        Exception thrown =
            assertThrows(Exception.class, () -> LinearRegionConverter.validatePair(src, missing));
        assertTrue(thrown.getMessage().contains("Target missing"),
            "validation names the missing target: " + thrown.getMessage());
        assertTrue(Files.exists(src), "failed validation never deletes the source");
    }

    /**
     * HIGH-1b: a truncated source (below any valid .mca header) fails all 3
     * attempts into protection and is never deleted.
     */
    @Test
    public void truncatedSourceNeverDeletes() throws Exception {
        Path region = this.tempDir.resolve("region-trunc");
        Files.createDirectories(region);
        Path src = region.resolve("r.8.8.mca");
        Files.write(src, new byte[100]);
        List<LinearRegionConverter.FileResult> seen = new ArrayList<>();
        List<String> retries = new ArrayList<>();
        LinearRegionConverter.ConversionProtectionException thrown = assertThrows(
            LinearRegionConverter.ConversionProtectionException.class,
            () -> LinearRegionConverter.convertRegionFolder(region, 3, quietListener(seen, retries)));
        assertEquals(1, thrown.getSummary().failed());
        assertTrue(Files.exists(src), "truncated source never deleted");
        assertEquals(2, retries.size(), "attempts 1+2 report onRetry; attempt 3 is terminal");
    }

    /**
     * MED-1 integration: a source mixing a real chunk with a degenerate
     * empty-write slot converges instead of burning 3 retries into a halt.
     */
    @Test
    public void degenerateSourceConverges() throws Exception {
        Path region = this.tempDir.resolve("region-degenerate");
        Files.createDirectories(region);
        RegionStorageInfo info = new RegionStorageInfo("converter", Level.OVERWORLD, "chunk");
        Path src = region.resolve("r.4.4.mca");
        writeAnvilChunk(src, 4, 4, 0, 0);
        try (RegionFile rf = new RegionFile(info, src, region, false)) {
            ChunkPos degPos = new ChunkPos(4 * 32 + 1, 4 * 32 + 1);
            rf.write(degPos, ByteBuffer.wrap(new byte[0]));
            rf.flush();
            // Loud precondition: if vanilla ever stops storing empty writes as
            // present-but-empty slots, fail here (not deep in pipeline asserts).
            assertTrue(rf.hasChunk(degPos), "empty write stores a degenerate present-but-empty slot");
        }
        List<LinearRegionConverter.FileResult> seen = new ArrayList<>();
        List<String> retries = new ArrayList<>();
        LinearRegionConverter.ConversionSummary summary =
            LinearRegionConverter.convertRegionFolder(region, 3, quietListener(seen, retries));
        assertEquals(1, summary.deleted(), "degenerate slots converge (no doomed retries)");
        assertTrue(retries.isEmpty(), "no retry needed when counting matches copying: " + retries);
        assertFalse(Files.exists(src));
        assertTrue(Files.isRegularFile(region.resolve("r.4.4.linear")));
    }
}
