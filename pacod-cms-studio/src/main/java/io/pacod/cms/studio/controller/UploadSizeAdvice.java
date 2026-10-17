package io.pacod.cms.studio.controller;

import io.pacod.cms.core.services.ImageService;
import io.pacod.cms.studio.BannerCreativeService;
import io.pacod.cms.studio.i18n.MgmtText;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * Turns an upload the container refused into a message on the screen it came from.
 *
 * <h2>Why this is its own advice, and deliberately not scoped</h2>
 * Every other console-wide model attribute lives in {@link MgmtControllerAdvice}, which is scoped
 * to {@code io.pacod.cms.studio.controller} so nothing it adds can reach the public site.
 * This handler <b>cannot</b> live there. Spring's multipart resolver parses the request in
 * {@code DispatcherServlet.doDispatch} <em>before</em> the handler is looked up, so when the parse
 * fails there is no controller yet — and {@code ControllerAdviceBean}'s package predicate answers
 * {@code false} for a null controller type. A scoped advice is therefore never consulted, and the
 * operator gets Tomcat's bare "HTTP Status 413 – Payload Too Large" page. Verified against the
 * running stack, not theorised.
 *
 * <p>Being unscoped is safe here precisely because it handles one exception that only an upload
 * can raise, and the public site has no upload endpoint. The path check below is the belt to that
 * braces: anything outside {@code /mgmt/} gets a plain 413 rather than a redirect into the console.
 *
 * <h2>Why the {@code @Order} is mandatory</h2>
 * Any advice extending {@code ResponseEntityExceptionHandler} -- the public site's
 * {@code ControllerAdvisor} is one, and the studio may grow one -- inherits a {@code handleException}
 * that already claims {@code MaxUploadSizeExceededException} among a long list and answers it with
 * a {@code ProblemDetail} 413. {@code ExceptionHandlerExceptionResolver}
 * consults advice beans <em>in order</em> and takes the first that offers any match, so without
 * {@link Ordered#HIGHEST_PRECEDENCE} the generic handler wins over the specific one purely on scan
 * order — and this class silently does nothing. Being "more specific" does not help across advices;
 * it only breaks ties within one.
 *
 * <h2>What this does and does not reach</h2>
 * It is the residue, not the main mechanism. The multipart limits in {@code application.properties}
 * are set to Caddy's {@code request_body max_size}, so every upload that arrives through the proxy
 * is parsed and refused by {@code ImageService.validate} or {@code BannerCreativeService.validate},
 * which name the rule that was broken. Past those limits Tomcat rejects the request inside the CSRF
 * filter's first {@code getParameter()} call -- below Spring, with its own HTML 413 that no
 * {@code @ControllerAdvice} can intercept. Verified against the running stack; do not add code here
 * expecting to catch that case. What is left for this class is anything that reaches Spring's
 * resolver: a request that bypassed Caddy, or a future endpoint whose handler raises the exception
 * itself.
 */
@Slf4j
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadSizeAdvice {

    private static final String CONSOLE_PREFIX = "/mgmt/";

    /**
     * Read for its byte limit only. That limit is a setting rather than a constant, so this
     * message names what {@code ImageService.validate} would have refused rather than a number
     * written down twice.
     */
    private final ImageService imageService;
    private final MgmtText text;

    public UploadSizeAdvice(ImageService imageService, MgmtText text) {
        this.imageService = imageService;
        this.text = text;
    }

    /**
     * Redirects to the parent of the upload URL, which is the record's own screen for both upload
     * endpoints: {@code /mgmt/banners/5/creative} and {@code /mgmt/articles/42/images} each strip
     * their last segment to it.
     *
     * <p>{@code RedirectAttributes} is not an argument an {@code @ExceptionHandler} can take, so
     * the flash message goes into the output flash map directly.
     *
     * <p>The return type is {@link ModelAndView} rather than {@code Object}, and that is not a
     * style choice. Spring picks a return-value handler from the <em>declared</em> type, and no
     * handler claims {@code Object} — the resolver then gives up and falls through to the default
     * one, which produces exactly the 413 error page this class exists to prevent. Silent, and it
     * looks identical to the handler never being registered.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView handleOversizeUpload(MaxUploadSizeExceededException e,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {

        String uri = request.getRequestURI();
        log.warn("Upload to {} refused before validation: {}", uri, e.getMessage());

        // Compared within the website: under /sites/<id>/ that prefix is the context path.
        String path = uri == null ? null : uri.substring(Math.min(uri.length(), request.getContextPath().length()));
        if (path == null || !path.startsWith(CONSOLE_PREFIX)) {
            // Unreachable while the public site has no multipart endpoint. Here so this
            // unscoped advice can never redirect a non-console request into the console.
            // A cleared ModelAndView is how a handler says "the response is already complete";
            // an empty one that is not cleared would fail rendering for want of a view.
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            ModelAndView handled = new ModelAndView();
            handled.clear();
            return handled;
        }

        FlashMap flash = RequestContextUtils.getOutputFlashMap(request);
        if (flash != null) {
            // The article-image clause is dropped when app.image.file-size-check.enabled is off.
            // This is the response an operator sees at the moment an upload was refused, so
            // naming a limit that no longer exists is worse here than anywhere else on the
            // console: it sends them to shrink a file against a rule nothing is applying, when
            // what actually refused the request was the transport ceiling above.
            long bannerKilobytes = BannerCreativeService.MAX_BANNER_BYTES / 1024;
            String limits = imageService.isFileSizeCheckEnabled()
                    ? text.msg("articles.upload.limits.withImage", bannerKilobytes,
                            imageService.getMaxImageBytes() / 1024)
                    : text.msg("articles.upload.limits.noImage", bannerKilobytes);
            flash.put("error", text.msg("articles.upload.tooLarge") + " " + limits);
        }

        int lastSlash = uri.lastIndexOf('/');
        String parent = lastSlash > CONSOLE_PREFIX.length() ? uri.substring(0, lastSlash) : "/mgmt/articles";
        return new ModelAndView("redirect:" + parent);
    }
}
