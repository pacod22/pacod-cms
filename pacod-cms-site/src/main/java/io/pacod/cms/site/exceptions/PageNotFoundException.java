package io.pacod.cms.site.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * No standing page answers this slug.
 *
 * <p>Its own type rather than a widened {@link ArticleNotFoundException}: that one takes an
 * article id and its message says "Article Id=… cannot be found", which is the wrong sentence for
 * {@code /p/terms} and would be the wrong thing to log.
 *
 * <p>Annotated rather than handled in a {@code @ControllerAdvice}, the same shape the studio's
 * {@code MgmtNotFoundException} uses, because there is nothing to decide: a slug nobody has
 * written is a 404 whichever route asked for it. The annotation is also what makes the reader's
 * side right. A handler returning {@code ResponseEntity<Map>} can only be written by a JSON
 * converter, so the browser that asked for this page would get the 404 as a page of raw JSON —
 * and a client sending a strict {@code Accept: text/html} would get no converter at all, which is
 * a {@code 500} Whitelabel page for what is plainly a 404. {@code @ResponseStatus} sends the
 * status through the container's error dispatch instead, where {@code error.html} renders it in
 * the site's own chrome and {@code BasicErrorController} still answers a JSON client with JSON.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PageNotFoundException extends Exception {

    public PageNotFoundException(String path) {
        super(String.format("Page %s cannot be found", path));
    }
}
