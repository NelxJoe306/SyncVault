package syncvault;

/**
 * User-facing command failure. {@link Main} prints {@link #getMessage()}
 * (when non-empty) and is the only place that calls {@code System.exit}.
 * Domain code and the Netty server must not exit the JVM for these errors;
 * the server turns client mistakes into ERROR frames.
 */
public class CommandException extends SyncVaultException {

    private final int exitCode;

    /** A command failure with the given user-facing message and exit code 1. */
    public CommandException(String message) {
        this(message, 1);
    }

    /** A command failure with the given user-facing message and exit code. */
    public CommandException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    /** Process status {@link Main} should use after printing the message. */
    public int exitCode() {
        return exitCode;
    }
}
