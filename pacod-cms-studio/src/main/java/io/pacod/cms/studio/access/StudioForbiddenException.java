package io.pacod.cms.studio.access;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The signed-in person lacks the permission a screen needs, at the website it is for. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class StudioForbiddenException extends RuntimeException {

    public StudioForbiddenException(String message) {
        super(message);
    }
}
