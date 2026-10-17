package io.pacod.cms.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PerSiteTest {

    private static Tenant tenant(String id) {
        return new Tenant(id, "org_" + id, "site_" + id, "en", ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("each website's value is drained with that website bound")
    void drainsUnderEachWebsite() {
        PerSite<AtomicInteger> counts = new PerSite<>(AtomicInteger::new);
        TenantContext.runAs(tenant("alpha"), () -> counts.current().orElseThrow().incrementAndGet());
        TenantContext.runAs(tenant("beta"), () -> counts.current().orElseThrow().addAndGet(2));

        List<String> seen = new ArrayList<>();
        counts.forEach(value -> seen.add(TenantContext.current().dbName() + "=" + value.get()));

        assertThat(seen).containsExactlyInAnyOrder("site_alpha=1", "site_beta=2");
    }

    @Test
    @DisplayName("a deleted website's slot is discarded, not flushed -- the write would recreate its database")
    void deletedWebsiteIsSkipped() {
        PerSite<AtomicInteger> counts = new PerSite<>(AtomicInteger::new);
        TenantContext.runAs(tenant("alpha"), () -> counts.current().orElseThrow().incrementAndGet());
        TenantContext.runAs(tenant("gone"), () -> counts.current().orElseThrow().incrementAndGet());
        Set<String> deleted = Set.of("gone");
        counts.skipWhen(deleted::contains);

        List<String> flushed = new ArrayList<>();
        counts.forEach(value -> flushed.add(TenantContext.current().siteId()));
        assertThat(flushed).containsExactly("alpha");
        assertThat(counts.size()).isEqualTo(1);

        flushed.clear();
        counts.forEach(value -> flushed.add(TenantContext.current().siteId()));
        assertThat(flushed).containsExactly("alpha");
    }
}
