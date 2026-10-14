package net.linear.versions.base;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.linear.bucket.LinearBucketReader;
import net.linear.bucket.LinearBucketWriter;
import net.linear.versions.codecs.PumpkinLinear;
import net.linear.versions.codecs.V2Linear;
import net.linear.versions.codecs.V3Linear;
import net.linear.versions.RegionFormatDetector;

/**
 * Deprecated compat supertype for the bucketed codecs.
 *
 * <p>Thin alias: extends {@link AbstractBucketedVersion} with no additional
 * logic. All shared bucketed behavior (parse/flush flow, bucket table,
 * inflate, guards) lives in {@link AbstractBucketedVersion} and
 * {@link AbstractLinearVersion}; per-wire headers live in the
 * {@code *Decoder}/{@code *Encoder} helpers behind the one-file-per-version
 * subclasses ({@code V2Linear}, {@code V3Linear}, {@code PumpkinLinear}).
 * Retained (rather than deleted) because {@code LinearBucketReader} and
 * {@code LinearBucketWriter} narrow on this type via {@code instanceof} and
 * {@code RegionFormatDetector} calls {@code skipFeatures} through it; all
 * three live outside the versions-rewire scope.</p>
 *
 * @deprecated No logic of its own; new code should reference
 *             {@link AbstractBucketedVersion} directly.
 */
@Deprecated
public abstract class V2LinearBase extends AbstractBucketedVersion {

    /**
     * Consumes the V2 NBT-features table (terminator 0x00); shared by V2.
     * Forwarder to {@link AbstractLinearVersion} for
     * {@code RegionFormatDetector}.
     */
    public static void skipFeatures(ByteBuffer raw) throws IOException {
        AbstractLinearVersion.skipFeatures(raw);
    }

    /** Validated-grid forwarder to {@link AbstractLinearVersion}. */
    public static void checkGrid(int grid) throws IOException {
        AbstractLinearVersion.checkGrid(grid);
    }

    /** Footer-validation forwarder to {@link AbstractLinearVersion}. */
    public static void requireFooter(ByteBuffer raw) throws IOException {
        AbstractLinearVersion.requireFooter(raw);
    }
}
