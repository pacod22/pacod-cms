package io.pacod.cms.studio;

import io.pacod.cms.studio.i18n.MgmtCodedMessage;
import io.pacod.cms.studio.i18n.MgmtText;

/**
 * A console operation refused for a reason worth showing the operator -- a category number
 * already in use, a layout id taken, or an image still attached to an article.
 *
 * <p>Unchecked because every caller is a controller that turns the message into a flash
 * message and re-renders the form; there is nothing to recover from programmatically.
 *
 * <p>The first argument is a message key, resolved in the operator's language where it is shown
 * ({@link MgmtText#of(Throwable)}); {@code getMessage()} is the English text, for logs and tests.
 * A string that is not a key is shown as written.
 */
public class MgmtOperationException extends RuntimeException implements MgmtCodedMessage {

    private final String code;
    private final transient Object[] args;

    public MgmtOperationException(String code, Object... args) {
        super(MgmtText.base(code, args));
        this.code = code;
        this.args = args;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Object[] args() {
        return args;
    }
}
