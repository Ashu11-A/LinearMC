package io.linearmc.horizon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards mixin injection selectors: every {@code method = "..."} descriptor
 * in {@code RegionFileStorageMixin} must be a syntactically valid member
 * reference ({@code name(args)return}). A typo here (e.g. a missing {@code L}
 * on an object return) fails the ENTIRE mixin at boot with
 * {@code InvalidInjectionException}, silently disabling all Linear storage
 * interception. This test fails such typos in CI instead of production.
 *
 * <p>Reads the mixin source relative to the module dir (Gradle test
 * {@code user.dir}); skipped gracefully if the source tree is absent.
 */
public class MixinDescriptorsTest {

    private static final Pattern METHOD_SELECTOR =
        Pattern.compile("method\\s*=\\s*\"([^\"]+)\"");

    // memberName '(' params ')' return — return must be a valid descriptor.
    private static final Pattern MEMBER_REF = Pattern.compile(
        "^[\\w$]+\\((?:(?:[ZBCSIJFD]|L[\\w/$]+;|\\[+(?:[ZBCSIJFD]|L[\\w/$]+;))*)\\)"
            + "(?:V|[ZBCSIJFD]|L[\\w/$]+;|\\[+(?:[ZBCSIJFD]|L[\\w/$]+;))$");

    static List<String> selectorsIn(final String source) {
        final List<String> out = new ArrayList<>();
        final Matcher m = METHOD_SELECTOR.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    static boolean isValidMemberRef(final String selector) {
        // Multiple selectors may be space/comma separated; every part must parse.
        for (final String part : selector.split("[,\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            // Strip Mixin target-quantity suffixes like " [0]" if ever used.
            final String ref = part.replaceAll("\\s*\\[.*\\]$", "");
            // Bare method names (no descriptor) are legal: they match any overload.
            if (ref.matches("^[\\w$]+$")) {
                continue;
            }
            if (!MEMBER_REF.matcher(ref).matches()) {
                return false;
            }
        }
        return true;
    }

    @Test
    public void allRedirectTargetsAreValidDescriptors() throws Exception {
        final Path mixin = Path.of(System.getProperty("user.dir"),
            "src/main/java/io/linearmc/horizon/mixins/RegionFileStorageMixin.java");
        if (!Files.isRegularFile(mixin)) {
            return; // Source tree absent (e.g. binary-only CI layout): nothing to check.
        }
        final List<String> selectors = selectorsIn(Files.readString(mixin));
        assertTrue(selectors.size() >= 2,
            "expected at least the two RegionFile construction redirects, found " + selectors.size());
        for (final String selector : selectors) {
            assertTrue(isValidMemberRef(selector),
                "invalid mixin member descriptor (would fail boot apply): " + selector);
        }
    }

    @Test
    public void validatorCatchesTheKnownTypoShape() {
        // The production incident: missing L on the object return descriptor.
        assertEquals(false, isValidMemberRef(
            "moonrise$getRegionFileIfExists(IILnet/minecraft/world/level/chunk/storage/RegionFile;)"));
        assertEquals(true, isValidMemberRef(
            "moonrise$getRegionFileIfExists(II)Lnet/minecraft/world/level/chunk/storage/RegionFile;"));
        assertEquals(true, isValidMemberRef(
            "getRegionFile(Lnet/minecraft/world/level/ChunkPos;Z)Lnet/minecraft/world/level/chunk/storage/RegionFile;"));
    }
}
