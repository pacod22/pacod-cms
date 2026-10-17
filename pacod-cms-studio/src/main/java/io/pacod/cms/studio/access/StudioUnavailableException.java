package io.pacod.cms.studio.access;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** pacod-id's copy is missing or too old to decide what anyone may do. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class StudioUnavailableException extends RuntimeException {

    public StudioUnavailableException(String message) {
        super(message);
    }
}
