package io.pacod.cms.core.services.sitefiles;

import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFiles;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The generated {@code robots.txt}, in this order:
 *
 * <ol>
 *   <li>a group per AI switch that is on ({@link AiCrawlers}): its {@code User-agent} lines and
 *       {@code Disallow: /};</li>
 *   <li>the {@code User-agent: *} group, keeping crawlers off what is not a page: the PDF
 *       renderer's print views, the banner and social redirects and beacons, search results, the
 *       JSON API;</li>
 *   <li>with additions, the operator's lines. They come after the {@code *} group on purpose: a
 *       rule without a {@code User-agent} line of its own belongs to the group before it (RFC 9309),
 *       which is then every crawler's, never an AI group's;</li>
 *   <li>{@code Sitemap:} by an absolute address, as the protocol requires -- unless the sitemap
 *       is off.</li>
 * </ol>
 *
 * The switches apply in both automatic modes; a custom file is served as written.
 */
@Component
public class RobotsBuilder {

    /** What the {@code *} group keeps crawlers off. */
    public static final List<String> DISALLOWED = List.of("/article-print/", "/issue-print/", "/b/", "/social/go/",
            "/social/read/", "/search", "/web/v1/");

    public String build(String origin, SiteFiles files) {
        StringBuilder out = new StringBuilder();
        if (files.isBlockAiTraining()) {
            group(out, AiCrawlers.TRAINING);
        }
        if (files.isBlockAiAnswering()) {
            group(out, AiCrawlers.ANSWERING);
        }
        out.append("User-agent: *\n");
        for (String path : DISALLOWED) {
            out.append("Disallow: ").append(path).append('\n');
        }
        if (files.modeOf(SiteFileKind.ROBOTS) == SiteFileMode.AUTO_PLUS) {
            String additions = files.setting(SiteFileKind.ROBOTS).getAdditions();
            if (additions != null && !additions.isBlank()) {
                out.append('\n').append(additions.strip()).append('\n');
            }
        }
        if (files.isOn(SiteFileKind.SITEMAP)) {
            out.append('\n').append("Sitemap: ").append(origin).append(SiteFileKind.SITEMAP.path()).append('\n');
        }
        return out.toString();
    }

    private static void group(StringBuilder out, List<String> agents) {
        for (String agent : agents) {
            out.append("User-agent: ").append(agent).append('\n');
        }
        out.append("Disallow: /\n\n");
    }
}
