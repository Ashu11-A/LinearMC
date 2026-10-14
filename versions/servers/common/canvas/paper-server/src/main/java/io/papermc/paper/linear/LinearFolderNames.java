package io.papermc.paper.linear;

/**
 * Linear linearstats: shared folder-identity helper.
 *
 * @deprecated Moved to core as {@link net.linear.probe.LinearFolderNames} (verbatim
 *     logic). This shim stays only so existing imports of this FQN keep
 *     compiling; it adds no logic. New code must use the core class directly.
 */
@Deprecated
public final class LinearFolderNames extends net.linear.probe.LinearFolderNames {

    private LinearFolderNames() {
    }
}
