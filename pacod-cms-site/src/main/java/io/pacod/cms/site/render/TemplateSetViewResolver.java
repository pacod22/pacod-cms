package io.pacod.cms.site.render;

import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;
import org.thymeleaf.dialect.IDialect;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a view with the template set of the website the request is for.
 *
 * <p>Registered as {@code thymeleafViewResolver}, so Boot's own backs off. It implements
 * {@link ViewResolver} directly and caches nothing itself: a caching resolver keys its cache by
 * view name, and would hand the first website's {@code article} to every website. Each set's own
 * {@link ThymeleafViewResolver} caches within that set.
 *
 * <p>The set is the bound tenant's ({@link Tenant#templateSet()}); with no website bound, or a set
 * this installation does not have, it is {@link Tenant#DEFAULT_TEMPLATE_SET} -- a website never
 * fails to render because its set was removed.
 */
@Slf4j
public class TemplateSetViewResolver implements ViewResolver, Ordered, ApplicationContextAware {

    private final Set<String> sets;
    private final java.util.function.Function<String, List<String>> chains;
    private final MessageSource messages;
    private final boolean cacheable;
    private final List<IDialect> dialects;
    private final Map<String, ThymeleafViewResolver> resolvers = new ConcurrentHashMap<>();
    private ApplicationContext context;

    public TemplateSetViewResolver(Set<String> sets, MessageSource messages, boolean cacheable, List<IDialect> dialects) {
        this(sets, List::of, messages, cacheable, dialects);
    }

    /** @param chains where each set's templates are looked up, the set first ({@code TemplateSetCatalog.chain}) */
    public TemplateSetViewResolver(Set<String> sets, java.util.function.Function<String, List<String>> chains,
                                   MessageSource messages, boolean cacheable, List<IDialect> dialects) {
        this.chains = chains;
        this.sets = Set.copyOf(sets);
        this.messages = messages;
        this.cacheable = cacheable;
        this.dialects = List.copyOf(dialects);
        if (!this.sets.contains(Tenant.DEFAULT_TEMPLATE_SET)) {
            throw new IllegalStateException("The default template set '" + Tenant.DEFAULT_TEMPLATE_SET
                    + "' is missing from " + TemplateSets.ROOT);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        this.context = context;
    }

    @Override
    public View resolveViewName(String viewName, Locale locale) throws Exception {
        return resolverFor(currentSet()).resolveViewName(viewName, locale);
    }

    /** The set the bound website renders with, after the fallbacks. */
    public String currentSet() {
        return TenantContext.find().map(Tenant::templateSet).filter(sets::contains)
                .orElse(Tenant.DEFAULT_TEMPLATE_SET);
    }

    public Set<String> sets() {
        return sets;
    }

    private ThymeleafViewResolver resolverFor(String set) {
        return resolvers.computeIfAbsent(set, id -> {
            ThymeleafViewResolver resolver = new ThymeleafViewResolver();
            resolver.setTemplateEngine(TemplateSets.engine(chains.apply(id), messages, cacheable, dialects));
            resolver.setCharacterEncoding("UTF-8");
            resolver.setContentType("text/html;charset=UTF-8");
            resolver.setCache(cacheable);
            resolver.setOrder(getOrder());
            resolver.setApplicationContext(context);
            log.info("Template set '{}' ready (templates from {})", id, chains.apply(id));
            return resolver;
        });
    }

    @Override
    public int getOrder() {
        // Where Boot puts its own Thymeleaf resolver.
        return Ordered.LOWEST_PRECEDENCE - 5;
    }
}
