package io.pacod.cms.studio.controller;

import io.pacod.cms.core.archive.ArchiveException;
import io.pacod.cms.core.archive.SiteArchives;
import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.OrganisationPermission;
import io.pacod.cms.studio.access.PlatformScreen;
import io.pacod.cms.studio.access.StudioForbiddenException;
import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.studio.archive.PortalRestore;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.studio.portal.PortalOrganisations;
import io.pacod.cms.studio.portal.PortalWebsites;
import io.pacod.cms.studio.site.StudioPaths;
import io.pacod.cms.studio.site.StudioSiteFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Export and restore ({@link SiteArchives}, {@link PortalRestore}).
 *
 * <ul>
 *   <li>{@code GET /sites/<id>/mgmt/settings/export}: the website as a ZIP, {@code website.manage};
 *       {@code GET /mgmt/organisations/{orgId}/export}: every website of the organisation,
 *       {@code org.manage}. Streamed as written, never held whole.</li>
 *   <li>{@code /mgmt/sites/restore} and {@code /mgmt/organisations/restore}: the screen, and the
 *       archive itself POSTed as the request body ({@code application/zip}, by {@code mgmt.js}) --
 *       not multipart, whose 4 MB ceiling is the uploads' everywhere else; Caddy lets these two
 *       paths alone carry more. The answer is JSON for {@code fetch}: where to go, or why not.</li>
 * </ul>
 * Who may restore is decided where creating is ({@code website.create} in the organisation chosen;
 * the policy and limits for an organisation); the screens are platform screens, with or without a
 * website prefix.
 */
