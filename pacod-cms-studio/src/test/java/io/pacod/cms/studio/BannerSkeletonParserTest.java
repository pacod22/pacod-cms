package io.pacod.cms.studio;

import io.pacod.cms.core.data.BannerSkeleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The split, and the three tags it refuses to do without.
 *
 * <p>Both vendored skeletons are exercised as they ship rather than through a fixture written to
 * suit the parser: if one of them ever stopped satisfying the contract, the seed would fail at
 * startup on a fresh database and this is where that would be caught first.
 */
class BannerSkeletonParserTest {

    private final BannerSkeletonParser parser = new BannerSkeletonParser();

    private String vendored(String id) throws IOException {
        try (InputStream in = new ClassPathResource("banner-skeletons/" + id + "/index.html").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code html} with Google Tag Manager's two snippets added, the way an advertiser's skeleton
     * carries them: the script in the head and the noscript iframe in the body. The built-in
     * skeletons load no tag manager, so the GTM handling is exercised on this.
     */
    private static String withGtm(String html, String id) {
        String script = "<script>(function(w,d,s,l,i){w[l]=w[l]||[];w[l].push({'gtm.start':"
                + "new Date().getTime(),event:'gtm.js'});var f=d.getElementsByTagName(s)[0],"
                + "j=d.createElement(s),dl=l!='dataLayer'?'&l='+l:'';j.async=true;j.src="
                + "'https://www.googletagmanager.com/gtm.js?id='+i+dl;f.parentNode.insertBefore(j,f);"
                + "})(window,document,'script','dataLayer','" + id + "');</script>\n";
        String noscript = "<noscript><iframe src=\"https://www.googletagmanager.com/ns.html?id=" + id
                + "\" height=\"0\" width=\"0\" style=\"display:none\"></iframe></noscript>\n";
        String withHead = html.replaceFirst("<head>", "<head>\n" + script);
        return withHead.replaceFirst("(<body[^>]*>)", "$1\n" + noscript);
    }

    @Test
    @DisplayName("the built-in skeletons load no tag manager of their own")
    void builtInSkeletonsCarryNoGtm() throws IOException {
        for (String id : new String[]{BannerSkeleton.SIDEBAR, BannerSkeleton.TOP}) {
            assertThat(parser.parse(vendored(id)).gtmId()).as(id).isNull();
            assertThat(vendored(id)).as(id).doesNotContain("googletagmanager");
        }
    }

    @Test
    @DisplayName("the sidebar skeleton splits, and the tail carries the closing tag")
    void splitsTheSidebarSkeleton() throws IOException {

        BannerSkeletonParser.Split split = parser.parse(vendored(BannerSkeleton.SIDEBAR));

        assertThat(split.htmlHead()).contains("<link rel=\"stylesheet\" href=\"css/banner.css\"/>");
        assertThat(split.containerTag()).isEqualTo("<div class=\"banner-container-inline\"");
        // Inclusive of </div>, so generating never has to know what the element was called.
        assertThat(split.htmlTail()).startsWith("</div>");
        assertThat(split.htmlTail()).contains("banner();");
    }

    @Test
    @DisplayName("the top skeleton splits too, despite being formatted differently")
    void splitsTheTopSkeleton() throws IOException {

        BannerSkeletonParser.Split split = parser.parse(vendored(BannerSkeleton.TOP));

        assertThat(split.containerTag()).isEqualTo("<div class=\"banner-container-inline\"");
        assertThat(split.htmlTail()).startsWith("</div>");
    }

    @Test
    @DisplayName("the GTM container id is lifted out and replaced by a token throughout")
    void liftsTheGtmId() throws IOException {

        BannerSkeletonParser.Split split = parser.parse(withGtm(vendored(BannerSkeleton.SIDEBAR), "GTM-TEST4321"));

        assertThat(split.gtmId()).isEqualTo("GTM-TEST4321");
        // Both occurrences: the script and the noscript iframe. Missing one would leave a folder
        // reporting half its pageviews to whichever container the skeleton was written for.
        assertThat(split.htmlHead()).doesNotContain("GTM-TEST4321");
        assertThat(split.htmlTail()).doesNotContain("GTM-TEST4321");
        assertThat(split.htmlHead()).contains(BannerSkeletonParser.GTM_TOKEN);
    }

    @Test
    @DisplayName("a skeleton with no GTM at all is legitimate")
    void allowsNoGtm() {
        BannerSkeletonParser.Split split = parser.parse(minimal(
                "<div class=\"banner-container-inline\"></div>", null));
        assertThat(split.gtmId()).isNull();
    }

    @Test
    @DisplayName("two different GTM containers are refused rather than one of them picked")
    void refusesTwoGtmIds() {
        String html = minimal("<div class=\"banner-container-inline\"></div>", null)
                .replace("<body>", "<body><!-- GTM-AAAA1111 GTM-BBBB2222 -->");

        assertThatThrownBy(() -> parser.parse(html))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("GTM-AAAA1111");
    }

    @Test
    @DisplayName("assemble puts the file back together with the real GTM id, so a round trip keeps it")
    void assembleRestoresTheGtmId() throws IOException {

        BannerSkeletonParser.Split split = parser.parse(withGtm(vendored(BannerSkeleton.SIDEBAR), "GTM-TEST4321"));

        BannerSkeleton skeleton = new BannerSkeleton();
        skeleton.setHtmlHead(split.htmlHead());
        skeleton.setContainerTag(split.containerTag());
        skeleton.setHtmlTail(split.htmlTail());
        skeleton.setGtmId(split.gtmId());

        String assembled = parser.assemble(skeleton);
        assertThat(assembled).contains("GTM-TEST4321");
        assertThat(assembled).doesNotContain(BannerSkeletonParser.GTM_TOKEN);

        // The point of the substitution: a save of what the editor showed must not lose the id.
        assertThat(parser.parse(assembled).gtmId()).isEqualTo("GTM-TEST4321");
    }

    @Test
    @DisplayName("no container, two containers and a self-closing one are each refused by name")
    void refusesAnAmbiguousContainer() {

        assertThatThrownBy(() -> parser.parse(minimal("<div class=\"other\"></div>", null)))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("banner-container-inline");

        assertThatThrownBy(() -> parser.parse(minimal(
                "<div class=\"banner-container-inline\"></div>"
                        + "<div class=\"banner-container-inline\"></div>", null)))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("more than once");

        assertThatThrownBy(() -> parser.parse(minimal("<div class=\"banner-container-inline\"/>", null)))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("self-closing");
    }

    @Test
    @DisplayName("a nested div does not end the container early")
    void countsNestedElements() {
        BannerSkeletonParser.Split split = parser.parse(minimal(
                "<div class=\"banner-container-inline\"><div><div></div></div></div><footer>x</footer>", null));

        assertThat(split.htmlTail()).startsWith("</div><footer>x</footer>");
    }

    @Test
    @DisplayName("a > inside an attribute value does not end the start tag")
    void survivesAngleBracketsInAttributes() {
        BannerSkeletonParser.Split split = parser.parse(minimal(
                "<div class=\"banner-container-inline\" title=\"5 > 4\"></div>", null));

        assertThat(split.containerTag()).isEqualTo("<div class=\"banner-container-inline\" title=\"5 > 4\"");
    }

    @Test
    @DisplayName("each of the three required tags is refused by name when it is missing")
    void refusesMarkupTheGeneratorCouldNotDriveSilently() {

        // Every one of these produces a folder that publishes with a 200 and shows the wrong
        // thing -- unstyled, or the first slide forever -- which is the reason they are refused
        // here rather than discovered on the site.
        String container = "<div class=\"banner-container-inline\"></div>";

        assertThatThrownBy(() -> parser.parse(
                "<html><head><link rel=stylesheet href=\"style.css\"></head><body>" + container
                        + "<script src=\"js/banner.js\"></script><script>banner();</script></body></html>"))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("css/banner.css");

        assertThatThrownBy(() -> parser.parse(
                "<html><head><link rel=stylesheet href=\"css/banner.css\"></head><body>" + container
                        + "<script src=\"js/rotate.js\"></script><script>banner();</script></body></html>"))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("js/banner.js");

        assertThatThrownBy(() -> parser.parse(
                "<html><head><link rel=stylesheet href=\"css/banner.css\"></head><body>" + container
                        + "<script src=\"js/banner.js\"></script></body></html>"))
                .isInstanceOf(MgmtOperationException.class)
                .hasMessageContaining("banner()");
    }

    @Test
    @DisplayName("a ./ prefixed asset path counts as the same file")
    void acceptsDotSlashPaths() {
        BannerSkeletonParser.Split split = parser.parse(
                "<html><head><link rel=stylesheet href=\"./css/banner.css\"></head><body>"
                        + "<div class=\"banner-container-inline\"></div>"
                        + "<script src=\"./js/banner.js\"></script><script>banner();</script></body></html>");

        assertThat(split.containerTag()).contains("banner-container-inline");
    }

    /** A minimal document that satisfies the three-tag contract, so a test can break one thing. */
    private String minimal(String body, String ignored) {
        return "<html><head><link rel=\"stylesheet\" href=\"css/banner.css\"></head><body>"
                + body
                + "<script src=\"js/banner.js\"></script><script>banner();</script></body></html>";
    }
}
