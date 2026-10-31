package io.pacod.cms.site.services;

import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.repository.SiteFilesRepository;
import io.pacod.cms.core.services.sitefiles.SiteFileService;
import io.pacod.cms.site.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * A website's {@link SiteFiles} and its generated crawler files, cached on the 60 s TTL like
 * everything the studio writes: a saved change is live within a minute.
 *
 * <p><b>Unlike {@code SiteChromeReader}, a read failure propagates.</b> The controller answers 503,
 * which a crawler retries; falling back to the generated file would serve an allow-all
 * {@code robots.txt}, or list addresses the operator left out, to crawlers that keep what they
 * read for a day.
 *
 * <p>A separate bean from the controller: a {@code @Cacheable} called on {@code this} never
 * passes the proxy.
 */
@Service
public class SiteFilesReader {

    private final SiteFilesRepository repository;
    private final SiteFileService files;

    public SiteFilesReader(SiteFilesRepository repository, SiteFileService files) {
        this.repository = repository;
        this.files = files;
    }

    @Cacheable(cacheNames = CacheConfig.SITE, key = "'files'")
    public SiteFiles settings() {
        return repository.findById(SiteFiles.ID).orElseGet(SiteFiles::new);
    }

    /**
     * The generated file -- the sitemap's 45,000 rows, llms-full's bodies -- per kind, origin and
     * settings, so a crawler asking again within the minute costs no query. The settings' hash is
     * in the key, so a change seen in {@link #settings()} is never answered with the file before it.
     */
    @Cacheable(cacheNames = CacheConfig.SITE_FILES,
               key = "#kind.name() + '|' + #origin + '|' + #settings.hashCode()")
    public String generated(SiteFileKind kind, SiteFiles settings, String origin, Set<String> ownHosts) {
        return files.generate(kind, settings, origin, ownHosts);
    }
}
