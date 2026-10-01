package net.linear.command;

/**
 * Leg-agnostic message sink for the {@code /linear} family. Pure JDK (no
 * Bukkit): legs adapt their native sender (Bukkit, Horizon) to this.
 */
public interface CommandSender {

    /** Sends a plain-text line to the invoker. */
    void sendMessage(String message);

    /** Returns true when the invoker holds the permission node. */
    boolean hasPermission(String permission);

    /** Display name of the invoker (player name or console label). */
    String name();
}
