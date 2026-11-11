package net.linear.command.queue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One convert per storage folder, on every leg: a job claims all of its
 * folders (region, entities, poi) or none, and a second {@code --execute} on a
 * busy folder is rejected with {@code conversion already running for <folder>}.
 */
public final class FolderClaims {

    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private FolderClaims() {
    }

    private static String key(Path folder) {
        return folder.toAbsolutePath().normalize().toString();
    }

    /** Claims every folder or none: {@code null} on success, else the first busy folder. */
    public static Path claimAll(List<Path> folders) {
        for (int i = 0; i < folders.size(); i++) {
            if (!IN_FLIGHT.add(key(folders.get(i)))) {
                releaseAll(folders.subList(0, i));
                return folders.get(i);
            }
        }
        return null;
    }

    public static void releaseAll(List<Path> folders) {
        for (Path folder : folders) {
            IN_FLIGHT.remove(key(folder));
        }
    }

    /** The rejection line every leg sends. */
    public static String busyMessage(Path folder) {
        return "conversion already running for " + folder;
    }
}