@Slf4j
@Controller
public class MgmtArchiveController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final SiteArchives archives;
    private final PortalRestore restore;
    private final PortalWebsites websites;
    private final PortalOrganisations organisations;
    private final MgmtText text;

    public MgmtArchiveController(SiteArchives archives, PortalRestore restore, PortalWebsites websites,
                                 PortalOrganisations organisations, MgmtText text) {
        this.archives = archives;
        this.restore = restore;
        this.websites = websites;
        this.organisations = organisations;
        this.text = text;
    }

    // -------------------------------------------------------------------------------- export

    @GetMapping("/mgmt/settings/export")
    @StudioPermission(read = "website.manage")
    public void exportSite(HttpServletResponse response) throws IOException {
        Website website = websites.require(TenantContext.current().siteId());
        download(response, "website-" + website.getId());
        archives.exportSite(website, response.getOutputStream());
    }

    @GetMapping(StudioPaths.ORGANISATIONS_SCREEN + "/{orgId}/export")
    @OrganisationPermission(read = "org.manage")
    public void exportOrganisation(@PathVariable String orgId, HttpServletResponse response) throws IOException {
        Organisation organisation = organisations.require(orgId);
        download(response, "organisation-" + organisation.getId());
        archives.exportOrganisation(organisation, response.getOutputStream());
    }

    private static void download(HttpServletResponse response, String name) {
        String file = name + "-" + ZonedDateTime.now(ZoneOffset.UTC).format(STAMP) + ".zip";
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
    }

    // ------------------------------------------------------------------------------- restore

    @GetMapping(StudioPaths.SITES_SCREEN + "/restore")
    @PlatformScreen
    public String restoreSiteScreen(Model model) {
        model.addAttribute("organisations", organisations.creatable());
        model.addAttribute("siteDomain", websites.siteDomain());
        model.addAttribute("maxMegabytes", archives.maxBytes() / (1024 * 1024));
        return "mgmt/site-restore";
    }

    @PostMapping(StudioPaths.SITES_SCREEN + "/restore")
    @PlatformScreen
    @ResponseBody
    public ResponseEntity<Map<String, Object>> restoreSite(@RequestParam(name = "org", defaultValue = "") String organisationId,
                                                           @RequestParam(defaultValue = "") String id,
                                                           @RequestParam(defaultValue = "") String name,
                                                           HttpServletRequest request, HttpServletResponse response) {
        return staged(request, file -> {
            PortalRestore.RestoredSite restored = restore.restoreSite(file, organisationId, id, name);
            List<String> said = new ArrayList<>();
            said.add(text.msg("portal.archive.flash.site", restored.website().getName(), restored.website().getId()));
            restored.notes().forEach(note -> said.add(text.msg(note.key(), note.args())));
            return go(request, response, StudioPaths.siteScreen(restored.website().getId(), "/mgmt/dashboard"),
                    String.join(" ", said), null);
        });
    }

    @GetMapping(StudioPaths.ORGANISATIONS_SCREEN + "/restore")
    @PlatformScreen
    public String restoreOrganisationScreen(Model model) {
        model.addAttribute("allowance", organisations.allowance());
        model.addAttribute("maxMegabytes", archives.maxBytes() / (1024 * 1024));
        return "mgmt/organisation-restore";
    }

    @PostMapping(StudioPaths.ORGANISATIONS_SCREEN + "/restore")
    @PlatformScreen
    @ResponseBody
    public ResponseEntity<Map<String, Object>> restoreOrganisation(@RequestParam(defaultValue = "") String name,
                                                                   HttpServletRequest request, HttpServletResponse response) {
        return staged(request, file -> {
            PortalRestore.RestoredOrganisation restored = restore.restoreOrganisation(file, name);
            List<String> said = new ArrayList<>();
            said.add(text.msg("portal.archive.flash.organisation", restored.organisation().getName(),
                    restored.websites().size()));
            if (!restored.settled()) {
                said.add(text.msg("portal.org.flash.createdPending"));
            }
            restored.notes().forEach(note -> said.add(text.msg(note.key(), note.args())));
            String error = null;
            if (!restored.failures().isEmpty()) {
                List<String> failed = new ArrayList<>();
                restored.failures().forEach(f -> failed.add(f.sourceId() + " (" + text.of(f.error()) + ")"));
                error = text.msg("portal.archive.flash.failed", String.join("; ", failed));
            }
            return go(request, response, StudioPaths.ORGANISATIONS_SCREEN + "/" + restored.organisation().getId(),
                    String.join(" ", said), error);
        });
    }

    private interface Work {
        ResponseEntity<Map<String, Object>> apply(Path file);
    }

    /** The request body staged on disk, the work done, the staged file gone -- whatever happened. */
    private ResponseEntity<Map<String, Object>> staged(HttpServletRequest request, Work work) {
        Path file;
        try {
            file = archives.stage(request.getInputStream());
        } catch (ArchiveException e) {
            return error(HttpStatus.CONTENT_TOO_LARGE, text.of(PortalRestore.coded(e)));
        } catch (IOException e) {
            log.warn("Could not receive an archive: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, text.msg("portal.archive.error.upload"));
        }
        try {
            return work.apply(file);
        } catch (MgmtOperationException e) {
            return error(HttpStatus.UNPROCESSABLE_CONTENT, text.of(e));
        } catch (StudioForbiddenException e) {
            return error(HttpStatus.FORBIDDEN, text.msg("portal.archive.error.forbidden"));
        } finally {
            archives.discard(file);
        }
    }

    /**
     * Where the browser goes next, with what the screen there says: the flash is filed for that
     * address as a redirect would file it, since a {@code fetch} answer is not a redirect.
     */
    private static ResponseEntity<Map<String, Object>> go(HttpServletRequest request, HttpServletResponse response,
                                                          String path, String message, String error) {
        Object root = request.getAttribute(StudioSiteFilter.STUDIO_ROOT);
        String location = (root instanceof String r ? r : request.getContextPath()) + path;
        FlashMap flash = RequestContextUtils.getOutputFlashMap(request);
        flash.put("message", message);
        if (error != null) {
            flash.put("error", error);
        }
        RequestContextUtils.saveOutputFlashMap(location, request, response);
        return ResponseEntity.ok(Map.of("location", location));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
