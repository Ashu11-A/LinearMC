package net.linear.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime format override per storage scope (usually world name or folder
 * key). Lets a running server flip a world between LINEAR and ANVIL without
 * a restart — used by reverse (linear to anvil) convert jobs so new writes
 * land in {@code .mca} while unconverted {@code .linear} files keep serving
 * reads through dual-read until each file validates and is deleted.
 *
 * <p>Precedence at NMS resolution sites (legs consult this between sysprop
 * and file): sysprop wins, then this override, then the file value, then
 * the fail-closed default. Pure JDK, no NMS.
 */
public final class LinearFormatOverride {

    private static final Map<String, String> OVERRIDES = new ConcurrentHashMap<>();

    private LinearFormatOverride() {
    }

    /**
     * Sets the effective format for {@code scope}, returning the previous
     * raw value (null when none). Only exact {@code ANVIL}/{@code LINEAR}
     * (case-insensitive) are stored; anything else clears the scope.
     */
    public static String set(String scope, String format) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (format == null || (!format.equalsIgnoreCase("ANVIL") && !format.equalsIgnoreCase("LINEAR"))) {
            return OVERRIDES.remove(scope);
        }
        return OVERRIDES.put(scope, format.toUpperCase(java.util.Locale.ROOT));
    }

    /** Clears the override for {@code scope}, returning the previous raw value. */
    public static String clear(String scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        return OVERRIDES.remove(scope);
    }

    /** Raw override for {@code scope}, or null when none. */
    public static String get(String scope) {
        if (scope == null) {
            return null;
        }
        return OVERRIDES.get(scope);
    }

    /** Test hook: drops all overrides. */
    static void resetForTests() {
        OVERRIDES.clear();
    }
}
