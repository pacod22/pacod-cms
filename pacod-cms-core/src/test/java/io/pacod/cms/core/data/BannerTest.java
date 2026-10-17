package io.pacod.cms.core.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
