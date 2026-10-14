package net.linear;

/**
 * Linear on-disk implementation selector (DivineMC-compatible numbering).
 *
 * <p>Wire version bytes: V1 writes 2 (legacy 1 aliases to 2), V2 writes 3,
 * V3 writes 4. This enum owns the mapping so {@code LinearRegionFile} load
 * dispatch and config resolution share one source of truth. Pure JDK, NMS-free.</p>
 */
public enum LinearImplementation {
    V1(2),
    V2(3),
    V3(4);

    private final int version;

    LinearImplementation(int version) {
        this.version = version;
    }

    /** Wire version byte written by this implementation. */
    public byte version() {
        return (byte) version;
    }

    /**
     * Resolves a wire version byte to its implementation.
     * Legacy version 1 aliases to V1 (LinearPaper wrote 1 with zeroed timestamps).
     *
     * @return implementation, or {@code null} when unsupported
     */
    public static LinearImplementation forWireVersion(int wire) {
        if (wire == 1 || wire == 2) {
            return V1;
        }
        if (wire == 3) {
            return V2;
        }
        if (wire == 4) {
            return V3;
        }
        return null;
    }

    /**
     * Sole readability gate: non-null means this wire version opens.
     * All load paths must delegate here instead of range-checking,
     * so a future wire version changes in exactly one place.
     */
    public static boolean isSupportedWire(int wire) {
        return forWireVersion(wire) != null;
    }

    /**
     * Case-insensitive config parse; unknown (or {@code null}) yields {@code null}
     * so callers fall back to V1.
     */
    public static LinearImplementation fromString(String name) {
        if (name == null) {
            return null;
        }
        for (LinearImplementation candidate : values()) {
            if (candidate.name().equalsIgnoreCase(name.trim())) {
                return candidate;
            }
        }
        return null;
    }
}
