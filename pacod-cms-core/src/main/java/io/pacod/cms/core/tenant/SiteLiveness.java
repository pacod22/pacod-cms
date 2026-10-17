package io.pacod.cms.core.tenant;

import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Whether a website has been deleted, asked by the buffered writers before they flush
 * ({@link PerSite#skipWhen}).
 *
 * <p>Read from the control plane every time, never from {@link TenantDirectory}'s cache: a
 * cached "still there" is exactly what would recreate the database. One lookup per website per
 * flush. A control plane that cannot be read answers "not gone" -- losing a minute of counts to a
 * hiccup would be the worse failure, and the studio drops a deleted website's database once more
 * after the caches expire ({@code DeletedWebsite}).
 */
@Slf4j
@Component
public class SiteLiveness {

    private final WebsiteRepository websites;

    public SiteLiveness(WebsiteRepository websites) {
        this.websites = websites;
    }

    public boolean isGone(String siteId) {
        try {
            return websites.findById(siteId).map(w -> w.getStatus() == WebsiteStatus.DELETING).orElse(true);
        } catch (DataAccessException e) {
            log.debug("Could not check whether website {} exists: {}", siteId, e.getMessage());
            return false;
        }
    }
}
