package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import net.linear.config.LinearPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * v1.0.0 default flip: region format ANVIL to LINEAR, linear compression
 * level 1 to 6.
 *
 * <p>Canvas port against current APIs: core {@code RegionFileFormat} plus
 * {@code LinearPolicy} (sysprop wins, file layer is exact-match, fallback is
 * the shipped default). The Canvas Part defaults
 * ({@code WorldConfig.RegionFormat}: LINEAR, 6, guard on) mirror
 * {@code LinearPolicy.DEFAULT_FORMAT}/{@code DEFAULT_LEVEL} pinned below;
 * the Part shape itself is pinned in {@code LinearGlobalConfigTest} so this
 * file stays compilable against staged core ({@code net.linear} only plus
 * JUnit, no NMS bootstrap needed).</p>
 */
public class LinearDefaultFormatTest {

    @TempDir
    private Path tempDir;

    // --- Shipped defaults: LINEAR + 6 (canvas-worlds.yml shape) ---

    @Test
    public void defaultFormatIsLinear() {
        assertEquals(RegionFileFormat.LINEAR, LinearPolicy.DEFAULT_FORMAT,
            "shipped default format must be LINEAR");
        assertEquals(RegionFileFormat.ANVIL, LinearPolicy.FALLBACK_FORMAT,
            "fallback for unknown values stays ANVIL");
        assertEquals(6, LinearPolicy.DEFAULT_LEVEL,
            "shipped default level must be 6");
        assertEquals(6, RegionFileFormat.DEFAULT_COMPRESSION_LEVEL,
            "core default level must be 6");
        assertEquals(22, RegionFileFormat.MAX_COMPRESSION_LEVEL,
            "MAX_COMPRESSION_LEVEL stays 22");
    }

    // --- Sysprop layer: case-insensitive parse, wins over file ---

    @Test
    public void syspropLayerIsCaseInsensitive() {
        assertEquals(RegionFileFormat.LINEAR, RegionFileFormat.fromString("LINEAR"));
        assertEquals(RegionFileFormat.LINEAR, RegionFileFormat.fromString("linear"));
        assertEquals(RegionFileFormat.ANVIL, RegionFileFormat.fromString("anvil"));
        assertEquals(RegionFileFormat.ANVIL, RegionFileFormat.fromString("AnViL"));
        assertEquals(RegionFileFormat.INVALID, RegionFileFormat.fromString(null));
        assertEquals(RegionFileFormat.INVALID, RegionFileFormat.fromString("garbage"));
    }

