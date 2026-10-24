package io.pacod.cms.studio.archive;

import io.pacod.cms.core.archive.SiteArchives;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.unit.DataSize;

/**
 * The studio's archive engine ({@link SiteArchives}, core): declared here because only the studio
 * exports and restores. {@code pacod.archive.max-bytes} (2GB) bounds an uploaded archive, staged
 * and uncompressed alike; Caddy's {@code CMS_ARCHIVE_MAX_SIZE} on the two restore paths is kept in
 * step with it.
 */
@Configuration
public class ArchiveConfig {

    @Bean
    public SiteArchives siteArchives(MongoTemplate mongoTemplate, WebsiteRepository websites,
                                     @Value("${pacod.storage.root}") String storageRoot,
                                     @Value("${pacod.archive.max-bytes:2GB}") DataSize maxBytes) {
        return new SiteArchives(mongoTemplate, websites, storageRoot, maxBytes.toBytes());
    }
}
