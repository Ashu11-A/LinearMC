package net.linear.convert.versions.external;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.linear.RegionFileFormat;
import net.linear.convert.versions.AbstractVersionConvertModule;
import net.linear.LinearRegionConverter;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.VersionWriteParams;

/**
 * Anvil convert module shim ({@code .mca}).
 *
 * <p>Behavior copied from {@code LinearRegionConverter}: readability is the
 * {@code .mca} extension, header sanity is the {@link #MIN_ANVIL_SIZE} floor
 * (vanilla pads every region file to two 4 KiB sectors on open, so a smaller
 * file was never written by vanilla). Chunk parse/write need the NMS-backed
 * {@code RegionOpener}/{@code ChunkSinkOpener} seams, which stay in the
 * facade ({@code StorageAdapters} is skipped); those two methods fail closed
 * until wiring delegates. Constants are duplicated locally; the facade is
 * never referenced.</p>
 */
public final class AnvilConvertModule extends AbstractVersionConvertModule {

    private static final long MIN_ANVIL_SIZE = 8192L;

    private static final AnvilConvertModule SHARED = new AnvilConvertModule();

    private AnvilConvertModule() {
    }

    /** Shared stateless instance (no codec state; all behavior is per-call). */
    public static AnvilConvertModule shared() {
        return SHARED;
    }

    @Override
    public String sourceFormat() {
        return "mca";
    }

    @Override
    public boolean canRead(Path file) throws IOException {
        return file.getFileName().toString().endsWith(RegionFileFormat.ANVIL_EXTENSION);
    }

    @Override
    public ChunkSlotTable parseToSlots(Path file, int x, int z) throws IOException {
        throw new IOException("Anvil slot parse needs the NMS RegionOpener seam (facade-owned,"
            + " StorageAdapters skipped); not delegated yet: " + file);
    }

    @Override
    public void writeFromSlots(ChunkSlotTable slots, VersionWriteParams params, Path stagingTarget)
        throws IOException {
        throw new IOException("Anvil write needs the NMS ChunkSinkOpener seam (facade-owned,"
            + " StorageAdapters skipped); not delegated yet: " + stagingTarget);
    }

    @Override
    public String targetExtension() {
        return RegionFileFormat.ANVIL_EXTENSION;
    }

    @Override
    public int resolveGrid(int requested) {
        return requested;
    }

    @Override
    public void headerSanity(Path file) throws IOException {
        long size = Files.size(file);
        if (size < MIN_ANVIL_SIZE) {
            throw new IOException("File smaller than anvil header (" + size + " bytes): " + file);
        }
    }
}
