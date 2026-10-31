package io.pacod.cms.core.services.sitefiles;

import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.data.SiteSettings;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * What a website answers at one of its crawler files, given its {@link SiteFiles} and its public
 * origin (scheme, host and port, no trailing slash): empty when the file is off, or custom with no
 * text; the stored text when custom; else generated. The one place both applications ask, so the
 * studio's preview and its "start from the generated file" show exactly what the site serves.
 *
 * <p>The origin is the caller's to supply -- the site's {@code SiteOrigin}, the studio the
 * website's {@code baseUrl} or its primary host, never its own. A {@code DataAccessException}
 * propagates.
 */
@Service
public class SiteFileService {

    private final RobotsBuilder robots;
    private final SitemapBuilder sitemap;
    private final LlmsBuilder llms;

    public SiteFileService(RobotsBuilder robots, SitemapBuilder sitemap, LlmsBuilder llms) {
        this.robots = robots;
        this.sitemap = sitemap;
        this.llms = llms;
    }

    /** {@code ownHosts}: the hosts a sitemap addition's absolute address may name. */
    public Optional<String> render(SiteFileKind kind, SiteFiles files, String origin, Set<String> ownHosts) {
        return switch (files.modeOf(kind)) {
            case OFF -> Optional.empty();
            case CUSTOM -> Optional.ofNullable(files.setting(kind).getCustom()).filter(text -> !text.isBlank());
            case AUTO, AUTO_PLUS -> Optional.of(generate(kind, files, origin, ownHosts));
        };
    }

    /**
     * The hosts a sitemap addition's absolute address may name: {@code baseUrl}'s, with and without
     * {@code www.}, and the origin's. One rule for the studio, which checks an addition when it is
     * saved, and the site, which drops one that no longer passes.
     */
    public static Set<String> ownHosts(SiteSettings settings, String origin) {
        Set<String> hosts = new LinkedHashSet<>(settings != null ? settings.ownHosts() : Set.of());
        String host = SiteFileRules.httpHost(origin);
        if (host != null) {
            hosts.add(host);
        }
        return hosts;
    }

    /** The generated file, whatever the mode: what "start from the generated file" copies. */
    public String generate(SiteFileKind kind, SiteFiles files, String origin, Set<String> ownHosts) {
        return switch (kind) {
            case ROBOTS -> robots.build(origin, files);
            case SITEMAP -> sitemap.build(origin, files, ownHosts);
            case LLMS -> llms.index(origin, files);
            case LLMS_FULL -> llms.full(origin, files);
        };
    }
}
