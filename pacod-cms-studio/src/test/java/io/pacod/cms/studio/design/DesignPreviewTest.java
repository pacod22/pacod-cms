package io.pacod.cms.studio.design;

import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.design.PreviewTokens;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.studio.site.StudioSites;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where the studio's "Preview" and "View page" send the operator: the website's own origin, never
 * the studio's path under {@code /sites/<id>}, which on the studio's host is a 404.
 */
class DesignPreviewTest {

    private static final String SECRET = "a-test-secret-of-at-least-thirty-two-characters";

    private DesignPreview preview(String secret, String publicUrl) {
        StudioSites sites = mock(StudioSites.class);
        when(sites.current()).thenReturn(Optional.of(new StudioSites.SiteView("test", "org", "Test",
                WebsiteStatus.ACTIVE, "en", publicUrl == null ? List.of() : List.of("www.example.com"), publicUrl)));
        return new DesignPreview(new PreviewTokens(secret), sites, mock(TemplateSetCatalog.class),
                mock(ArticleRepository.class), mock(CategoryRepository.class), mock(PageRepository.class));
    }

    @Test
    @DisplayName("a page opens on the website's origin, in the language asked for, with a preview token")
    void pageOpensOnTheWebsite() {
        String url = preview(SECRET, "https://www.example.com").pageUrl("about", "de").orElseThrow();

        assertThat(url).startsWith("https://www.example.com/p/about?lang=de&_preview=p1.test.");
    }

    @Test
    @DisplayName("without the shared secret it is the public address; a language that is not a code is left out")
    void withoutASecretItIsThePublicAddress() {
        assertThat(preview("", "https://www.example.com").pageUrl("about", "x\"><script>"))
                .contains("https://www.example.com/p/about");
        assertThat(preview("", "https://www.example.com").articleUrl(7, "bg"))
                .contains("https://www.example.com/article/7?lang=bg");
    }

    @Test
    @DisplayName("a website without a host has nowhere to show anything")
    void noHostNoAddress() {
        assertThat(preview(SECRET, null).pageUrl("about", "en")).isEmpty();
        assertThat(preview(SECRET, null).articleUrl(7, "en")).isEmpty();
    }
}
