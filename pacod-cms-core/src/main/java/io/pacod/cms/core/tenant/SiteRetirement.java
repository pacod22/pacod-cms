package io.pacod.cms.core.tenant;

import com.mongodb.client.MongoClient;
import io.pacod.cms.core.control.data.DeletedWebsite;
import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.DeletedWebsiteRepository;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Deletes websites, in an order that leaves nothing behind for the public site to write back.
 *
 * <ol>
 *   <li>{@link #retire}: the website becomes {@link WebsiteStatus#DELETING} and its domains are
 *       released. It stops answering -- on the public site once its host lookup expires (a
 *       minute) -- and pacod-id's scope sync leaves it alone.</li>
 *   <li>The caller removes the website's scope from pacod-id, with every role and invitation
 *       there. That is the studio's step, between the two here: if it fails, the website stays
 *       {@code DELETING}, and deleting it again carries on. A new website of the same id must never
 *       find the old one's roles waiting for it.</li>
 *   <li>{@link #erase}: its database is dropped, its files deleted, a {@link DeletedWebsite}
 *       written, and the website document removed.</li>
 *   <li>{@link #sweep}, run again once every cache that knew the website has expired, drops the
 *       database once more: MongoDB recreates a database on the first write, and the public site
 *       may have written one in that minute. The tombstone also refuses the id to a new website
 *       until then.</li>
 * </ol>
 */
@Slf4j
@Component
public class SiteRetirement {

    /** Longer than every cache the public site keeps of a website (60 s) and its slowest flush. */
    public static final Duration SETTLE = Duration.ofMinutes(3);

    private final WebsiteRepository websites;
    private final DomainRepository domains;
    private final DeletedWebsiteRepository deleted;
    private final TenantDirectory directory;
    private final MongoClient mongo;
    private final String storageRoot;
    private final Clock clock;

    @Autowired
    public SiteRetirement(WebsiteRepository websites, DomainRepository domains, DeletedWebsiteRepository deleted,
                          TenantDirectory directory, MongoClient mongo,
                          @Value("${pacod.storage.root:}") String storageRoot) {
        this(websites, domains, deleted, directory, mongo, storageRoot, Clock.systemUTC());
    }

    SiteRetirement(WebsiteRepository websites, DomainRepository domains, DeletedWebsiteRepository deleted,
                   TenantDirectory directory, MongoClient mongo, String storageRoot, Clock clock) {
        this.websites = websites;
        this.domains = domains;
        this.deleted = deleted;
        this.directory = directory;
        this.mongo = mongo;
        this.storageRoot = storageRoot == null ? "" : storageRoot.strip();
        this.clock = clock;
    }

    /** Takes the website offline and releases its domains; safe to repeat. */
    public Website retire(String siteId) {
        Website website = websites.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("No website " + siteId));
        if (website.getStatus() != WebsiteStatus.DELETING) {
            website.setStatus(WebsiteStatus.DELETING);
            website = websites.save(website);
        }
        for (Domain domain : domains.findByWebsiteId(siteId)) {
            domains.delete(domain);
        }
        directory.evictAll();
        log.info("Website {} is being deleted: offline, domains released", siteId);
        return website;
    }

    /**
     * Drops the database, deletes the files and the website document, and leaves a tombstone.
     * Only for a website {@link #retire}d first.
     */
    public void erase(String siteId, String deletedBy) {
        Website website = websites.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("No website " + siteId));
        if (website.getStatus() != WebsiteStatus.DELETING) {
            throw new IllegalStateException("Website " + siteId + " has not been taken offline first");
        }
        deleted.save(new DeletedWebsite(website, deletedBy));
        mongo.getDatabase(website.getDbName()).drop();
        deleteFiles(siteId);
        websites.deleteById(siteId);
        directory.evictAll();
        log.info("Deleted website {}: database {} dropped, files removed", siteId, website.getDbName());
    }

    /**
     * Drops once more the database of every website deleted within {@link #SETTLE}, and forgets the
     * older ones. A tombstone whose id belongs to a website again is forgotten without a drop.
     *
     * @return how many databases it dropped
     */
    public int sweep() {
        int dropped = 0;
        Instant now = clock.instant();
        for (DeletedWebsite tombstone : deleted.findAll()) {
            try {
                if (websites.existsById(tombstone.getId())) {
                    deleted.delete(tombstone);
                    continue;
                }
                mongo.getDatabase(tombstone.getDbName()).drop();
                dropped++;
                if (tombstone.getDeletedOn() == null || tombstone.getDeletedOn().plus(SETTLE).isBefore(now)) {
                    deleted.delete(tombstone);
                }
            } catch (RuntimeException e) {
                log.error("Could not sweep deleted website {}: {}", tombstone.getId(), e.getMessage());
            }
        }
        return dropped;
    }

    private void deleteFiles(String siteId) {
        if (storageRoot.isEmpty()) {
            return;
        }
        Path sites = Path.of(storageRoot, "sites").toAbsolutePath().normalize();
        Path folder = sites.resolve(siteId).normalize();
        if (!folder.getParent().equals(sites) || !Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the files of website " + siteId, e);
        }
    }
}
