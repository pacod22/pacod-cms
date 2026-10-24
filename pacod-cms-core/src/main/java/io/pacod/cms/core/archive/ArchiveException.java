package io.pacod.cms.core.archive;

/**
 * Why an archive is refused or could not be restored: a message key of the studio's
 * {@code portal} bundle and its arguments, which the studio turns into its own coded exception.
 * The message is the key and arguments, for logs.
 */
public class ArchiveException extends RuntimeException {

    private final String code;
    private final transient Object[] args;

    public ArchiveException(String code, Object... args) {
        super(code + (args.length == 0 ? "" : " " + java.util.Arrays.toString(args)));
        this.code = code;
        this.args = args;
    }

    public String code() {
        return code;
    }

    public Object[] args() {
        return args.clone();
    }
}
