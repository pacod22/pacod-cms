package io.pacod.cms.core.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SocialSourceTest {

    @Test
    @DisplayName("a tag is read by key, name or alias, in any case")
    void tagsAreReadLeniently() {
        assertThat(SocialSource.of("instagram")).isEqualTo(SocialSource.INSTAGRAM);
        assertThat(SocialSource.of("IG")).isEqualTo(SocialSource.INSTAGRAM);
        assertThat(SocialSource.of(" fb ")).isEqualTo(SocialSource.FACEBOOK);
        assertThat(SocialSource.of("twitter")).isEqualTo(SocialSource.X);
        assertThat(SocialSource.of("qr")).isEqualTo(SocialSource.QR);
        assertThat(SocialSource.of("direct")).isEqualTo(SocialSource.DIRECT);
    }

    @Test
    @DisplayName("no tag is null; an unknown tag is OTHER, not a guess")
    void blankAndUnknownDiffer() {
        assertThat(SocialSource.of(null)).isNull();
        assertThat(SocialSource.of("  ")).isNull();
        assertThat(SocialSource.of("newsletter-october")).isEqualTo(SocialSource.OTHER);
    }

    @Test
    @DisplayName("a stored key that no longer names a constant reads as OTHER")
    void storedKeysAreLenient() {
        assertThat(SocialSource.fromStored("TIKTOK")).isEqualTo(SocialSource.TIKTOK);
        assertThat(SocialSource.fromStored("MYSPACE")).isEqualTo(SocialSource.OTHER);
    }

    @Test
    @DisplayName("SOCIAL is a page type the banner form must not offer")
    void socialIsNotBookable() {
        assertThat(BannerPage.SOCIAL.isBookable()).isFalse();
        assertThat(BannerPage.bookable()).doesNotContain(BannerPage.SOCIAL).contains(BannerPage.PAGE);
    }
}
