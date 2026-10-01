package io.papermc.paper.configuration.type;

/**
 * Linear A7: region file format selector.
 * Mirrors Kaiiju {@code dev.kaiijumc.kaiiju.region.RegionFileFormat}
 * minus the {@code INVALID} sentinel. Unknown strings resolve to
 * {@code null} via the shared {@code EnumValueSerializer}; the world-side
 * {@code @PostProcess} (0002) logs SEVERE and falls back to ANVIL.
 *
 * <p>Thin mapping over the core {@link net.linear.RegionFileFormat}: the leg
 * enum keeps its two values (no {@code INVALID} sentinel) and converts via
 * {@link #map(net.linear.RegionFileFormat)} / {@link #toCore()}. Core
 * {@code INVALID} maps to {@code null} (unknown, same as the serializer).</p>
 */
public enum RegionFileFormat {
    ANVIL,
    LINEAR;

    /**
     * Core-to-leg mapping; {@code null} or core {@code INVALID} yields
     * {@code null} (unknown, handled by the serializer fallback).
     */
    public static RegionFileFormat map(final net.linear.RegionFileFormat core) {
        if (core == null) {
            return null;
        }
        switch (core) {
            case ANVIL:
                return ANVIL;
            case LINEAR:
                return LINEAR;
            default:
                return null;
        }
    }

    /** Leg-to-core mapping (total: both leg values have a core twin). */
    public net.linear.RegionFileFormat toCore() {
        switch (this) {
            case ANVIL:
                return net.linear.RegionFileFormat.ANVIL;
            case LINEAR:
                return net.linear.RegionFileFormat.LINEAR;
            default:
                throw new AssertionError("unmapped leg format: " + this);
        }
    }
}
