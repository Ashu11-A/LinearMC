package net.linear;

import net.linear.versions.V1Linear;
import net.linear.versions.V2Linear;
import net.linear.versions.V3Linear;
import net.linear.versions.Version;

/**
 * Single wire-to-implementation map for the linear region codecs
 * (DivineMC-style {@code LinearBase}).
 *
 * <p>Wires 1 and 2 alias to V1 (LinearPaper wrote 1 with zeroed
 * timestamps), 3 selects V2, 4 selects V3. Returned codecs are shared
 * stateless singletons (thread-safe); all IO, locks and coordinator
 * reporting stay with the caller ({@code LinearRegionFile}).</p>
 */
public final class LinearBase {

    private LinearBase() {
    }

    /**
     * Returns the shared codec for {@code wireVersion}.
     *
     * @param wireVersion raw version byte from the file header
     * @return shared {@link Version} (1,2 -&gt; V1; 3 -&gt; V2; 4 -&gt; V3)
     * @throws IllegalArgumentException for any other wire value; callers
     *         opening files translate this to an {@link java.io.IOException}
     *         carrying file context
     */
    public static Version getShared(byte wireVersion) {
        switch (wireVersion) {
            case 1:
            case 2:
                return V1Linear.shared();
            case 3:
                return V2Linear.shared();
            case 4:
                return V3Linear.shared();
            default:
                throw new IllegalArgumentException("Unsupported linear version " + (wireVersion & 0xFF));
        }
    }
}