    @Test
    public void syspropBeatsFile() {
        assertEquals(RegionFileFormat.LINEAR,
            LinearPolicy.resolveFormat("LINEAR", "ANVIL", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.ANVIL,
            LinearPolicy.resolveFormat("anvil", "LINEAR", RegionFileFormat.LINEAR));
        assertEquals(RegionFileFormat.LINEAR,
            LinearPolicy.resolveFormat("linear", "ANVIL", RegionFileFormat.ANVIL),
            "sysprop parse is case-insensitive");
        assertEquals(9, LinearPolicy.resolveLevel("9", 3, 6));
    }

    @Test
    public void unknownSyspropFallsToFileValue() {
        assertEquals(RegionFileFormat.LINEAR,
            LinearPolicy.resolveFormat("BOGUS", "LINEAR", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.ANVIL,
            LinearPolicy.resolveFormat("BOGUS", "ANVIL", RegionFileFormat.LINEAR));
        assertEquals(12, LinearPolicy.resolveLevel("BOGUS", 12, 6));
        assertEquals(12, LinearPolicy.resolveLevel("99", 12, 6));
        assertEquals(6, LinearPolicy.resolveLevel("BOGUS", null, 6));
    }

    // --- File layer: exact-match only (case-sensitive, trimmed) ---

    @Test
    public void fileLayerIsExactMatch() {
        assertEquals(RegionFileFormat.LINEAR,
            LinearPolicy.resolveFileFormat("LINEAR", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.ANVIL,
            LinearPolicy.resolveFileFormat("ANVIL", RegionFileFormat.LINEAR));
        assertEquals(RegionFileFormat.ANVIL,
            LinearPolicy.resolveFileFormat("linear", RegionFileFormat.ANVIL),
            "lowercase file value is unknown -> default");
        assertEquals(RegionFileFormat.LINEAR,
            LinearPolicy.resolveFileFormat("linear", RegionFileFormat.LINEAR),
            "lowercase file value is unknown -> default");
        assertEquals(RegionFileFormat.ANVIL,
            LinearPolicy.resolveFileFormat(null, RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.ANVIL,
            LinearPolicy.resolveFileFormat("BOGUS", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.LINEAR,
            LinearPolicy.resolveFileFormat(" LINEAR ", RegionFileFormat.ANVIL),
            "file value is trimmed");
        // Composition: sysprop absent reads the file layer.
        assertEquals(RegionFileFormat.LINEAR,
            LinearPolicy.resolveFormat(null, "LINEAR", RegionFileFormat.ANVIL));
        assertEquals(RegionFileFormat.LINEAR,
            LinearPolicy.resolveFormat(null, "linear", RegionFileFormat.LINEAR),
            "unknown file value -> default");
    }

    // --- Clamp: 1..22 pass, anything else -> 6 ---

    @Test
    public void clampPassesRangeAndFallsBackToSix() {
        assertEquals(6, RegionFileFormat.clampCompressionLevel(6), "6 passes through");
        assertEquals(1, RegionFileFormat.clampCompressionLevel(1), "1 still in range");
        assertEquals(22, RegionFileFormat.clampCompressionLevel(22), "22 still in range");
        assertEquals(6, RegionFileFormat.clampCompressionLevel(0), "0 falls back to 6");
        assertEquals(6, RegionFileFormat.clampCompressionLevel(23), "23 falls back to 6");
        assertEquals(6, RegionFileFormat.clampCompressionLevel(-5), "negative falls back to 6");
    }

    @Test
    public void resolveLevelClampsFileAndSysprop() {
        assertEquals(6, LinearPolicy.resolveLevel(null, 0, 6));
        assertEquals(6, LinearPolicy.resolveLevel(null, 99, 6));
        assertEquals(6, LinearPolicy.resolveLevel(null, null, 6));
        assertEquals(22, LinearPolicy.resolveLevel(null, 22, 6));
        assertEquals(1, LinearPolicy.resolveLevel("1", null, 6));
        assertEquals(22, LinearPolicy.resolveLevel("22", null, 6));
        assertEquals(6, LinearPolicy.resolveLevel("0", null, 6));
        assertEquals(6, LinearPolicy.resolveLevel("23", null, 6));
    }

    // --- NMS-free round-trip at the new default level ---

    private static byte[] readAll(LinearRegionFile region, long chunk) throws IOException {
        try (DataInputStream in = region.getChunkDataInputStream(chunk)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Test
    public void levelSixHeaderAndPayloadRoundTrip() throws IOException {
        Path file = this.tempDir.resolve("r.0.0.linear");
        long pos = ChunkKey.of(3, 5);
        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i * 17 + 3) & 0xFF);
        }

        LinearRegionFile region = new LinearRegionFile(file, 6);
        region.write(pos, ByteBuffer.wrap(payload));
        region.flush();
        assertTrue(Files.exists(file), "flush must materialise the file");
        assertTrue(Files.size(file) > 0, "flushed file must be non-empty");
        assertArrayEquals(payload, readAll(region, pos), "pre-close read at level 6");
        region.close();

        LinearRegionFile reopened = new LinearRegionFile(file, 6);
        try {
            assertArrayEquals(payload, readAll(reopened, pos), "restart re-read at level 6");
            assertTrue(reopened.hasChunk(pos), "chunk present after reopen");
        } finally {
            reopened.close();
        }
    }
}
