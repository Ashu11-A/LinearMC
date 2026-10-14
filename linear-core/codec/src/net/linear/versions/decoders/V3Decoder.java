package net.linear.versions.decoders;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.versions.base.AbstractLinearVersion;
import net.linear.versions.codecs.V3Linear;

/**
 * Read side of the V3 codec (wire 4: 10 B header + 12 B entries).
 *
 * <p>The {@code V3Linear} version class delegates its {@code readHeader}
 * here: grid byte only (no timestamp, coords, bitmap or features). V3
 * uses the default timestamp coercion and bucket index.</p>
 */
public final class V3Decoder {

    private V3Decoder() {
    }

    /**
     * Reads the V3 header from {@code raw} (positioned just after the
     * version byte) and returns the validated grid size.
     */
    public static int readHeader(ByteBuffer raw) throws IOException {
        if (raw.remaining() < 1) {
            throw new IOException("Truncated V3 header");
        }
        int grid = raw.get() & 0xFF;
        AbstractLinearVersion.checkGrid(grid);
        return grid;
    }
}
