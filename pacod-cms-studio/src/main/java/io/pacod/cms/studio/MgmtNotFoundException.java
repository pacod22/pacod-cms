package io.pacod.cms.studio;

import io.pacod.cms.studio.i18n.MgmtCodedMessage;
import io.pacod.cms.studio.i18n.MgmtText;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A console request for a document that is not there.
 *
 * <p>Annotated rather than handled in a {@code @ControllerAdvice} because there is nothing to
 * decide: a missing article, author, category or page is a 404 whichever screen asked for it.
 *
 * <p>Keyed like {@link MgmtOperationException}: the first argument is a message key, and
 * {@code getMessage()} is its English text (the base bundle).
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class MgmtNotFoundException extends RuntimeException implements MgmtCodedMessage {

    private final String code;
    private final transient Object[] args;

    public MgmtNotFoundException(String code, Object... args) {
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
