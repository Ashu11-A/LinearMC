package net.linear.command;

/**
 * Thrown by {@link LinearCommandParser#parse} when the args do not match the
 * {@code /linear} grammar. The message always ends with the usage text so
 * legs can display it directly.
 */
public final class InvalidUsageException extends RuntimeException {

    public InvalidUsageException(String message) {
        super(message);
    }
}
