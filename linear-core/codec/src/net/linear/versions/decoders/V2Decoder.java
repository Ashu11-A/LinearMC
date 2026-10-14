package net.linear.versions.decoders;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.versions.base.AbstractLinearVersion;
import net.linear.versions.codecs.V2Linear;

/**
 * Read side of the V2 codec (wire 3: full header + 13 B entries).
 *
 * <p>The {@code V2Linear} version class delegates its {@code readHeader}
 * here: file timestamp (informational), whitelisted grid, ignored region
 * coords, MSB-first bitmap (parsed but unused), NBT-features table. V2
 * uses the default timestamp coercion and bucket index.</p>
 */
public final class V2Decoder {

    private V2Decoder() {
    }

    /**
     * Reads the V2 header from {@code raw} (positioned just after the
     * version byte) and returns the validated grid size.
     */
    public static int readHeader(ByteBuffer raw) throws IOException {
        if (raw.remaining() < 8 + 1 + 4 + 4 + 128 + 1) {
            throw new IOException("Truncated V2 header");
        }
        raw.getLong(); // file timestamp (informational)
        int grid = raw.get() & 0xFF;
        AbstractLinearVersion.checkGrid(grid);
        raw.getInt(); // regionX (ignored; filename is authoritative)
        raw.getInt(); // regionZ
        raw.position(raw.position() + 128); // existence bitmap (parsed but unused, like DivineMC)
        AbstractLinearVersion.skipFeatures(raw);
        return grid;
    }
}
