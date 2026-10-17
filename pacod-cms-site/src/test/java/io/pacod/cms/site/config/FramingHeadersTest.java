package io.pacod.cms.site.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FramingHeadersTest {

    private static MockHttpServletResponse write(FramingHeaders headers, String path) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        headers.writeHeaders(new MockHttpServletRequest("GET", path), response);
        return response;
    }

    @Test
    @DisplayName("pages: the website alone, in both headers")
    void pages() {
        MockHttpServletResponse response = write(new FramingHeaders("https://studio.example.org"), "/article/5");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("frame-ancestors 'self'");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
    }

    @Test
    @DisplayName("banner folders: the website and the studio, and no X-Frame-Options to refuse the studio")
    void bannerFolders() {
        MockHttpServletResponse response = write(new FramingHeaders("https://studio.example.org/"),
                "/resources/banners/spring/index.html");
        assertThat(response.getHeader("Content-Security-Policy"))
                .isEqualTo("frame-ancestors 'self' https://studio.example.org");
        assertThat(response.getHeader("X-Frame-Options")).isNull();
    }

    @Test
    @DisplayName("a draft preview: the studio may frame it, whatever the path")
    void preview() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/article/5");
        request.setAttribute(io.pacod.cms.site.render.SitePreview.ATTRIBUTE,
                new io.pacod.cms.site.render.SitePreview.Preview("t", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new FramingHeaders("https://studio.example.org").writeHeaders(request, response);
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("frame-ancestors 'self' https://studio.example.org");
        assertThat(response.getHeader("X-Frame-Options")).isNull();
    }

    @Test
    @DisplayName("no studio origin: a banner folder is framed by its website only")
    void noStudio() {
        MockHttpServletResponse response = write(new FramingHeaders(""), "/resources/banners/spring/index.html");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("frame-ancestors 'self'");
    }

    @Test
    @DisplayName("a policy the response already carries is kept")
    void existingPolicyKept() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("Content-Security-Policy", "sandbox");
        new FramingHeaders("").writeHeaders(new MockHttpServletRequest("GET", "/"), response);
        assertThat(response.getHeaders("Content-Security-Policy")).containsExactly("sandbox");
    }

    @Test
    @DisplayName("an origin that is not scheme://host[:port] fails the start rather than reach a header")
    void badOrigin() {
        assertThatThrownBy(() -> new FramingHeaders("https://studio.example.org/x; script-src *"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FramingHeaders("studio.example.org")).isInstanceOf(IllegalStateException.class);
    }
}
