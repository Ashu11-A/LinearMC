package net.linear;

import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import net.linear.buffered.BufferedRegionOutput;

/**
 * Self-reporting: every core mutation path registers with the folder
 * coordinator without caller-side reporting (live-found gap: streaming
 * writes otherwise never flush).
 */
public class LinearRegionSelfReportTest {

    @Test
    public void linearWritesSelfReport() throws Exception {
        Path dir = Files.createTempDirectory("linear-selfreport");
        Path file = dir.resolve("r.0.0" + RegionFileFormat.LINEAR_EXTENSION);
        try (LinearRegionFile region = new LinearRegionFile(file, 6)) {
            region.write(ChunkKey.of(0, 0), ByteBuffer.wrap("direct".getBytes(StandardCharsets.UTF_8)));
            assertTrue(LinearFlushCoordinator.isFolderDirty(dir));
        }
        Path dir2 = Files.createTempDirectory("linear-selfreport-stream");
        Path file2 = dir2.resolve("r.0.0" + RegionFileFormat.LINEAR_EXTENSION);
        try (LinearRegionFile region = new LinearRegionFile(file2, 6)) {
            try (OutputStream out = BufferedRegionOutput.forFile(region, ChunkKey.of(1, 1))) {
                out.write("streamed".getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(LinearFlushCoordinator.isFolderDirty(dir2));
        }
    }
}
