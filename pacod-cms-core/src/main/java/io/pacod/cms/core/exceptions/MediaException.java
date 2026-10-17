package io.pacod.cms.core.exceptions;

import java.security.PrivilegedActionException;

public class MediaException extends Exception {

    /**
     * A message key the management console resolves in the operator's language, or null. Core has
     * no message bundles -- every other caller uses {@link #getMessage()} as written -- so the key
     * travels beside a readable default rather than replacing it.
     */
    private final String code;
    private final transient Object[] args;

    /**
     * Constructs a new exception with the specified detail message.  The
     * cause is not initialized, and may subsequently be initialized by
     * a call to {@link #initCause}.
     *
     * @param message the detail message. The detail message is saved for
     *                later retrieval by the {@link #getMessage()} method.
     */
    public MediaException(String message) {
        super(message);
        this.code = null;
        this.args = new Object[0];
    }

    /**
     * A message with a key for the console: {@code message} is what every other caller sees,
     * {@code code} and {@code args} what the console resolves in the operator's language.
     */
    public MediaException(String message, String code, Object... args) {
        super(message);
        this.code = code;
        this.args = args == null ? new Object[0] : args;
    }

    /**
     * Constructs a new exception with the specified cause and a detail
     * message of {@code (cause==null ? null : cause.toString())} (which
     * typically contains the class and detail message of {@code cause}).
     * This constructor is useful for exceptions that are little more than
     * wrappers for other throwables (for example, {@link
     * PrivilegedActionException}).
     *
     * @param cause the cause (which is saved for later retrieval by the
     *              {@link #getCause()} method).  (A {@code null} value is
     *              permitted, and indicates that the cause is nonexistent or
     *              unknown.)
     * @since 1.4
     */
    public MediaException(Throwable cause) {
        super(cause);
        this.code = null;
        this.args = new Object[0];
    }

    public String getCode() {
        return code;
    }

    public Object[] getArgs() {
        return args;
    }
}
