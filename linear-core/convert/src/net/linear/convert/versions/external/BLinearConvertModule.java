package net.linear.convert.versions.external;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.linear.RegionFileFormat;
import net.linear.convert.versions.AbstractVersionConvertModule;
import net.linear.LinearRegionConverter;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.VersionWriteParams;

/**
 * Buffered-linear convert module shim ({@code .b_linear} master).
 *
 * <p>Behavior copied from {@code LinearRegionConverter}: readability is the
 * {@code .b_linear} extension, header sanity is the 18 B master magic plus
 * version with the {@link #MIN_BLINEAR_SIZE} floor (the master carries no
 * footer; the zstd frame is self-checking). Buffered chunk IO stays in the
 * facade, so parse/write fail closed until wiring delegates. Constants are
 * duplicated locally (including the master magic/version, which are
 * package-private in the codec); the facade is never referenced.</p>
 */
public final class BLinearConvertModule extends AbstractVersionConvertModule {

    private static final long MASTER_SUPERBLOCK = -0x200812250269L;
    private static final int MASTER_VERSION = 2;
    private static final long MIN_BLINEAR_SIZE = 24L;

    private static final BLinearConvertModule SHARED = new BLinearConvertModule();

    private BLinearConvertModule() {
    }

    /** Shared stateless instance (no codec state; all behavior is per-call). */
    public static BLinearConvertModule shared() {
        return SHARED;
    }

    @Override
    public String sourceFormat() {
        return "b_linear";
    }

    @Override
    public boolean canRead(Path file) throws IOException {
        return file.getFileName().toString().endsWith(RegionFileFormat.B_LINEAR_EXTENSION);
    }

    @Override
    public ChunkSlotTable parseToSlots(Path file, int x, int z) throws IOException {
        throw new IOException("Buffered-linear slot parse needs the facade buffered IO seam"
            + " (StorageAdapters skipped); not delegated yet: " + file);
    }

    @Override
    public void writeFromSlots(ChunkSlotTable slots, VersionWriteParams params, Path stagingTarget)
        throws IOException {
        throw new IOException("Buffered-linear write needs the facade buffered IO seam"
            + " (StorageAdapters skipped); not delegated yet: " + stagingTarget);
    }

    @Override
    public String targetExtension() {
        return RegionFileFormat.B_LINEAR_EXTENSION;
    }

    @Override
    public int resolveGrid(int requested) {
        return requested;
    }

    @Override
    public void headerSanity(Path file) throws IOException {
        long size = Files.size(file);
        if (size < MIN_BLINEAR_SIZE) {
            throw new IOException("File smaller than buffered header: " + file);
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate(18);
            head.order(ByteOrder.BIG_ENDIAN);
            int read = 0;
            while (head.hasRemaining()) {
                int n = ch.read(head, read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < 18) {
                throw new IOException("Truncated buffered header in " + file);
            }
            head.flip();
            if (head.getLong() != MASTER_SUPERBLOCK) {
                throw new IOException("Bad buffered magic in " + file);
            }
            if ((head.get() & 0xFF) != (MASTER_VERSION & 0xFF)) {
                throw new IOException("Unsupported buffered version in " + file);
            }
        }
    }
}
