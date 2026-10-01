package net.linear;

/**
 * Linear linearstats: shared folder-identity helper.
 *
 * <p>Single shared helper for {@code folderType}/{@code world} inference
 * (de-duplicated from the command + bridge). {@code folderType} domain is
 * pinned to {@code region|poi|entities} inferred from the absolute
 * folder-path suffix (default {@code region}); {@code world} is the parent
 * dir name ({@code .../<world>/<type>}). Granularity is flush-granularity:
 * the event fires once per coordinator {@code flushDirty()} that attempted
 * {@code >=1} file (clean-no-op flushes fire nothing).</p>
 *
 * <p>Moved verbatim from the Paper leg's linearstats folder-identity helper
 * (JDK-only, no NMS). Non-final only so the Paper leg can keep a deprecated
 * subclass shim at the retired FQN; no behavioural extension point is intended.</p>
 */
public class LinearFolderNames {

    protected LinearFolderNames() {
    }

    public static String inferFolderType(String folder) {
        String f = folder.replace('\\', '/');
        if (f.endsWith("/entities") || f.contains("/entities/")) {
            return "entities";
        }
        if (f.endsWith("/poi") || f.contains("/poi/")) {
            return "poi";
        }
        return "region";
    }

    public static String inferWorld(String folder) {
        String f = folder.replace('\\', '/');
        // Folder is .../<world>/<region|poi|entities>; world is the parent name.
        int slash = f.lastIndexOf('/');
        if (slash <= 0) {
            return f;
        }
        String parent = f.substring(0, slash);
        int slash2 = parent.lastIndexOf('/');
        return slash2 < 0 ? parent : parent.substring(slash2 + 1);
    }
}
