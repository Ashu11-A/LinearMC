package net.linear.compression;

import java.nio.ByteBuffer;
import net.openhft.hashing.ByteBufferAccess;
import net.openhft.hashing.LongHashFunction;

/**
 * Delega para OpenHFT ({@code LongHashFunction.xx()}, XXH64 seed 0).
 *
 * <p>Byte-compativel com o antigo {@code LinearXxHash64}: mesmos vetores
 * para seed 0. Vale para heap/direto/somente-leitura no Java 25 via
 * caminho Access (evita crash de DirectBuffer).</p>
 */
public final class LinearHash {

    // Instancia unica XXH64 seed 0.
    private static final LongHashFunction XX = LongHashFunction.xx();

    private LinearHash() {
    }

    /**
     * Calcula hash de {@code len} bytes a partir de {@code off} (seed 0).
     */
    public static long hashBytes(byte[] src, int off, int len) {
        if (src == null) {
            throw new NullPointerException("src");
        }
        if (off < 0 || len < 0 || len > src.length - off) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len);
        }
        return XX.hashBytes(src, off, len);
    }

    /**
     * Calcula hash dos bytes restantes sem mudar posicao/limite.
     */
    public static long hashBuffer(ByteBuffer buf) {
        if (buf == null) {
            throw new NullPointerException("buf");
        }
        ByteBuffer dup = buf.duplicate(); // copia visao, sem mutacao
        return XX.hash(dup, ByteBufferAccess.INSTANCE, dup.position(), dup.remaining());
    }
}
