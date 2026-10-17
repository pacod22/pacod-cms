package io.pacod.cms.core.tenant;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import io.pacod.cms.core.control.data.DeletedWebsite;
import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.DeletedWebsiteRepository;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteRetirementTest {

    @TempDir
    Path storage;

    private WebsiteRepository websites;
    private DomainRepository domains;
    private DeletedWebsiteRepository deleted;
    private TenantDirectory directory;
    private MongoClient mongo;
    private MongoDatabase database;
    private Instant now = Instant.parse("2026-10-16T10:00:00Z");
    private SiteRetirement retirement;

    @BeforeEach
    void setUp() {
        websites = mock(WebsiteRepository.class);
        domains = mock(DomainRepository.class);
        deleted = mock(DeletedWebsiteRepository.class);
        directory = mock(TenantDirectory.class);
        mongo = mock(MongoClient.class);
        database = mock(MongoDatabase.class);
        when(mongo.getDatabase(any())).thenReturn(database);
        when(websites.save(any(Website.class))).thenAnswer(i -> i.getArgument(0));
        retirement = new SiteRetirement(websites, domains, deleted, directory, mongo, storage.toString(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private Website website(WebsiteStatus status) {
        Website website = new Website("alpha", "org_a", "Alpha");
        website.setStatus(status);
        when(websites.findById("alpha")).thenReturn(Optional.of(website));
        return website;
    }

    @Test
    @DisplayName("retiring takes the website offline and releases its domains")
    void retireReleasesDomains() {
        website(WebsiteStatus.ACTIVE);
        Domain a = new Domain("alpha.example.com", "alpha", true);
        when(domains.findByWebsiteId("alpha")).thenReturn(List.of(a));

        Website retired = retirement.retire("alpha");

        assertThat(retired.getStatus()).isEqualTo(WebsiteStatus.DELETING);
        verify(domains).delete(a);
        verify(directory).evictAll();
    }

    @Test
    @DisplayName("erasing refuses a website that was not taken offline first")
    void eraseNeedsRetireFirst() {
        website(WebsiteStatus.ACTIVE);
        assertThatThrownBy(() -> retirement.erase("alpha", "usr_1")).isInstanceOf(IllegalStateException.class);
        verify(database, never()).drop();
        verify(websites, never()).deleteById(any());
    }

    @Test
    @DisplayName("erasing leaves a tombstone, then drops the database, the files and the document")
    void eraseDropsEverything() throws Exception {
        website(WebsiteStatus.DELETING);
        Path folder = Files.createDirectories(storage.resolve("sites/alpha/assets/img/articles"));
        Files.writeString(folder.resolve("a.jpg"), "x");
        Path neighbour = Files.createDirectories(storage.resolve("sites/beta"));

        retirement.erase("alpha", "usr_1");

        InOrder order = inOrder(deleted, database, websites);
        ArgumentCaptor<DeletedWebsite> tombstone = ArgumentCaptor.forClass(DeletedWebsite.class);
        order.verify(deleted).save(tombstone.capture());
        order.verify(database).drop();
        order.verify(websites).deleteById("alpha");
        assertThat(tombstone.getValue().getDbName()).isEqualTo("site_alpha");
        assertThat(tombstone.getValue().getDeletedBy()).isEqualTo("usr_1");
        assertThat(storage.resolve("sites/alpha")).doesNotExist();
        assertThat(neighbour).exists();
    }

    @Test
    @DisplayName("the sweep drops a recently deleted database again, and forgets it once the caches have expired")
    void sweepDropsAgainThenForgets() {
        DeletedWebsite recent = tombstone("alpha", now.minusSeconds(60));
        DeletedWebsite old = tombstone("beta", now.minus(SiteRetirement.SETTLE).minusSeconds(1));
        when(deleted.findAll()).thenReturn(List.of(recent, old));

        assertThat(retirement.sweep()).isEqualTo(2);

        verify(mongo).getDatabase("site_alpha");
        verify(mongo).getDatabase("site_beta");
        verify(deleted, never()).delete(recent);
        verify(deleted).delete(old);
    }

    @Test
    @DisplayName("the sweep never drops the database of a website that exists again")
    void sweepSparesARecreatedWebsite() {
        DeletedWebsite tombstone = tombstone("alpha", now.minusSeconds(30));
        when(deleted.findAll()).thenReturn(List.of(tombstone));
        when(websites.existsById("alpha")).thenReturn(true);

        assertThat(retirement.sweep()).isZero();

        verify(database, never()).drop();
        verify(deleted).delete(tombstone);
    }

    private static DeletedWebsite tombstone(String id, Instant when) {
        Website website = new Website(id, "org_a", id);
        DeletedWebsite tombstone = new DeletedWebsite(website, "usr_1");
        tombstone.setDeletedOn(when);
        return tombstone;
    }
}
