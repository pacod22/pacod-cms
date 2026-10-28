package io.pacod.cms.core.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BannerTest {

    @Test
    @DisplayName("an image banner is always click-tracked: its link goes through /b/{id}")
    void imageBannersAreClickTracked() {
        Banner banner = new Banner(1);
        banner.setType(BannerType.IMAGE);
        banner.setSource("https://cdn.example.com/creative.png");

        assertThat(banner.isClickTracked()).isTrue();
    }

    @Test
    @DisplayName("a same-origin HTML5 banner is click-tracked: site.js can listen inside the frame")
    void sameOriginHtml5IsClickTracked() {
        assertThat(html5("/resources/banners/acme2/index.html").isClickTracked()).isTrue();
    }

    @Test
    @DisplayName("an off-origin HTML5 banner is not: the parent page cannot see into it")
    void offOriginHtml5IsNotClickTracked() {
        assertThat(html5("https://ads.example.com/banner/index.html").isClickTracked()).isFalse();
        assertThat(html5("http://ads.example.com/banner/index.html").isClickTracked()).isFalse();
    }

    @Test
    @DisplayName("a protocol-relative source is off-origin, despite starting with a slash")
    void protocolRelativeHtml5IsNotClickTracked() {
        assertThat(html5("//ads.example.com/banner/index.html").isClickTracked()).isFalse();
    }

    @Test
    @DisplayName("an HTML5 banner with no source is not click-tracked")
    void html5WithoutSourceIsNotClickTracked() {
        assertThat(html5(null).isClickTracked()).isFalse();
    }

    private static Banner html5(String source) {
        Banner banner = new Banner(2);
        banner.setType(BannerType.HTML5);
        banner.setSource(source);
        return banner;
    }

    @Test
    @DisplayName("a booking for sections covers a page of one of them only; one for none covers every page")
    void coversCategories() {
        Banner general = new Banner(1);
        assertThat(general.isTargeted()).isFalse();
        assertThat(general.coversCategories(List.of())).isTrue();
        assertThat(general.coversCategories(null)).isTrue();

        Banner gateway = new Banner(2);
        gateway.setCategories(List.of(1, 7));
        assertThat(gateway.isTargeted()).isTrue();
        assertThat(gateway.coversCategories(List.of(3, 7))).isTrue();
        assertThat(gateway.coversCategories(List.of(3))).isFalse();
        assertThat(gateway.coversCategories(List.of())).as("a page with no section").isFalse();
        assertThat(gateway.coversCategories(null)).isFalse();
    }
}
