package io.pacod.cms.site.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No published journal issue answers this id: {@link PageNotFoundException}'s shape and reason. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class IssueNotFoundException extends Exception {

    public IssueNotFoundException(Object id) {
        super(String.format("Issue %s cannot be found", id));
    }
}
