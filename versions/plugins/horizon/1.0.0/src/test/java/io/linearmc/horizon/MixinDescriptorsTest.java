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

    /**
     * Per-bundle patcher check: every mixin contact point must exist in the
     * NMS classes of the bundle under test (the test classpath carries the
     * dev-bundle target of this matrix leg: 26.1.2, 26.2 or 26.3). A missing
     * host method, shadow field or overwrite target fails the mixin apply at
     * boot with {@code defaultRequire}, so a miss here is a red leg, never
     * a skip. Descriptors are matched exactly (name + params + return).
     */
    @Test
    public void mixinTargetsExistInThisBundle() throws Exception {
        final Path mixin = Path.of(System.getProperty("user.dir"),
            "src/main/java/io/linearmc/horizon/mixins/RegionFileStorageMixin.java");
        if (!Files.isRegularFile(mixin)) {
            return; // Same binary-only-layout rule as above.
        }
        final String source = Files.readString(mixin);
        final java.util.Map<String, String> imports = importsIn(source);
        final List<String> problems = new ArrayList<>();
        final Matcher mixinDecl = Pattern.compile("@Mixin\\(([^)]+)\\)").matcher(source);
        if (!mixinDecl.find()) {
            fail("no @Mixin target declared");
        }
        final String[] targets = mixinDecl.group(1).split(",");
        if (targets.length != 1) {
            fail("single-target mixin expected, found " + targets.length);
        }
        final Class<?> target = load(mixinDecl, targets[0].trim(), imports, problems);
        if (target == null || !problems.isEmpty()) {
            fail("unresolvable @Mixin target: " + String.join("; ", problems));
        }
        // Injection hosts + overwrite: exact descriptor match.
        final Matcher sel = METHOD_SELECTOR.matcher(source);
        while (sel.find()) {
            for (final String part : sel.group(1).split("[,\\s]+")) {
                if (part.isEmpty()) {
                    continue;
                }
                checkMethodSelector(target, part, problems);
            }
        }
        // NEW targets (@At value NEW): the class must exist.
        final Matcher news = Pattern.compile("value\\s*=\\s*\"NEW\"[^}]*?target\\s*=\\s*\"([^\"]+)\"",
            Pattern.DOTALL).matcher(source);
        while (news.find()) {
            final String internal = news.group(1);
            try {
                Class.forName(internal.replace('/', '.'), false, getClass().getClassLoader());
            } catch (final LinkageError | ReflectiveOperationException missing) {
                problems.add("NEW target missing in this bundle: " + internal);
            }
        }
        // Shadows: field exists with matching type and static-ness.
        final Matcher shadows = Pattern.compile(
            "@Shadow\\s+(?:@Final\\s+)?(?:private|protected|public)?\\s*(static\\s+)?([\\w$.]+)\\s+(\\w+)\\s*;",
            Pattern.DOTALL).matcher(source);
        while (shadows.find()) {
            final boolean wantStatic = shadows.group(1) != null;
            final Class<?> wantType = resolve(shadows.group(2), imports);
            final String name = shadows.group(3);
            if (wantType == null) {
                problems.add("unresolvable @Shadow type: " + shadows.group(2) + " " + name);
                continue;
            }
            try {
                final java.lang.reflect.Field f = target.getDeclaredField(name);
                if (!f.getType().equals(wantType)) {
                    problems.add("@Shadow type drift: " + name + " is "
                        + f.getType().getName() + ", mixin says " + wantType.getName());
                }
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) != wantStatic) {
                    problems.add("@Shadow static drift: " + name);
                }
            } catch (final NoSuchFieldException missing) {
                problems.add("@Shadow target missing in this bundle: " + name);
            }
        }
        // Overwrites: exact signature match (name + params + return + static).
        final Matcher overwrites = Pattern.compile(
            "@Overwrite\\s+(?:private|protected|public)?\\s*(static\\s+)?([\\w$.]+)\\s+(\\w+)\\s*\\(([^)]*)\\)",
            Pattern.DOTALL).matcher(source);
        while (overwrites.find()) {
            final boolean wantStatic = overwrites.group(1) != null;
            final Class<?> wantReturn = resolve(overwrites.group(2), imports);
            final String name = overwrites.group(3);
            final Class<?>[] wantParams = resolveParams(overwrites.group(4), imports, problems, name);
            if (wantReturn == null || wantParams == null) {
                continue;
            }
            try {
                final java.lang.reflect.Method m =
                    target.getDeclaredMethod(name, wantParams);
                if (!m.getReturnType().equals(wantReturn)) {
                    problems.add("@Overwrite return drift: " + name + " returns "
                        + m.getReturnType().getName());
                }
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) != wantStatic) {
                    problems.add("@Overwrite static drift: " + name);
                }
            } catch (final NoSuchMethodException missing) {
                problems.add("@Overwrite target missing in this bundle: " + name);
            }
        }
        assertTrue(problems.isEmpty(),
            "mixin would fail boot apply on this bundle: " + String.join("; ", problems));
    }

    private static Class<?> load(final Matcher where, final String ref,
        final java.util.Map<String, String> imports, final List<String> problems) {
        final String simple = ref.replace(".class", "").trim();
        final String fqcn = simple.contains(".") ? simple : imports.get(simple);
        if (fqcn == null) {
            problems.add("unresolvable target: " + ref);
            return null;
        }
        try {
            return Class.forName(fqcn, false, MixinDescriptorsTest.class.getClassLoader());
        } catch (final LinkageError | ReflectiveOperationException missing) {
            problems.add("target missing in this bundle: " + fqcn);
            return null;
        }
    }

    private static void checkMethodSelector(final Class<?> target, final String selector,
        final List<String> problems) {
        final int open = selector.indexOf('(');
        if (open < 0) {
            // Bare name (HEAD/TAIL injects): any overload satisfies Mixin.
            if (target.getDeclaredMethods() != null) {
                boolean found = false;
                for (final java.lang.reflect.Method m : target.getDeclaredMethods()) {
                    if (m.getName().equals(selector)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    problems.add("inject host missing in this bundle: " + selector);
                }
            }
            return;
        }
        final String name = selector.substring(0, open);
        final int close = selector.indexOf(')', open);
        final String paramsDesc = selector.substring(open, close + 1);
        final String returnDesc = selector.substring(close + 1);
        final Class<?>[] wantParams;
        final Class<?> wantReturn;
        try {
            wantParams = parseParams(paramsDesc);
            wantReturn = parseType(new StringReader(returnDesc));
        } catch (final IllegalArgumentException bad) {
            problems.add("unparseable selector (syntax gate should have caught it): " + selector);
            return;
        }
        try {
            final java.lang.reflect.Method m = target.getDeclaredMethod(name, wantParams);
            if (!m.getReturnType().equals(wantReturn)) {
                problems.add("host return drift: " + selector + " returns "
                    + m.getReturnType().getName());
            }
        } catch (final NoSuchMethodException missing) {
            problems.add("host method missing in this bundle: " + selector);
        }
    }

    private static java.util.Map<String, String> importsIn(final String source) {
        final java.util.Map<String, String> out = new java.util.HashMap<>();
        final Matcher m = Pattern.compile("(?m)^import\\s+([\\w$.]+);").matcher(source);
        while (m.find()) {
            final String fqcn = m.group(1);
            out.put(fqcn.substring(fqcn.lastIndexOf('.') + 1), fqcn);
        }
        return out;
    }

    private static Class<?> resolve(final String name, final java.util.Map<String, String> imports) {
        switch (name) {
            case "byte": return byte.class;
            case "short": return short.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "char": return char.class;
            case "boolean": return boolean.class;
            case "void": return void.class;
            default:
                break;
        }
        String fqcn = name.contains(".") ? name : imports.get(name);
        if (fqcn == null) {
            return null;
        }
        try {
            return Class.forName(fqcn, false, MixinDescriptorsTest.class.getClassLoader());
        } catch (final LinkageError | ReflectiveOperationException missing) {
            return null;
        }
    }

    private static Class<?>[] resolveParams(final String params,
        final java.util.Map<String, String> imports, final List<String> problems,
        final String context) {
        if (params.trim().isEmpty()) {
            return new Class<?>[0];
        }
        final String[] parts = params.split(",");
        final Class<?>[] out = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim().replaceAll("^(final\\s+)?", "");
            final int space = part.lastIndexOf(' ');
            final String type = space < 0 ? part : part.substring(0, space);
            final Class<?> resolved = resolve(type.replace("...", "[]"), imports);
            if (resolved == null) {
                problems.add("unresolvable @Overwrite param in " + context + ": " + type);
                return null;
            }
            out[i] = resolved;
        }
        return out;
    }

    private static final class StringReader {
        final String s;
        int i;

        StringReader(final String s) {
            this.s = s;
        }
    }

    private static Class<?> parseType(final StringReader r) {
        if (r.i >= r.s.length()) {
            throw new IllegalArgumentException("truncated descriptor");
        }
        final char c = r.s.charAt(r.i++);
        switch (c) {
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'D': return double.class;
            case 'F': return float.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'S': return short.class;
            case 'Z': return boolean.class;
            case 'V': return void.class;
            case 'L': {
                final int end = r.s.indexOf(';', r.i);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated L");
                }
                final String internal = r.s.substring(r.i, end);
                r.i = end + 1;
                try {
                    return Class.forName(internal.replace('/', '.'),
                        false, MixinDescriptorsTest.class.getClassLoader());
                } catch (final LinkageError | ReflectiveOperationException missing) {
                    throw new IllegalArgumentException("missing " + internal, missing);
                }
            }
            case '[': {
                final Class<?> component = parseType(r);
                return java.lang.reflect.Array.newInstance(component, 0).getClass();
            }
            default:
                throw new IllegalArgumentException("bad descriptor char " + c);
        }
    }

    private static Class<?>[] parseParams(final String paramsDesc) {
        if (!paramsDesc.startsWith("(") || !paramsDesc.endsWith(")")) {
            throw new IllegalArgumentException("bad params " + paramsDesc);
        }
        final List<Class<?>> out = new ArrayList<>();
        final StringReader r = new StringReader(paramsDesc.substring(1, paramsDesc.length() - 1));
        while (r.i < r.s.length()) {
            out.add(parseType(r));
        }
        return out.toArray(new Class<?>[0]);
    }
}
