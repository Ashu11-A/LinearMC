package net.linear;

import com.github.luben.zstd.Zstd;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Direct-{@link ByteBuffer}-only zstd helpers for chunk payloads.
 *
 * <p>Spottedleaf GC rule (Paper PR #5029 review): the zstd-jni heap/stream
 * wrappers ({@code ZstdInputStream}/{@code ZstdOutputStream}, {@code byte[]}
 * one-shot helpers) must NOT sit on the chunk read/write hot path. They
 * allocate per-call heap buffers and churn the GC on a region-threaded
 * server. Every hot method here therefore:</p>
 * <ul>
 *   <li>requires {@link ByteBuffer#isDirect()} buffers (throws otherwise),</li>
 *   <li>calls only {@link Zstd#compressDirectByteBuffer} /
 *       {@link Zstd#decompressDirectByteBuffer} and the direct frame-size
 *       probes,</li>
 *   <li>never touches {@code InputStream}/{@code OutputStream}/{@code byte[]}
 *       payload copies.</li>
 * </ul>
 *
 * <p>Levels: {@link #LEVEL_HOT} = 1 is the per-chunk hot default, matching
 * LinearPaper ({@code COMPRESSION_LEVEL = 1} in {@code 0002-Linear-region-file-format.patch})
 * and LinearPurpur practice: minimum CPU on the tick-adjacent write path.
 * {@link #LEVEL_FLUSH} = 3 (zstd default, same as the proven Linear cold-tier
 * {@code WorldArchiver} default) is for background flush / full-region rewrites
 * where throughput matters less than ratio. {@link #LEVEL_ARCHIVE} = 6 matches
 * PR #5029's Zstd default and the Linear offline-tool suggestion; use it only
 * for operator-invoked compaction, never on the hot path. Valid range is
 * 1..22 ({@link Zstd#maxCompressionLevel()}).</p>
 *
 * <p>Versioned codec-id registry sketch (R4 constraints): the on-disk chunk
 * {@code version} byte stays Mojang-compatible ({@code 1..4} plus namespaced
 * custom {@code 127}); a zstd dictionary revision is NEVER a new top-level id.
 * It travels as a payload prefix <em>inside</em> the custom-127 frame, after
 * the {@code z:4d} magic carried over from the reference {@code paper-zstd}
 * 8002 patch ({@code 00 04 7a 3a 34 64}). Full dictionary training is Phase-2:
 * this file only stubs the provider API and ships no dictionary bytes.</p>
 *
 * <p>Wiring note: selection stays behind
 * {@code RegionFileVersion.configure(String)} (8001 backport) with
 * {@code DEFAULT = VERSION_DEFLATE}; this codec does not change the default.
 * See {@code REVERSIBILITY.md}.</p>
 */
public final class ZstdChunkCodec {

    /** Hot per-chunk level: LinearPaper/LinearPurpur practice. */
    public static final int LEVEL_HOT = 1;
    /** Background flush / full-rewrite level: zstd default, Linear cold-tier default. */
    public static final int LEVEL_FLUSH = 3;
    /** Operator-invoked offline compaction only (PR #5029 Zstd default). Never hot. */
    public static final int LEVEL_ARCHIVE = 6;

    /** Lowest legal zstd level exposed here (negative "fast" levels excluded deliberately). */
    public static final int MIN_LEVEL = 1;
    /** Highest regular zstd level (ultra levels &gt;= 20 need explicit opt-in, not offered). */
    public static final int MAX_LEVEL = 22;

    /**
     * Reference 8002 magic: {@code 00 04 "z:4d"} — the 6-byte prefix the custom-127
     * payload starts with so {@code RegionFile} can route {@code z}/{@code 4d} frames
     * to the zstd wrapper instead of logging "Unrecognized custom compression".
     * Cold constant only; hot path never copies payloads through heap to check it
     * (see {@link CodecRegistry#hasZstdMagic(ByteBuffer)} which peeks a direct buffer).
     */
    public static final byte[] ZSTD_MAGIC = new byte[] {0x00, 0x04, 0x7a, 0x3a, 0x34, 0x64};

    /** Namespaced custom id Mojang reserves for non-vanilla codecs (1.20.5+). */
    public static final int CUSTOM_ID = 127;
    /** SectorTool-style raw id, listed for the registry sketch only. Not written by this codec. */
    public static final int CODEC_ID_ZSTD_RAW = 5;

    /** Dict-version prefix size in bytes (u16 BE) inside the custom payload. */
    public static final int DICT_VERSION_PREFIX_LEN = 2;
    /** No dictionary: plain zstd frame after the magic. */
    public static final int DICT_VERSION_NONE = 0;
    /** First trained-dictionary generation (reserved; Phase-2 populates it). */
    public static final int DICT_VERSION_V1 = 1;

    /** Header length: magic (6) + dict-version u16 (2). */
    public static final int HEADER_LEN = ZSTD_MAGIC.length + DICT_VERSION_PREFIX_LEN;

    private ZstdChunkCodec() {}

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    /** @throws IllegalArgumentException if the buffer is not direct. */
    public static void requireDirect(ByteBuffer buf, String name) {
        if (buf == null || !buf.isDirect()) {
            throw new IllegalArgumentException(name + " must be a direct ByteBuffer (Spottedleaf GC rule)");
        }
    }

    /** @throws IllegalArgumentException if the level is outside 1..22. */
    public static int checkLevel(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("zstd level out of range 1..22: " + level);
        }
        return level;
    }

    /** Upper bound for the compressed form of {@code srcSize} bytes. Heap-free. */
    public static long compressBound(long srcSize) {
        return Zstd.compressBound(srcSize);
    }

    // ------------------------------------------------------------------
    // Hot path: direct-only compress / decompress
    // ------------------------------------------------------------------

    /**
     * Compress {@code src.remaining()} bytes into {@code dst}, both direct.
     *
     * <p>Contract: reads from {@code src.position()} to {@code src.limit()} without
     * modifying {@code src}; advances {@code dst.position()} by the compressed size.
     * Caller must size {@code dst} with {@link #compressBound(long)} first.
     *
     * @return compressed byte count.
     * @throws IOException on a native compression error.
     */
    public static int compressDirect(ByteBuffer src, ByteBuffer dst, int level) throws IOException {
        requireDirect(src, "src");
        requireDirect(dst, "dst");
        checkLevel(level);
        int srcPos = src.position();
        int srcSize = src.remaining();
        int dstPos = dst.position();
        int dstSize = dst.remaining();
        long rc = Zstd.compressDirectByteBuffer(dst, dstPos, dstSize, src, srcPos, srcSize, level);
        if (Zstd.isError(rc)) {
            throw new IOException("zstd compress failed: " + Zstd.getErrorName(rc));
        }
        dst.position(dstPos + (int) rc);
        return (int) rc;
    }

    /** Hot-path shorthand using {@link #LEVEL_HOT}. */
    public static int compressHot(ByteBuffer src, ByteBuffer dst) throws IOException {
        return compressDirect(src, dst, LEVEL_HOT);
    }

    /** Flush-path shorthand using {@link #LEVEL_FLUSH}. */
    public static int compressFlush(ByteBuffer src, ByteBuffer dst) throws IOException {
        return compressDirect(src, dst, LEVEL_FLUSH);
    }

    /**
     * Warms the zstd native library (extract + load) with a tiny direct
     * round-trip, so the first real flush never pays it on a tick-adjacent
     * thread. Returns true on success, false if native init is unavailable
     * (callers proceed anyway — the first flush will load it lazily).
     */
    public static boolean warmup() {
        try {
            final byte[] probe = new byte[64];
            for (int i = 0; i < probe.length; i++) {
                probe[i] = (byte) (i * 31);
            }
            final ByteBuffer src = ByteBuffer.allocateDirect(probe.length);
            src.put(probe);
            src.flip();
            final ByteBuffer compressed =
                ByteBuffer.allocateDirect((int) compressBound(probe.length));
            final int packed = compressDirect(src, compressed, LEVEL_HOT);
            compressed.flip();
            final ByteBuffer back = ByteBuffer.allocateDirect(probe.length);
            final int unpacked = decompressDirect(compressed, back);
            if (unpacked != probe.length) {
                return false;
            }
            back.flip();
            for (int i = 0; i < probe.length; i++) {
                if (back.get() != probe[i]) {
                    return false;
                }
            }
            return true;
        } catch (final RuntimeException | IOException failed) {
            return false;
        }
    }

    /**
     * Decompress a zstd frame from {@code src} into {@code dst}, both direct.
     *
     * <p>Contract: mirrors {@link #compressDirect}: {@code src} position is left
     * untouched, {@code dst.position()} advances by the decompressed size.
     * Caller sizes {@code dst} from {@link #frameContentSizeDirect(ByteBuffer)}
     * (or the chunk-length header owned by the caller).
     *
     * @return decompressed byte count.
     * @throws IOException on a native decompression error.
     */
    public static int decompressDirect(ByteBuffer src, ByteBuffer dst) throws IOException {
        requireDirect(src, "src");
        requireDirect(dst, "dst");
        int srcPos = src.position();
        int srcSize = src.remaining();
        int dstPos = dst.position();
        int dstSize = dst.remaining();
        long rc = Zstd.decompressDirectByteBuffer(dst, dstPos, dstSize, src, srcPos, srcSize);
        if (Zstd.isError(rc)) {
            throw new IOException("zstd decompress failed: " + Zstd.getErrorName(rc));
        }
        dst.position(dstPos + (int) rc);
        return (int) rc;
    }

    /**
     * Direct-buffer frame-content-size probe (for sizing {@code dst} before
     * {@link #decompressDirect}). Does not copy through heap.
     *
     * @return uncompressed size, or {@code -1} when the frame header does not
     *         carry it (caller falls back to its own length header).
     */
    public static long frameContentSizeDirect(ByteBuffer src) {
        requireDirect(src, "src");
        long size = Zstd.getDirectByteBufferFrameContentSize(src, src.position(), src.remaining());
        return size;
    }

    // ------------------------------------------------------------------
    // Versioned codec-id registry sketch (R4) — cold path, Phase-1 stub
    // ------------------------------------------------------------------

    /**
     * R4 sketch: on-disk {@code version} byte -&gt; (namespace, dict-version) routing.
     *
     * <ul>
     *   <li>Vanilla ids {@code 1..4} (gzip/deflate/none/lz4) are untouched and keep
     *       their {@code RegionFileVersion.configure(..)} names.</li>
     *   <li>Custom {@code 127} routes on the {@code z:4d} magic + the u16-BE
     *       dict-version prefix defined here:
     *       {@code MAGIC(6) | dictVersion u16BE | zstd-frame(s)}.</li>
     *   <li>No new top-level id is minted for a dictionary revision — that is the
     *       R4 constraint this sketch honors. Readers switch on
     *       {@code (magic, dictVersion)}, not on a new version byte.</li>
     *   <li>Phase-2 (not this patch): train + ship versioned dictionaries and back
     *       this registry with a real {@link DictProvider}. Unknown dict versions
     *       must fail closed with {@link IOException}, never silently fall back.</li>
     * </ul>
     */
    public static final class CodecRegistry {

        /** Known dict-version generations; v1+ are Phase-2 stubs. */
        public static final int[] KNOWN_DICT_VERSIONS = {DICT_VERSION_NONE, DICT_VERSION_V1};

        private CodecRegistry() {}

        /** Peek-check the {@code z:4d} magic on a direct buffer without copying. */
        public static boolean hasZstdMagic(ByteBuffer payload) {
            requireDirect(payload, "payload");
            if (payload.remaining() < HEADER_LEN) {
                return false;
            }
            int p = payload.position();
            for (int i = 0; i < ZSTD_MAGIC.length; i++) {
                if (payload.get(p + i) != ZSTD_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        }

        /** Read the u16-BE dict version at {@code MAGIC.length} offset (direct peek). */
        public static int readDictVersion(ByteBuffer payload) throws IOException {
            requireDirect(payload, "payload");
            if (!hasZstdMagic(payload)) {
                throw new IOException("missing z:4d magic: not a linear zstd payload");
            }
            int p = payload.position() + ZSTD_MAGIC.length;
            return payload.getShort(p) & 0xFFFF;
        }

        /**
         * Write the 8-byte header ({@code MAGIC + dictVersion u16BE}) into a direct
         * buffer. Cold path (region create / forced rewrite); the per-chunk hot
         * path writes payload frames only.
         */
        public static void writeHeader(ByteBuffer dst, int dictVersion) throws IOException {
            requireDirect(dst, "dst");
            if (dst.remaining() < HEADER_LEN) {
                throw new IOException("header buffer too small");
            }
            if (dictVersion != DICT_VERSION_NONE && dictVersion != DICT_VERSION_V1) {
                throw new IOException("unknown dict version: " + dictVersion);
            }
            for (byte b : ZSTD_MAGIC) {
                dst.put(b);
            }
            dst.putShort((short) dictVersion);
        }

        /**
         * Validate a payload's {@code (magic, dictVersion)} pair. Only
         * {@link #DICT_VERSION_NONE} is readable in Phase-1; anything else fails
         * closed so a future dictionary can never be mis-decoded as raw zstd.
         */
        public static void validateForRead(ByteBuffer payload) throws IOException {
            int v = readDictVersion(payload);
            if (v != DICT_VERSION_NONE) {
                throw new IOException(
                    "unsupported zstd dict version " + v + " (Phase-2: no trained dictionary ships yet)");
            }
        }
    }

    /**
     * Phase-2 stub: supplies versioned dictionaries as direct buffers.
     * No implementation ships a dictionary in Phase-1 — do not train here.
     */
    public interface DictProvider {
        /** Current dictionary generation this node writes (Phase-1: always NONE). */
        int currentVersion();

        /**
         * Direct buffer view of the dictionary for {@code version}, or {@code null}
         * for {@link #DICT_VERSION_NONE}. Implementations must return direct,
         * read-only buffers.
         */
        ByteBuffer dictFor(int version) throws IOException;
    }

    /** Phase-1 provider: raw frames only, no dictionary bytes anywhere. */
    public static final class NoDictProvider implements DictProvider {
        @Override
        public int currentVersion() {
            return DICT_VERSION_NONE;
        }

        @Override
        public ByteBuffer dictFor(int version) throws IOException {
            if (version != DICT_VERSION_NONE) {
                throw new IOException("no dictionary for version " + version + " (Phase-2 stub)");
            }
            return null;
        }
    }
}
