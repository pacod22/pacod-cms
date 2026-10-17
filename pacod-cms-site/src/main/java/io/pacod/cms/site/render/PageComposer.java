package io.pacod.cms.site.render;

import io.pacod.cms.core.design.LayoutResolver;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.SectionType;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.design.TemplateSetManifest;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.site.data.LandingSlot;
import io.pacod.cms.site.data.ListItem;
import io.pacod.cms.site.services.CategoryWidgetReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a page type's layout into what its template renders: the website's published layout (or
 * its set's default), resolved against the set ({@link LayoutResolver} -- what the set cannot
 * render is left out), each section's data looked up here.
 *
 * <p>Every lookup is isolated: a section whose data cannot be read is left out and logged, and the
 * page renders. A fragment never queries anything.
 */
@Slf4j
@Service
public class PageComposer {

    /** The synthetic most-read category {@code WebArticleService} recognises. */
    static final int MOST_READ = 100001;

    private final TemplateSetCatalog catalog;
    private final LayoutReader layouts;
    private final CategoryWidgetReader widgets;
    private final MessageSource messages;

    public PageComposer(TemplateSetCatalog catalog, LayoutReader layouts, CategoryWidgetReader widgets,
                        MessageSource messages) {
        this.catalog = catalog;
        this.layouts = layouts;
        this.widgets = widgets;
        this.messages = messages;
    }

    public Regions compose(PageType pageType, String lang) {
        TemplateSetManifest set = catalog.orDefault(TenantContext.find().map(Tenant::templateSet).orElse(null));
        LayoutResolver.Resolved resolved = LayoutResolver.resolve(set, pageType, (SitePreview.current().isPresent() ? layouts.draft(pageType) : layouts.published(pageType)).regions());
        if (!resolved.issues().isEmpty() && log.isDebugEnabled()) {
            log.debug("{} layout on set {}: not rendered {}", pageType, set.id(), resolved.issues());
        }
        Map<String, List<RenderedSection>> out = new LinkedHashMap<>();
        resolved.regions().forEach((region, sections) -> {
            List<RenderedSection> rendered = new ArrayList<>();
            for (LayoutResolver.ResolvedSection section : sections) {
                try {
                    rendered.add(new RenderedSection(section.id(), section.type().id(), section.type().fragment(),
                            section.settings(), data(section, lang)));
                } catch (RuntimeException e) {
                    log.warn("Section {} ({}) of the {} layout could not be prepared; leaving it out: {}",
                            section.id(), section.type().id(), pageType, e.toString());
                }
            }
            out.put(region, rendered);
        });
        return new Regions(out);
    }

    private Object data(LayoutResolver.ResolvedSection section, String lang) {
        if (section.type() != SectionType.ARTICLE_LIST) {
            return null;
        }
        Map<String, Object> s = section.settings();
        int count = (Integer) s.get("count");
        int offset = (Integer) s.get("offset");
        String source = (String) s.get("source");
        LandingSlot slot = switch (source) {
            case "category" -> widgets.read((Integer) s.get("category"), offset + count, lang);
            case "most-read" -> widgets.read(MOST_READ, offset + count, lang);
            default -> widgets.readLatest(offset + count, lang);
        };
        if (slot == null) {
            slot = LandingSlot.EMPTY;
        }
        List<ListItem> items = slot.items();
        List<ListItem> window = offset >= items.size() ? List.of()
                : new ArrayList<>(items.subList(offset, Math.min(items.size(), offset + count)));
        String heading = (String) s.get("heading");
        if (heading == null && "most-read".equals(source)) {
            // In the language the page is in: the chrome's locale is not set until after the controller.
            heading = messages.getMessage("section.mostRead", null, "", Locale.forLanguageTag(lang == null ? "en" : lang));
        }
        return new LandingSlot(slot.categoryId(), heading != null ? heading : slot.name(), window);
    }
}
