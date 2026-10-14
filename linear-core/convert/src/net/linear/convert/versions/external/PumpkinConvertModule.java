package net.linear.convert.versions.external;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.linear.probe.LinearBucketGrid;
import net.linear.LinearImplementation;
import net.linear.RegionFileFormat;
import net.linear.convert.versions.AbstractVersionConvertModule;
import net.linear.versions.codecs.PumpkinLinear;
import net.linear.LinearRegionConverter;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.RegionFormatDetector;
import net.linear.versions.VersionWriteParams;

/**
 * Pumpkin convert module shim (wire 2 bucketed layout, dense empty buckets).
 *
 * <p>Behavior copied from {@code LinearRegionConverter}: detector-routed
 * ({@code detect == PUMPKIN}), parse/flush via {@code PumpkinLinear.shared()},
 * compression fixed at level 1 (upstream ruzstd Fastest equivalent; the codec
 * forces it internally), grid defaults to 2 when off-whitelist, target
 * {@code .linear}. Constants are duplicated locally; the facade is never
 * referenced.</p>
 */
public final class PumpkinConvertModule extends AbstractVersionConvertModule {

    private static final long SUPERBLOCK = 0xC3FF13183CCA9D9AL;
    private static final long MIN_VALID_SIZE = 32L + 8L;
    private static final int PUMPKIN_DEFAULT_GRID = 2;

    private static final PumpkinConvertModule SHARED = new PumpkinConvertModule();

    private PumpkinConvertModule() {
    }

    /** Shared stateless instance (codecs are thread-safe; all state is per-call). */
    public static PumpkinConvertModule shared() {
        return SHARED;
    }

    @Override
    public String sourceFormat() {
        return "pumpkin";
    }

    @Override
    public boolean canRead(Path file) throws IOException {
        if (!file.getFileName().toString().endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            return false;
        }
        int[] coords = parseRegionName(file.getFileName().toString());
        if (coords == null) {
            return false;
        }
        ByteBuffer image = readImage(file);
        try {
            return RegionFormatDetector.shared().detect(image, coords[0], coords[1])
                == RegionFormatDetector.DetectedFormat.PUMPKIN;
        } catch (IOException corrupt) {
            return false;
        }
    }

    @Override
    public ChunkSlotTable parseToSlots(Path file, int x, int z) throws IOException {
        ByteBuffer image = readImage(file);
        RegionFormatDetector.DetectedFormat detected = detectImage(image, x, z, file);
        if (detected != RegionFormatDetector.DetectedFormat.PUMPKIN) {
            throw new IOException("Expected PUMPKIN layout, detected " + detected + " in " + file);
        }
        ChunkSlotTable out = new ChunkSlotTable();
        out.sourceName = file.toString();
        try {
            PumpkinLinear.shared().parse(image, out);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " in " + file, e);
        }
        return out;
    }

    @Override
    public void writeFromSlots(ChunkSlotTable slots, VersionWriteParams params, Path stagingTarget)
        throws IOException {
        int grid = resolveGrid(params.gridSize());
        // Level is informational here: PumpkinLinear forces 1 internally (upstream-faithful).
        VersionWriteParams effective = new VersionWriteParams(1, grid,
            params.regionX(), params.regionZ(), params.newestTimestamp(), 0, 0);
        ByteBuffer image;
        try {
            image = PumpkinLinear.shared().flush(slots, effective);
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " (pumpkin flush)", e);
        }
        commitImage(image, stagingTarget);
        if (!Files.isRegularFile(stagingTarget)) {
            throw new IOException("Pumpkin staging missing after conversion: " + stagingTarget);
        }
    }

    @Override
    public String targetExtension() {
        return RegionFileFormat.LINEAR_EXTENSION;
    }

    @Override
    public int resolveGrid(int requested) {
        return LinearBucketGrid.isValid(requested) ? requested : PUMPKIN_DEFAULT_GRID;
    }

    @Override
    public void headerSanity(Path file) throws IOException {
        long size = Files.size(file);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            if (size < MIN_VALID_SIZE) {
                throw new IOException("File smaller than header+footer: " + file);
            }
            ByteBuffer head = ByteBuffer.allocate(32);
            head.order(ByteOrder.BIG_ENDIAN);
            int read = 0;
            while (head.hasRemaining()) {
                int n = ch.read(head, read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < 32) {
                throw new IOException("Truncated header in " + file);
            }
            head.flip();
            long magic = head.getLong();
            byte version = head.get();
            if (magic != SUPERBLOCK) {
                throw new IOException("Bad magic in " + file);
            }
            if (!LinearImplementation.isSupportedWire(version & 0xFF)) {
                throw new IOException("Unsupported linear version " + (version & 0xFF) + " in " + file);
            }
            ByteBuffer foot = ByteBuffer.allocate(8);
            foot.order(ByteOrder.BIG_ENDIAN);
            int got = 0;
            while (foot.hasRemaining()) {
                int n = ch.read(foot, size - 8 + got);
                if (n < 0) {
                    break;
                }
                got += n;
            }
            if (got < 8) {
                throw new IOException("Truncated footer in " + file);
            }
            foot.flip();
            if (foot.getLong() != SUPERBLOCK) {
                throw new IOException("Bad footer magic in " + file);
            }
        }
    }
}
