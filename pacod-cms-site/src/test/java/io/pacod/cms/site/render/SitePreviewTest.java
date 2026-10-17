package io.pacod.cms.site.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SitePreviewTest {

    @Test
    @DisplayName("the token is read from the query string, wherever it stands in it")
    void tokenInQuery() {
        assertThat(SitePreview.tokenIn("_preview=p1.a.-.1.m")).isEqualTo("p1.a.-.1.m");
        assertThat(SitePreview.tokenIn("q=x&_preview=p1.a.-.1.m&page=2")).isEqualTo("p1.a.-.1.m");
        assertThat(SitePreview.tokenIn("x_preview=1")).isNull();
        assertThat(SitePreview.tokenIn("_preview=")).isNull();
        assertThat(SitePreview.tokenIn(null)).isNull();
    }
}
