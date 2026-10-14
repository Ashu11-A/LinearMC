package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zstd workers + LDM + reader long-max.
 *
 * <p>NMS-light: verifies both default 0 (inert, serial, LDM off), writer
 * setWorkers/setLong before first write (round-trip), reader setLongMax(27)
 * harmless no-op (LDM-27 frames decode), and out-of-range LDM treated as off.
 */
public class LinearZstdWorkersLdmTest {

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void resetZstd() {
        LinearRegionFile.linear$resetZstdForTests();
        LinearFlushCoordinator.linear$resetFlushPoolForTests();
    }

    private static byte[] pattern(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) ((seed * 31 + i * 17) & 0xFF);
        }
        return out;
    }

    private static byte[] readAll(LinearRegionFile region, long chunk) throws Exception {
        try (DataInputStream in = region.getChunkDataInputStream(chunk)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Test
    public void defaultsAreInert() {
        // Both default 0: no config, no override => 0 (serial, LDM off).
        assertEquals(0, LinearRegionFile.linear$resolveCompressionWorkers(),
            "workers default 0 (inert)");
        assertEquals(0, LinearRegionFile.linear$resolveLongDistanceMatching(),
            "LDM default 0 (off)");
    }

    @Test
    public void serialLdmOffRoundTrip() throws Exception {
        Path dir = this.tempDir.resolve("serial18");
        dir.toFile().mkdirs();
        LinearRegionFile.linear$setCompressionWorkersForTests(0);
        LinearRegionFile.linear$setLongDistanceMatchingForTests(0);
        Path file = dir.resolve("r.0.0.linear");
        byte[] payload = pattern(512, 11);
        long pos = ChunkKey.of(0, 0);
        LinearRegionFile region = new LinearRegionFile(file, 1);
        try {
            region.write(pos, ByteBuffer.wrap(payload));
            region.flush();
            assertArrayEquals(payload, readAll(region, pos), "serial LDM-off round-trip");
        } finally {
            try { region.close(); } catch (Exception ignored) {}
        }
        LinearRegionFile reopened = new LinearRegionFile(file, 1);
        try {
            assertArrayEquals(payload, readAll(reopened, pos), "reopened serial LDM-off");
        } finally {
            try { reopened.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    public void workersAtLowLevelRoundTrip() throws Exception {
        Path dir = this.tempDir.resolve("workers18");
        dir.toFile().mkdirs();
        // Workers at level 1: jobs exist (2MiB default job), safe operating point.
        LinearRegionFile.linear$setCompressionWorkersForTests(2);
        LinearRegionFile.linear$setLongDistanceMatchingForTests(0);
        Path file = dir.resolve("r.0.0.linear");
        byte[] payload = pattern(4096, 21);
        long pos = ChunkKey.of(3, 5);
        LinearRegionFile region = new LinearRegionFile(file, 1);
        try {
            region.write(pos, ByteBuffer.wrap(payload));
            region.flush();
            assertArrayEquals(payload, readAll(region, pos), "workers=2 L1 round-trip");
        } finally {
            try { region.close(); } catch (Exception ignored) {}
        }
        LinearRegionFile reopened = new LinearRegionFile(file, 1);
        try {
            assertArrayEquals(payload, readAll(reopened, pos), "reopened workers=2 L1");
        } finally {
            try { reopened.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    public void ldm27RoundTripAndReaderDecodes() throws Exception {
        Path dir = this.tempDir.resolve("ldm18");
        dir.toFile().mkdirs();
        // LDM at level 1 is cheap (+31MB, stock reader decodes).
        LinearRegionFile.linear$setCompressionWorkersForTests(0);
        LinearRegionFile.linear$setLongDistanceMatchingForTests(27);
        Path file = dir.resolve("r.0.0.linear");
        byte[] payload = pattern(2048, 31);
        long pos = ChunkKey.of(7, 9);
        LinearRegionFile region = new LinearRegionFile(file, 1);
        try {
            region.write(pos, ByteBuffer.wrap(payload));
            region.flush();
            assertArrayEquals(payload, readAll(region, pos), "LDM27 in-memory round-trip");
        } finally {
            try { region.close(); } catch (Exception ignored) {}
        }
        // Reader-first: stock reader + setLongMax(27) must decode LDM-27 frame.
        LinearRegionFile.linear$setLongDistanceMatchingForTests(0);
        LinearRegionFile reopened = new LinearRegionFile(file, 1);
        try {
            assertArrayEquals(payload, readAll(reopened, pos), "LDM27 frame decodes with stock reader");
        } finally {
            try { reopened.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    public void outOfRangeLdmIsOff() throws Exception {
        Path dir = this.tempDir.resolve("ldmrange18");
        dir.toFile().mkdirs();
        // Below 10 and above 27: treated as off (JNI silently disables anyway).
        for (int bad : new int[]{5, 30, -1}) {
            LinearRegionFile.linear$setCompressionWorkersForTests(0);
            LinearRegionFile.linear$setLongDistanceMatchingForTests(bad);
            Path file = dir.resolve("r." + (bad + 100) + ".0.linear");
            byte[] payload = pattern(256, bad + 50);
            long pos = ChunkKey.of(1, 1);
            LinearRegionFile region = new LinearRegionFile(file, 1);
            try {
                region.write(pos, ByteBuffer.wrap(payload));
                region.flush();
                assertArrayEquals(payload, readAll(region, pos),
                    "out-of-range LDM " + bad + " treated as off");
            } finally {
                try { region.close(); } catch (Exception ignored) {}
            }
        }
    }
}
