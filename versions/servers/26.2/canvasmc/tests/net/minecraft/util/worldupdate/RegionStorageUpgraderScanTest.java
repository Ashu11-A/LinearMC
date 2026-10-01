package net.minecraft.util.worldupdate;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Both-extension upgrade-scan coverage.
 *
 * <p>The upgrader REGEX must accept both extensions with no literal spaces
 * inside the alternation: {@code "^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(linear|mca)$"}.
 * The scan lives in {@link RegionStorageUpgrader}: the {@code REGEX} field plus
 * the {@code listFiles} filename filter ({@code endsWith} checks). Both cover
 * {@code linear} and {@code mca}; filenames always start with the {@code r.}
 * prefix.
 * Only exact dual-extension names match; junk is rejected.
 *
 * <p>These tests read the REAL private {@code REGEX} via reflection when available (so a
 * regression to spaced/mca-only fails), and always verify the filename filter predicate
 * and the fixed-vs-spaced pattern behaviour directly. No DataFixer/storage needed.
 *
 * <p>Target path when integrated: the server {@code src/test/java} tree (mirrors
 * the JUnit-Jupiter layout used by the other storage tests).
 */
public class RegionStorageUpgraderScanTest {

    private static final Pattern FIXED_REGEX =
        Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(linear|mca)$");
    private static final Pattern BUGGY_REGEX =
        Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(linear | mca)$");

    /** Best-effort read of the production REGEX; null if the scan moved to a dispatch helper. */
    private static Pattern productionRegex() {
        try {
            Field f = RegionStorageUpgrader.class.getDeclaredField("REGEX");
            f.setAccessible(true);
            return (Pattern) f.get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    /** Mirrors the fixed line-163 filter: {@code endsWith .mca || endsWith .linear}. */
    private static boolean fixedFilenameFilter(String name) {
        return name.endsWith(".mca") || name.endsWith(".linear");
    }

    @ParameterizedTest(name = "scan accepts {0}")
    @ValueSource(strings = {
        "r.0.0.mca", "r.1.2.mca", "r.-1.-2.mca",
        "r.0.0.linear", "r.1.2.linear", "r.-1.-2.linear", "r.-10.4.linear",
    })
    public void fixedRegexAcceptsBothExtensions(String name) {
        assertTrue(FIXED_REGEX.matcher(name).matches(), "fixed REGEX must match " + name);
    }

    @ParameterizedTest(name = "scan rejects {0}")
    @ValueSource(strings = {
        "foo.mca", "r.0.0.txt", "r.a.b.mca", "r.0.mca", "r.0.0.mca.bak",
        "r.0.0.linear ",   // trailing space
        "r.0.0. linear",   // spaced extension
        "r.0.0. mca",
        "r.0.0.linear|mca",
        "r.0.0.MCA", "r.0.0.LINEAR",
    })
    public void fixedRegexRejectsJunk(String name) {
        assertFalse(FIXED_REGEX.matcher(name).matches(), "fixed REGEX must reject " + name);
    }

    @Test
    public void buggySpacedRegexMatchesNothingReal() {
        // Pin the defect: the spaced alternation never matches real names.
        String[] realNames = {"r.0.0.mca", "r.0.0.linear", "r.-1.2.mca", "r.-1.2.linear"};
        for (String name : realNames) {
            assertFalse(BUGGY_REGEX.matcher(name).matches(), "buggy REGEX unexpectedly matched " + name);
        }
        // It only matches the literally-spaced strings nobody writes.
        assertTrue(BUGGY_REGEX.matcher("r.0.0.linear ").matches());
        assertTrue(BUGGY_REGEX.matcher("r.0.0. mca").matches());
    }

    @Test
    public void productionRegexMatchesBothExtensions() {
        Pattern actual = productionRegex();
        assertNotNull(actual, "RegionStorageUpgrader.REGEX not found via reflection — "
            + "if the scan moved to a dispatch helper, apply the dual-extension fix there");
        for (String name : new String[]{"r.0.0.mca", "r.-1.2.mca", "r.0.0.linear", "r.-1.2.linear"}) {
            assertTrue(actual.matcher(name).matches(), "production REGEX must match " + name);
        }
        assertFalse(actual.matcher("r.0.0.txt").matches());
        assertFalse(actual.matcher("r.0.0.linear ").matches(), "production REGEX must not contain literal spaces");
        // Accept either alternation order — (mca|linear) or (linear|mca);
        // both are the spaceless dual-extension form. What matters is both
        // extensions match, no spaces.
        String pattern = actual.pattern();
        boolean ok = pattern.equals("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(linear|mca)$")
            || pattern.equals("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(mca|linear)$");
        assertTrue(ok, "production REGEX pattern must be the spaceless dual-extension form, was: " + pattern);
    }

    @ParameterizedTest(name = "filter accepts {0}")
    @ValueSource(strings = {"r.0.0.mca", "r.-1.2.linear", "r.5.5.mca", "r.5.5.linear"})
    public void filenameFilterAcceptsBothExtensions(String name) {
        assertTrue(fixedFilenameFilter(name), "filter must accept " + name);
    }

    @ParameterizedTest(name = "filter rejects {0}")
    @ValueSource(strings = {"r.0.0.txt", "r.0.0.nbt", "r.0.0.mca.bak", "r.0.0.linear.tmp", "level.dat"})
    public void filenameFilterRejectsJunk(String name) {
        assertFalse(fixedFilenameFilter(name), "filter must reject " + name);
    }
}
