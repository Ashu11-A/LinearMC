package net.linear.versions.codecs;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.LinearImplementation;
import net.linear.bucket.LinearBase;
import net.linear.versions.base.AbstractLinearVersion;
import net.linear.versions.decoders.V1Decoder;
import net.linear.versions.encoders.V1Encoder;
import net.linear.versions.ChunkSlotTable;
import net.linear.versions.ChunkSlotView;
import net.linear.versions.VersionWriteParams;

/**
 * V1 codec (wires 1 and 2, single zstd blob).
 *
 * <p>Thin version delegate: {@code parse} forwards to {@link V1Decoder} and
 * {@code flush} forwards to {@link V1Encoder} (32 B header, 8 B table
 * entries, single zstd frame, footer validation, timestamp preservation,
 * chunkCount/fileLength checks). The bucketed hooks below ({@code wireVersion}
 * et al.) are never invoked on this path — {@code parse}/{@code flush} are
 * overridden here — and exist only to satisfy the shared
 * {@link AbstractLinearVersion} contract. Stateless shared instance,
 * thread-safe.</p>
 */
public final class V1Linear extends AbstractLinearVersion {

    private static final V1Linear SHARED = new V1Linear();

    private V1Linear() {
    }

    /** Shared stateless instance (see {@code LinearBase.getShared}). */
    public static V1Linear shared() {
        return SHARED;
    }

    /** Header byte length (fixed 32 B). */
    public static int headerLength() {
        return V1Decoder.headerLength();
    }

    @Override
    public LinearImplementation implementation() {
        return LinearImplementation.V1;
    }

    @Override
    public void parse(ByteBuffer fileImage, ChunkSlotTable out) throws IOException {
        V1Decoder.parse(fileImage, out);
    }

    @Override
    public ByteBuffer flush(ChunkSlotView slots, VersionWriteParams params) throws IOException {
        return V1Encoder.flush(slots, params);
    }

    @Override
    protected int wireVersion() {
        // Write wire (parse accepts 1 and 2 via V1Decoder); unused on this path.
        return 2;
    }

    @Override
    protected int tableEntrySize() {
        // V1 table entries are 8 B (size + timestamp); unused on this path.
        return 8;
    }

    @Override
    protected boolean hasLevelByte() {
        return false;
    }

    @Override
    protected int readHeader(ByteBuffer raw) throws IOException {
        throw new IOException("V1 has no bucket header");
    }

    @Override
    protected ByteBuffer writeHeader(ChunkSlotView slots, VersionWriteParams params) {
        throw new UnsupportedOperationException("V1 has no bucket header");
    }
}
