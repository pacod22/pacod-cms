package io.pacod.cms.studio.design;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.design.Layout;
import io.pacod.cms.core.design.LayoutResolver;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.design.TemplateSetManifest;
import io.pacod.cms.core.repository.LayoutRepository;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.tenant.TenantDirectory;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.site.StudioSites;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Switching a website's template set: first the report -- for each page type, what the website's
 * published layout and draft would leave unrendered on the other set -- then the switch itself.
 * Nothing is removed from any layout: what the new set cannot render stays, flagged, and renders
 * again after a switch back.
 */
@Slf4j
@Service
public class DesignTemplates {

    private final TemplateSetCatalog catalog;
    private final LayoutRepository layouts;
    private final WebsiteRepository websites;
    private final TenantDirectory directory;
    private final StudioSites sites;
    private final DesignLayouts design;

    public DesignTemplates(TemplateSetCatalog catalog, LayoutRepository layouts, WebsiteRepository websites,
                           TenantDirectory directory, StudioSites sites, DesignLayouts design) {
        this.catalog = catalog;
        this.layouts = layouts;
        this.websites = websites;
        this.directory = directory;
        this.sites = sites;
        this.design = design;
    }

    public List<TemplateSetManifest> sets() {
        return catalog.all();
    }

    /** One page type in the report: what would not render, published and in the draft. */
    public record PageReport(PageType pageType, List<LayoutResolver.Issue> published, List<LayoutResolver.Issue> draft) {

        public boolean clean() {
            return published.isEmpty() && draft.isEmpty();
        }
    }

    public List<PageReport> report(String setId) {
        TemplateSetManifest set = catalog.find(setId).orElseThrow(() -> new MgmtNotFoundException("design.template.notFound", setId));
        List<PageReport> out = new ArrayList<>();
        for (PageType type : PageType.values()) {
            Layout doc = layouts.findById(type.name()).orElse(null);
            Map<String, List<io.pacod.cms.core.design.Section>> published = doc == null ? null : doc.getPublished();
            List<LayoutResolver.Issue> pub = published == null ? List.of()
                    : relevant(LayoutResolver.resolve(set, type, published).issues());
            List<LayoutResolver.Issue> draft = doc == null || doc.getDraft() == null ? List.of()
                    : relevant(LayoutResolver.resolve(set, type, doc.getDraft()).issues());
            out.add(new PageReport(type, pub, draft));
        }
        return out;
    }

    /** A missing primary is added back by the resolver; it is not something the switch loses. */
    private static List<LayoutResolver.Issue> relevant(List<LayoutResolver.Issue> issues) {
        return issues.stream().filter(i -> !"primary-missing".equals(i.code())).toList();
    }

    public void apply(String setId) {
        catalog.find(setId).orElseThrow(() -> new MgmtNotFoundException("design.template.notFound", setId));
        String siteId = TenantContext.current().siteId();
        Website website = websites.findById(siteId).orElseThrow(() -> new MgmtNotFoundException("portal.site.notFound", siteId));
        website.setTemplateSet(setId);
        websites.save(website);
        directory.evictAll();
        sites.evict();
        log.info("Website {} now renders with template set {}", siteId, setId);
    }

    public TemplateSetManifest current() {
        return design.currentSet();
    }
}
