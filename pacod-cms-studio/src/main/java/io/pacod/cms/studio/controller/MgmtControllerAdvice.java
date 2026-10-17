package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.core.services.ImageService;
import io.pacod.cms.studio.BannerCreativeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;

/**
 * Model attributes every console screen needs.
 *
 * <p>Scoped to the console's controller package so nothing here reaches the public site.
 */
@Slf4j
@ControllerAdvice(basePackages = "io.pacod.cms.studio.controller")
public class MgmtControllerAdvice {

    /**
     * Read for its limits, never called. The upload rules are settings rather than constants,
     * so the only way for this help text and the rule the server actually applies to stay one fact
     * is to ask the service that applies it.
     */
    private final ImageService imageService;
    private final io.pacod.cms.studio.pdf.GotenbergClient gotenberg;
    private final io.pacod.cms.core.design.PrintTokens printTokens;

    /**
     * The ceiling that applies when this application's own byte cap does not.
     *
     * <p>Read from {@code spring.servlet.multipart.max-file-size} rather than written down,
     * because it is already declared in this module's properties and set to Caddy's
     * {@code request_body max_size} on purpose. With {@code app.image.file-size-check.enabled} off
     * the screens have to state SOMETHING about size: an upload is still refused above this, by
     * Tomcat, inside the CSRF filter's first {@code getParameter()} call -- below Spring, where no
     * {@code @ControllerAdvice} can turn it into a sentence. A hint that simply omitted size would
     * read as "any size", and the operator would learn otherwise from a bare 413.
     *
     * <p>A {@code DataSize} because that is the type the property is written in, and the default
     * matches Spring Boot's own so this cannot fail to start in a context that does not set it.
     */
    @Value("${spring.servlet.multipart.max-file-size:1MB}")
    private DataSize transportLimit;

    public MgmtControllerAdvice(ImageService imageService, io.pacod.cms.studio.pdf.GotenbergClient gotenberg,
                                io.pacod.cms.core.design.PrintTokens printTokens) {
        this.imageService = imageService;
        this.gotenberg = gotenberg;
        this.printTokens = printTokens;
    }

    /** Drives the "signed in as" line and the logout form in the topbar. */
    @ModelAttribute("currentUser")
    public String currentUser(Principal principal) {
        return principal instanceof org.springframework.security.core.Authentication authentication
                ? StudioPrincipals.displayName(authentication)
                : principal == null ? null : principal.getName();
    }

    /**
     * Whether PDF export is set up -- Gotenberg and the shared key both -- so the screens that
     * offer it (the jobs list, the article editor's PDF card) do so only when a job could run.
     */
    @ModelAttribute("pdfEnabled")
    public boolean pdfEnabled() {
        return gotenberg.enabled() && printTokens.enabled();
    }

    /** So the upload help text and the server's actual limits cannot drift apart. */
    @ModelAttribute("imageRules")
    public ImageRules imageRules() {
        return new ImageRules(
                imageService.getRequiredWidth(),
                imageService.getRequiredHeight(),
                imageService.getMaxImageBytes() / 1024,
                imageService.isDimensionCheckEnabled(),
                imageService.isFileSizeCheckEnabled(),
                transportLimit.toKilobytes());
    }

    /**
     * The same guarantee for banner creatives, which have their own limits.
     *
     * <p>Deliberately a second record rather than a reuse of {@link ImageRules}: banner creatives
     * have no required dimensions -- the frame height is the operator's choice through
     * {@code BannerSize} -- so a width and a height here would be two fields nothing could
     * truthfully fill in.
     */
    @ModelAttribute("bannerRules")
    public BannerRules bannerRules() {
        return new BannerRules(
                BannerCreativeService.MAX_BANNER_BYTES / 1024,
                String.join(", ", BannerCreativeService.ALLOWED_CONTENT_TYPES));
    }

    /**
     * The upload rules as a screen has to state them.
     *
     * <p>Two independent switches, so four combinations, and every screen that renders either
     * limit in prose has to branch on the flag beside it. With {@code dimensionCheck} off,
     * "точно 600×400 px" promises a refusal that will not happen and the note on
     * {@code /mgmt/images/{id}} calls a perfectly acceptable upload non-standard; with
     * {@code fileSizeCheck} off, "до 128 KB" and the «над текущото ограничение» note do the same
     * for weight. None of that fails the build -- a stale promise renders, answers 200 and passes
     * every other assertion -- so {@code MgmtScreenRenderTest}'s nested property contexts are what
     * pin it.
     *
     * @param maxKilobytes this application's cap. Meaningful only while {@code fileSizeCheck}.
     * @param dimensionCheck whether {@code width} and {@code height} are a rule or the house style.
     * @param fileSizeCheck whether {@code maxKilobytes} is a rule or merely a recommendation.
     * @param transportKilobytes what Caddy and the servlet container refuse above, whatever the
     *        two flags say. This is what makes "the check is off" an honest sentence rather than
     *        an implied promise of no limit at all -- there is always a limit, and past it the
     *        refusal is a bare 413 with no explanation attached.
     */
    public record ImageRules(int width, int height, long maxKilobytes,
                             boolean dimensionCheck, boolean fileSizeCheck,
                             long transportKilobytes) {
    }

    public record BannerRules(long maxKilobytes, String contentTypes) {
    }
}
