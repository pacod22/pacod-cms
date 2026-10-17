package io.pacod.cms.site.controller;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The public application's handling of Spring MVC's own exceptions.
 *
 * <p>It looks empty and is not: extending {@link ResponseEntityExceptionHandler} is the whole
 * job. That base class is what answers an unmapped URL, an unsupported method or an unreadable
 * body with {@code application/problem+json} rather than whatever
 * {@code DefaultHandlerExceptionResolver} would produce — and this application serves JSON
 * under {@code /web/v1/**}, so those responses are part of what a client of it sees, not just
 * chrome. Deleting the class would change them silently.
 *
 * <p>It carries no {@code @ExceptionHandler} for {@code ArticleNotFoundException} or
 * {@code PageNotFoundException}, and should not: the throwers are the two <em>template</em>
 * controllers, so the reader is a browser, and a {@code Map} body only a JSON converter can write
 * is the wrong answer for one — see {@code PageNotFoundException} for the two failures it
 * produces. The exceptions carry {@code @ResponseStatus}, and the template set's
 * {@code error.html} renders them.
 */
@ControllerAdvice
public class ControllerAdvisor extends ResponseEntityExceptionHandler {
}
