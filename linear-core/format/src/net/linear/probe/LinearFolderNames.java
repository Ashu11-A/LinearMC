package net.linear.probe;

import java.util.List;

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

    /**
     * Every per-world region-storage folder a leg converts and reports, in
     * order. The Horizon plugin's convert scope reads it; the forks still
     * carry private copies (Folia {@code paper-0009}/{@code paper-0011}, Canvas
     * {@code CommandLinear}/{@code LinearStartupConversion}) that must match
     * until they are moved onto this constant. No leg may convert
     * {@code region/} alone and leave {@code entities/} or {@code poi/}
     * stranded in another format.
     */
    public static final List<String> STORAGE_FOLDERS = List.of("region", "entities", "poi");

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

    /**
     * Per-world configuration key of a storage folder
     * ({@code .../<dimension>/<region|entities|poi>}): the dimension id on
     * the 26.x layout ({@code <level>/dimensions/<ns>/<path>} gives
     * {@code <ns>:<path>}), {@code minecraft:the_nether} / {@code minecraft:the_end}
     * for legacy {@code DIM-1} / {@code DIM1} folders, else the dimension
     * directory name. A folder that is not a storage folder is used as the
     * dimension directory itself.
     */
    public static String worldKey(String folder) {
        String f = folder.replace('\\', '/');
        while (f.endsWith("/")) {
            f = f.substring(0, f.length() - 1);
        }
        int slash = f.lastIndexOf('/');
        String last = slash < 0 ? f : f.substring(slash + 1);
        if (STORAGE_FOLDERS.contains(last) && slash > 0) {
            f = f.substring(0, slash);
        }
        int dims = f.lastIndexOf("/dimensions/");
        if (dims >= 0) {
            String rest = f.substring(dims + "/dimensions/".length());
            int ns = rest.indexOf('/');
            if (ns > 0 && ns < rest.length() - 1) {
                return rest.substring(0, ns) + ":" + rest.substring(ns + 1);
            }
        }
        slash = f.lastIndexOf('/');
        String dir = slash < 0 ? f : f.substring(slash + 1);
        if (dir.equals("DIM-1")) {
            return "minecraft:the_nether";
        }
        if (dir.equals("DIM1")) {
            return "minecraft:the_end";
        }
        return dir;
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
