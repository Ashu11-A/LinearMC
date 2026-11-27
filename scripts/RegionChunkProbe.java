import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import net.linear.AbstractRegionFile;
import net.linear.BufferedLinearRegionFile;
import net.linear.ChunkKey;
import net.linear.LinearRegionFile;

/**
 * Read-only chunk probe for a COPY of a .linear / .b_linear region file, used
 * by scripts/coupled-order-check.py (never point it at a live server's file:
 * opening a .b_linear creates a .swp sibling).
 *
 * <p>usage: java RegionChunkProbe region-file out-dir chunkX chunkZ [chunkX chunkZ ...]
 * <br>prints one line per chunk: {@code <x> <z> present <bytes>} (uncompressed
 * NBT written to out-dir/c.x.z.nbt) or {@code <x> <z> absent}.
 */
public final class RegionChunkProbe {
    public static void main(final String[] args) throws Exception {
        final Path file = Path.of(args[0]);
        final Path out = Path.of(args[1]);
        Files.createDirectories(out);
        final String name = file.getFileName().toString();
        final AbstractRegionFile region = name.endsWith(".b_linear")
            ? new BufferedLinearRegionFile(file, 1)
            : new LinearRegionFile(file, 1);
        try {
            for (int i = 2; i + 1 < args.length; i += 2) {
                final int x = Integer.parseInt(args[i]);
                final int z = Integer.parseInt(args[i + 1]);
                final long key = ChunkKey.of(x, z);
                byte[] data = null;
                if (region.hasChunk(key)) {
                    try (DataInputStream in = region.getChunkDataInputStream(key)) {
                        if (in != null) {
                            data = in.readAllBytes();
                        }
                    }
                }
                if (data == null) {
                    System.out.println(x + " " + z + " absent");
                } else {
                    Files.write(out.resolve("c." + x + "." + z + ".nbt"), data);
                    System.out.println(x + " " + z + " present " + data.length);
                }
            }
        } finally {
            region.close();
        }
    }
}
