package io.linearmc.horizon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Family classification is pure; the reflective probe is exercised on the
 * bundle classpath by the per-bundle mixin test. Order matters: a Canvas
 * host also exposes the Folia and Paper marker classes.
 */
public class HostPlatformTest {

    @Test
    public void classifiesHostFamilies() {
        assertEquals(HostPlatform.Family.CANVAS,
            HostPlatform.classify(true, true, true));
        assertEquals(HostPlatform.Family.FOLIA,
            HostPlatform.classify(false, true, true));
        assertEquals(HostPlatform.Family.PAPER,
            HostPlatform.classify(false, false, true));
        assertEquals(HostPlatform.Family.UNKNOWN,
            HostPlatform.classify(false, false, false));
    }
}
