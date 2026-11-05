package io.linearmc.horizon;

/**
 * Detects the host server family without linking against optional classes.
 *
 * <p>The plugin ships one jar for Paper, Folia and Canvas 26.1-26.3. None of
 * these probes is required for operation: they only make the enable log name
 * the host and the active storage path. Missing classes are treated as
 * "not that family", never as a failure.</p>
 */
public final class HostPlatform {

    public enum Family {
        CANVAS,
        FOLIA,
        PAPER,
        UNKNOWN
    }

    private HostPlatform() {
    }

    public static Family detect() {
        return detect(HostPlatform.class.getClassLoader());
    }

    static Family detect(final ClassLoader loader) {
        return classify(
            has(loader, "io.canvasmc.canvas.GlobalConfiguration"),
            has(loader, "io.papermc.paper.threadedregions.RegionizedServer"),
            has(loader, "io.papermc.paper.ServerBuildInfo"));
    }

    static Family classify(final boolean canvas, final boolean folia, final boolean paper) {
        if (canvas) {
            return Family.CANVAS;
        }
        if (folia) {
            return Family.FOLIA;
        }
        if (paper) {
            return Family.PAPER;
        }
        return Family.UNKNOWN;
    }

    private static boolean has(final ClassLoader loader, final String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (final ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }
}
