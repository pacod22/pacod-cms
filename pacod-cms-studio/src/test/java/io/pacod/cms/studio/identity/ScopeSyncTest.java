package io.pacod.cms.studio.identity;

import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.OrganisationRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScopeSyncTest {

    private IdClient client;
    private AuthzFeed feed;
    private OrganisationRepository organisations;
    private WebsiteRepository websites;
    private final List<String> puts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = mock(IdClient.class);
        feed = mock(AuthzFeed.class);
        organisations = mock(OrganisationRepository.class);
        websites = mock(WebsiteRepository.class);
        when(feed.isReady()).thenReturn(true);
        when(client.put(anyString(), any())).thenAnswer(invocation -> {
            puts.add(invocation.getArgument(0) + " " + invocation.getArgument(1));
            return tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        });
        when(organisations.findAll()).thenReturn(List.of(new Organisation("org_a", "Acme")));
        when(websites.findAll()).thenReturn(List.of(new Website("news", "org_a", "Acme News")));
    }

    private static AuthzFeed.Scope org(String id, String name) {
        return new AuthzFeed.Scope("organisation", id, null, name, List.of("cms"), List.of());
    }

    @Test
    @DisplayName("what pacod-id does not know is registered, organisation first, slug and name every time")
    void registersWhatIsMissing() {
        when(feed.scopes()).thenReturn(List.of());

        assertThat(new ScopeSync(client, feed, organisations, websites).reconcile()).isEqualTo(2);

        assertThat(puts).containsExactly(
                "/internal/v1/organisations/org_a " + Map.of("slug", "org_a", "display_name", "Acme"),
                "/internal/v1/scopes/cms.website/news " + Map.of("parent", "organisation:org_a", "display_name", "Acme News"));
    }

    @Test
    @DisplayName("what pacod-id already has, under the same name and parent, is left alone")
    void leavesWhatMatches() {
        when(feed.scopes()).thenReturn(List.of(org("org_a", "Acme"),
                new AuthzFeed.Scope("cms.website", "news", "organisation:org_a", "Acme News", List.of(), List.of())));

        assertThat(new ScopeSync(client, feed, organisations, websites).reconcile()).isZero();
        assertThat(puts).isEmpty();
    }

    @Test
    @DisplayName("a renamed website, or an organisation registered only by another product, is put again")
    void putsWhatDiffers() {
        when(feed.scopes()).thenReturn(List.of(
                new AuthzFeed.Scope("organisation", "org_a", null, "Acme", List.of("gw"), List.of()),
                new AuthzFeed.Scope("cms.website", "news", "organisation:org_a", "Old name", List.of(), List.of())));

        assertThat(new ScopeSync(client, feed, organisations, websites).reconcile()).isEqualTo(2);
    }

    @Test
    @DisplayName("nothing happens before the feed has its first snapshot")
    void waitsForTheFeed() {
        when(feed.isReady()).thenReturn(false);
        assertThat(new ScopeSync(client, feed, organisations, websites).reconcile()).isZero();
    }
}
