package io.pacod.cms.site.render;

import lombok.extern.slf4j.Slf4j;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.design.TemplateSetManifest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The public site's view resolution: one engine per template set (see {@link TemplateSets}).
 *
 * <p>The sets are the folders under {@code templates/sets/} on the classpath, found once at start.
 * Boot's own error page is off ({@code spring.web.error.whitelabel.enabled=false} -- Boot 4's
 * name; {@code server.error.whitelabel.enabled} is silently ignored): Boot looks for
 * {@code templates/error.html} at the classpath root, finds none since every set has its own,
 * and would otherwise answer every error with its whitelabel view instead of the set's page.
 * {@code HostResolutionIT} sees it first.
 */
@Slf4j
@Configuration
public class TemplateSetConfig {

    private static final Pattern SET_FOLDER = Pattern.compile("/templates/sets/([^/]+)/");

    /**
     * Only sets with both halves: templates here and a manifest in core's {@code design/sets/}. A
     * set with one half would render its templates with another set's layouts, or the reverse; it
     * is left out, said at ERROR, and its websites render with the default.
     */
    @Bean(name = "thymeleafViewResolver")
    public TemplateSetViewResolver thymeleafViewResolver(MessageSource messageSource, TemplateSetCatalog catalog,
                                                         @Value("${spring.thymeleaf.cache:true}") boolean cacheable,
                                                         ObjectProvider<org.thymeleaf.dialect.IDialect> dialects) {
        TreeSet<String> templates = installedSets();
        TreeSet<String> sets = new TreeSet<>();
        for (String id : templates) {
            if (catalog.find(id).isPresent() && templates.containsAll(catalog.chain(id))) {
                sets.add(id);
            } else {
                log.error("Template set '{}' has templates but no manifest (design/sets/{}.yaml), or extends a set "
                        + "without templates; not used", id, id);
            }
        }
        catalog.all().stream().map(TemplateSetManifest::id).filter(id -> !templates.contains(id))
                .forEach(id -> log.error("Template set '{}' has a manifest but no templates (templates/sets/{}/); not used", id, id));
        log.info("Template sets: {}", sets);
        return new TemplateSetViewResolver(sets, catalog::chain, messageSource, cacheable, dialects.orderedStream().toList());
    }

    /** Every folder under {@code templates/sets/} holding a template, except {@value TemplateSets#BASE}. */
    static TreeSet<String> installedSets() {
        TreeSet<String> sets = new TreeSet<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:" + TemplateSets.ROOT + "*/*.html")) {
                Matcher m = SET_FOLDER.matcher(r.getURL().toString());
                if (m.find() && !TemplateSets.BASE.equals(m.group(1)) && TemplateSets.ID.matcher(m.group(1)).matches()) {
                    sets.add(m.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sets;
    }
}
