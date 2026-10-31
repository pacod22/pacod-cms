package io.pacod.cms.studio.internal;

import io.pacod.cms.studio.access.StudioForbiddenException;
import io.pacod.cms.studio.access.StudioUnavailableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * The internal API's refusals as JSON, {@code {"error", "code"}}: a permission the account lacks
 * ({@code PermissionInterceptor}'s 403), pacod-id unavailable (503), a body that is not JSON (400)
 * -- never the screens' {@code error.html}. First, so no broader advice claims them.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = InternalApiAdvice.class)
public class InternalApiAdvice {

    @ExceptionHandler(StudioForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(StudioForbiddenException e) {
        return refuse(HttpStatus.FORBIDDEN, "forbidden", e.getMessage());
    }

    @ExceptionHandler(StudioUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable(StudioUnavailableException e) {
        return refuse(HttpStatus.SERVICE_UNAVAILABLE, "identity_unavailable", "pacod-id cannot be asked now; try again");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return refuse(HttpStatus.BAD_REQUEST, "bad_request", "The body must be a JSON object");
    }

    private static ResponseEntity<Map<String, String>> refuse(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("error", message, "code", code));
    }
}
