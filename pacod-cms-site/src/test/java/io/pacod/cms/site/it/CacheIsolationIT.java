package io.pacod.cms.site.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cached value belongs to the website it was read for. The standing pages are cached under one
 * key, {@code 'all'}, in every website -- exactly the key two websites would collide on if the
 * cache were not scoped.
 */
class CacheIsolationIT extends TwoWebsitesIT {

    @RepeatedTest(3)
    @DisplayName("interleaved renders of one cached page never show the other website's copy")
    void cachedPagesStayApart() {
        assertThat(get(ALPHA_HOST, "/p/about").body()).contains("About Alpha Daily").doesNotContain("Beta Weekly");
        assertThat(get(BETA_HOST, "/p/about").body()).contains("About Beta Weekly").doesNotContain("Alpha Daily");
        assertThat(get(ALPHA_HOST, "/p/about").body()).contains("About Alpha Daily");
    }
}
