package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.site.services.BannerImpressionCounter;
import io.pacod.cms.site.services.BannerReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The click redirect and the two beacons beside it.
 *
 * <p>The redirect takes an id from the URL and puts a stored string into a {@code Location}
 * header, so the interesting cases are the ones where that string is not what the studio would
 * have allowed. The beacons take input from any browser, so the interesting cases are the ones
 * that must count nothing.
 */
class BannerClickControllerTest {

    private BannerReader reader;
    private BannerImpressionCounter counter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reader = mock(BannerReader.class);
        counter = mock(BannerImpressionCounter.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new BannerClickController(reader, counter)).build();
    }

    private static Banner imageBanner(int id, String target) {
        Banner banner = new Banner(id);
        banner.setName("Advertiser " + id);
        banner.setType(BannerType.IMAGE);
        banner.setSource("/resources/banners/" + id + "-creative.gif");
        banner.setTargetUrl(target);
        banner.setPlacement(BannerPlacement.SIDEBAR);
        banner.setSize(BannerSize.AUTO);
        return banner;
    }

    private static Banner html5Banner(int id, String source) {
        Banner banner = new Banner(id);
        banner.setName("Advertiser " + id);
        banner.setType(BannerType.HTML5);
        banner.setSource(source);
        banner.setPlacement(BannerPlacement.SIDEBAR);
        banner.setSize(BannerSize.SQUARE);
        return banner;
    }

    @Test
    @DisplayName("A click redirects to the advertiser and is counted")
    void redirectsAndCounts() throws Exception {
        when(reader.findAll()).thenReturn(List.of(imageBanner(5, "https://partner.example.net/")));

        mockMvc.perform(get("/b/5"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://partner.example.net/"));

        verify(counter).recordClick(5);
    }

    @Test
    @DisplayName("An unknown banner is a 404, not a redirect to nowhere")
    void unknownBannerIsNotFound() throws Exception {
        when(reader.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/b/5")).andExpect(status().isNotFound());

        verify(counter, never()).recordClick(any());
    }

    /**
     * The studio refuses a non-http target on the way in. This is the second refusal, and the one
     * that matters: a document written any other way, or edited by hand in mongosh, would
     * otherwise turn the endpoint into an open redirect -- and a {@code javascript:} URL into
     * stored XSS.
     */
    @Test
    @DisplayName("A target that is not http(s) is refused rather than reflected")
    void refusesNonHttpTargets() throws Exception {
        for (String hostile : List.of("javascript:alert(1)", "data:text/html,<script>", "/mgmt/users", "")) {
            when(reader.findAll()).thenReturn(List.of(imageBanner(5, hostile)));

            mockMvc.perform(get("/b/5")).andExpect(status().isNotFound());
        }

        when(reader.findAll()).thenReturn(List.of(imageBanner(5, null)));
        mockMvc.perform(get("/b/5")).andExpect(status().isNotFound());

        verify(counter, never()).recordClick(any());
    }

    /**
     * Shared with {@code MgmtBannerService} so that what the studio refuses on save is exactly
     * what the redirect refuses on the way out. Two copies of this rule would eventually disagree,
     * and the disagreement would be an open redirect.
     */
    @Test
    @DisplayName("The URL rule is one rule, and it is case-insensitive on the scheme")
    void urlRuleIsSharedAndCaseInsensitive() {
        assertThat(BannerClickController.isHttpUrl("https://example.com/")).isTrue();
        assertThat(BannerClickController.isHttpUrl("http://example.com/")).isTrue();
        assertThat(BannerClickController.isHttpUrl("HTTPS://example.com/")).isTrue();
        assertThat(BannerClickController.isHttpUrl("  https://example.com/  ")).isTrue();

        assertThat(BannerClickController.isHttpUrl("javascript:alert(1)")).isFalse();
        assertThat(BannerClickController.isHttpUrl("//example.com/")).isFalse();
        assertThat(BannerClickController.isHttpUrl("ftp://example.com/")).isFalse();
        assertThat(BannerClickController.isHttpUrl("")).isFalse();
        assertThat(BannerClickController.isHttpUrl(null)).isFalse();
    }

    /* ------------------------------------------------------------------ HTML5 clicks */

    /**
     * An HTML5 booking's clicks arrive by beacon, so one carrying a stray {@code targetUrl} must
     * not also redirect and count here: that would count every click twice.
     */
    @Test
    @DisplayName("The redirect refuses an HTML5 booking, even one with a usable target")
    void redirectRefusesHtml5() throws Exception {
        Banner banner = html5Banner(7, "/resources/banners/acme2/index.html");
        banner.setTargetUrl("https://partner.example.net/");
        when(reader.findAll()).thenReturn(List.of(banner));

        mockMvc.perform(get("/b/7")).andExpect(status().isNotFound());

        verify(counter, never()).recordClick(any());
    }

    @Test
    @DisplayName("A click beacon from a running same-origin HTML5 booking is counted")
    void html5ClickBeaconCounts() throws Exception {
        when(reader.findAll()).thenReturn(List.of(html5Banner(7, "/resources/banners/acme2/index.html")));

        mockMvc.perform(post("/b/7/c").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isNoContent());

        verify(counter).recordClick(7);
    }

    @Test
    @DisplayName("A click beacon from a browser that sends no Sec-Fetch-Site is still counted")
    void html5ClickBeaconWithoutFetchMetadataCounts() throws Exception {
        when(reader.findAll()).thenReturn(List.of(html5Banner(7, "/resources/banners/acme2/index.html")));

        mockMvc.perform(post("/b/7/c")).andExpect(status().isNoContent());

        verify(counter).recordClick(7);
    }

    /** Image clicks are the redirect's; accepting them here too would count each one twice. */
    @Test
    @DisplayName("A click beacon for an image booking counts nothing")
    void clickBeaconRefusesImageBookings() throws Exception {
        when(reader.findAll()).thenReturn(List.of(imageBanner(5, "https://partner.example.net/")));

        mockMvc.perform(post("/b/5/c")).andExpect(status().isNoContent());

        verify(counter, never()).recordClick(any());
    }

    @Test
    @DisplayName("A click beacon for an off-origin, paused, expired or unknown booking counts nothing")
    void clickBeaconRefusesWhatCannotHaveBeenClicked() throws Exception {
        Banner offOrigin = html5Banner(1, "https://ads.example.com/banner/index.html");
        Banner paused = html5Banner(2, "/resources/banners/a/index.html");
        paused.setActive(false);
        Banner expired = html5Banner(3, "/resources/banners/b/index.html");
        expired.setActiveTo(LocalDateTime.now().minusDays(1));
        when(reader.findAll()).thenReturn(List.of(offOrigin, paused, expired));

        for (int id : new int[]{1, 2, 3, 99}) {
            mockMvc.perform(post("/b/" + id + "/c")).andExpect(status().isNoContent());
        }

        verify(counter, never()).recordClick(any());
    }

    /** Another website must not be able to beacon into these numbers from its own readers. */
    @Test
    @DisplayName("A cross-site click beacon counts nothing")
    void crossSiteClickBeaconCountsNothing() throws Exception {
        when(reader.findAll()).thenReturn(List.of(html5Banner(7, "/resources/banners/acme2/index.html")));

        mockMvc.perform(post("/b/7/c").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/b/7/c").header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isNoContent());

        verify(counter, never()).recordClick(any());
    }

    /* ------------------------------------------------------------------ viewable impressions */

    @Test
    @DisplayName("A viewable beacon counts each running id once, skipping junk and unknown ids")
    void viewableBeaconCountsEachRunningIdOnce() throws Exception {
        when(reader.findAll()).thenReturn(List.of(
                imageBanner(3, "https://partner.example.net/"),
                html5Banner(7, "/resources/banners/acme2/index.html")));

        mockMvc.perform(post("/b/v").contentType(MediaType.TEXT_PLAIN).content("3,7,7,abc,999")
                        .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isNoContent());

        verify(counter).recordViewable(3);
        verify(counter).recordViewable(7);
        verify(counter, never()).recordViewable(999);
    }

    @Test
    @DisplayName("A viewable beacon for a paused booking counts nothing")
    void viewableBeaconSkipsPausedBookings() throws Exception {
        Banner paused = imageBanner(3, "https://partner.example.net/");
        paused.setActive(false);
        when(reader.findAll()).thenReturn(List.of(paused));

        mockMvc.perform(post("/b/v").contentType(MediaType.TEXT_PLAIN).content("3"))
                .andExpect(status().isNoContent());

        verify(counter, never()).recordViewable(any());
    }

    @Test
    @DisplayName("A cross-site, empty or oversized viewable beacon counts nothing")
    void viewableBeaconRefusals() throws Exception {
        when(reader.findAll()).thenReturn(List.of(imageBanner(3, "https://partner.example.net/")));

        mockMvc.perform(post("/b/v").contentType(MediaType.TEXT_PLAIN).content("3")
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/b/v")).andExpect(status().isNoContent());
        mockMvc.perform(post("/b/v").contentType(MediaType.TEXT_PLAIN)
                        .content("3," + "0,".repeat(BannerClickController.MAX_VIEWABLE_BODY)))
                .andExpect(status().isNoContent());

        verifyNoInteractions(counter);
    }

    @Test
    @DisplayName("Ids are parsed distinct, in order, and capped")
    void idsAreParsedDistinctAndCapped() {
        assertThat(BannerClickController.parseIds(" 3, 7,7 ,x,,-1")).containsExactly(3, 7, -1);

        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            many.append(i).append(',');
        }
        assertThat(BannerClickController.parseIds(many.toString()))
                .hasSize(BannerClickController.MAX_VIEWABLE_IDS)
                .startsWith(1, 2, 3);
    }
}
