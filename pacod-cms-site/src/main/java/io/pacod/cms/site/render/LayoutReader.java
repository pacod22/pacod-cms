package io.pacod.cms.site.render;

import io.pacod.cms.core.design.Layout;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.Section;
import io.pacod.cms.core.repository.LayoutRepository;
import io.pacod.cms.site.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * A website's published layout of one page type, cached like the rest of its chrome (60 s, per
 * website). Absent -- no document, nothing published, or a database that cannot be read -- is an
 * empty {@link Stored}, and the page renders with its set's default.
 */
@Slf4j
@Service
public class LayoutReader {

    private final LayoutRepository layouts;

    public LayoutReader(LayoutRepository layouts) {
        this.layouts = layouts;
    }

    /** What is stored for a page type; {@code regions} null means "use the set's default". */
    public record Stored(Map<String, List<Section>> regions) {
    }

    @Cacheable(value = CacheConfig.DESIGN, key = "#pageType.name()")
    public Stored published(PageType pageType) {
        try {
            return new Stored(layouts.findById(pageType.name()).map(Layout::getPublished).orElse(null));
        } catch (DataAccessException e) {
            log.error("Could not read the {} layout; rendering the default: {}", pageType, e.getMessage());
            return new Stored(null);
        }
    }

    /**
     * The draft, for the studio's preview: read on every call and never cached, since the cache
     * is keyed per website alone and a cached draft would reach readers.
     */
    public Stored draft(PageType pageType) {
        try {
            return new Stored(layouts.findById(pageType.name()).map(Layout::getDraft).orElse(null));
        } catch (DataAccessException e) {
            log.error("Could not read the {} draft; previewing the default: {}", pageType, e.getMessage());
            return new Stored(null);
        }
    }
}
