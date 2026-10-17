package io.pacod.cms.site.render;

import org.springframework.context.MessageSource;
import org.thymeleaf.dialect.IDialect;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where a template set lives, and the one way its engine is built -- by the application and by the
 * render tests alike, so the tests never render with an engine production does not have.
 *
 * <p>A set is a folder, {@code templates/sets/<id>/}, with the views a website renders (the page
 * types, the error page, their fragments). A view or fragment the set lacks is looked up in the set
 * it extends, if any, then in {@value #BASE}, which holds what every set shares. Each set gets an engine of its own:
 * Thymeleaf caches parsed templates per engine, by name, so two sets in one engine would share one
 * {@code article} -- whichever website asked first.
 */
public final class TemplateSets {

    public static final String ROOT = "templates/sets/";
    public static final String BASE = "_base";

    /** A set id: what may name a folder. */
    public static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,39}");

    private TemplateSets() {
    }

    /**
     * The engine for {@code set}: its own folder first, then {@value #BASE}.
     *
     * @param messages the site's chrome messages ({@code ThymeleafConfig.messageSource()})
     * @param cacheable {@code spring.thymeleaf.cache}: off in development, so an edited template shows
     * @param dialects  any extra dialects the application registers
     */
    public static SpringTemplateEngine engine(String set, MessageSource messages, boolean cacheable,
                                              Collection<IDialect> dialects) {
        return engine(java.util.List.of(set), messages, cacheable, dialects);
    }

    /**
     * The engine for a set that extends others: {@code chain} is the set first, then what it
     * extends, in order ({@code TemplateSetCatalog.chain}); {@value #BASE} comes after all of them.
     */
    public static SpringTemplateEngine engine(java.util.List<String> chain, MessageSource messages, boolean cacheable,
                                              Collection<IDialect> dialects) {
        Set<org.thymeleaf.templateresolver.ITemplateResolver> resolvers = new LinkedHashSet<>();
        int order = 1;
        for (String set : chain) {
            if (set == null || !ID.matcher(set).matches()) {
                throw new IllegalArgumentException("Not a template set id: " + set);
            }
            resolvers.add(resolver(ROOT + set + "/", order++, cacheable));
        }
        resolvers.add(resolver(ROOT + BASE + "/", order, cacheable));

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolvers(resolvers);
        engine.setTemplateEngineMessageSource(messages);
        engine.setEnableSpringELCompiler(true);
        for (IDialect dialect : dialects) {
            engine.addDialect(dialect);
        }
        return engine;
    }

    private static ClassLoaderTemplateResolver resolver(String prefix, int order, boolean cacheable) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(prefix);
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(cacheable);
        resolver.setOrder(order);
        // So a view the set lacks falls through to the next resolver instead of failing there.
        resolver.setCheckExistence(true);
        return resolver;
    }
}
