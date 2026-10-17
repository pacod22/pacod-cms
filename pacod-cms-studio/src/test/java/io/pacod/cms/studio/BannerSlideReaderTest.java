package io.pacod.cms.studio;

import io.pacod.cms.core.data.BannerLayout;
import io.pacod.cms.core.data.BannerSkeleton;
import io.pacod.cms.core.data.BannerSlide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading frames back out of a folder the console did not write.
 *
 * <p>The fixtures are <b>hand-edited folders</b>, not markup written to suit the parser:
 * {@code acme2.html} is a booking's folder in the hand-authored shape, its own script included, and
 * {@code globex-top.html} a top leaderboard copied from a template and reformatted since. That is
 * the point -- folders hand-edited over years drift from the template they came from, so a reader
 * validated only against generated markup would adopt none of them.
 *
 * <p><b>They are fixed fixtures and must not be refreshed from a live folder.</b> The assertions
 * name the campaigns those files carry ({@code spring-offer}, {@code product-two}), so replacing
 * them with a later copy breaks these tests for a reason that has nothing to do with the code. They
 * carry no images; see {@code banner-skeletons/VENDORED.md}.
 */
class BannerSlideReaderTest {

    @TempDir
    Path folder;

    private final BannerSkeletonParser parser = new BannerSkeletonParser();

    private String fixture(String name) throws IOException {
        try (InputStream in = new ClassPathResource("banner-archive/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("a hand-authored booking's three frames are read, links and alt text included")
    void readsALiveBooking() throws IOException {

        List<BannerSlide> slides = parser.readSlides(fixture("acme2.html"));

        assertThat(slides).hasSize(3);
        assertThat(slides).extracting(BannerSlide::getImage)
                .containsExactly("acme-banner-1.webp", "acme-banner-2.webp", "acme-banner-3.webp");
        assertThat(slides).allSatisfy(slide -> {
            assertThat(slide.getHref()).startsWith("https://");
            assertThat(slide.getAlt()).isEqualTo("Acme");
        });
        // The three frames of one booking do not share a destination -- the third goes to a
        // different host entirely. Adoption has to carry each frame's own link, which is the whole
        // reason a layout stores a list rather than one target the way an image booking does.
        assertThat(slides.get(0).getHref()).contains("www.example.com/acme/spring-offer");
        assertThat(slides.get(1).getHref()).contains("www.example.com/acme/product-two");
        assertThat(slides.get(2).getHref()).startsWith("https://promo.example.com/");
    }

    @Test
    @DisplayName("the first frame is read despite carrying neither the id nor the class we emit")
    void isLenientAboutWhatTheArchiveOmits() throws IOException {
        // acme2's first <a> has no id="a-banner-1" and no class="banner-link". Matching what
        // BannerLayoutStorage emits would drop it, and the adopted layout would silently lose a
        // creative the advertiser is paying for.
        String html = fixture("acme2.html");
        assertThat(html).doesNotContain("id=\"a-banner-1\"");

        assertThat(parser.readSlides(html).getFirst().getHref())
                .contains("spring-offer");
    }

    @Test
    @DisplayName("a query string's &amp; comes back as one ampersand, not two escapes")
    void decodesEntitiesOnTheWayIn() throws IOException {
        // Every one of these URLs carries utm parameters, so a round trip that did not decode would
        // turn & into &amp;amp; on the first save and break the advertiser's campaign tracking.
        List<BannerSlide> slides = parser.readSlides(fixture("acme2.html"));

        assertThat(slides.getFirst().getHref()).contains("&utm_medium=display");
        assertThat(slides.getFirst().getHref()).doesNotContain("&amp;");
    }

    @Test
    @DisplayName("the leaderboard template reads too, formatted entirely differently")
    void readsTheOtherTemplate() throws IOException {
        // Prettier-formatted, attributes on their own lines, class="banner-img" present.
        List<BannerSlide> slides = parser.readSlides(fixture("globex-top.html"));

        assertThat(slides).hasSize(2);
        assertThat(slides).extracting(BannerSlide::getImage)
                .containsExactly("globex-banner-1.webp", "globex-banner-2.webp");
        assertThat(slides.getFirst().getTitle()).isEqualTo("Глобекс® Продукт");
    }

    @Test
    @DisplayName("the rotation interval is read from the folder's own script, and modulo is ignored")
    void readsTheRotationInterval() throws IOException {
        String js = fixture("acme2-banner.js");

        assertThat(parser.speedFrom(js)).isEqualTo(10000);
        // modulo is the hardcoded slide count adoption exists to replace: this very file declares 2
        // while its markup carries three frames, so the third creative has never been shown.
        assertThat(js).contains("modulo = 2");
        assertThat(parser.readSlides(fixture("acme2.html"))).hasSize(3);
    }

    @Test
    @DisplayName("an unreadable or absent speed falls back rather than guessing a number")
    void toleratesAScriptItCannotRead() {
        assertThat(parser.speedFrom(null)).isNull();
        assertThat(parser.speedFrom("function banner() {}")).isNull();
        // Out of the range rotator.js honours; taking it would store a value the folder ignores.
        assertThat(parser.speedFrom("var speed = 12;")).isNull();
        assertThat(parser.speedFrom("var speed = 4500;")).isEqualTo(4500);
    }

    @Test
    @DisplayName("a child with no image is not a frame")
    void skipsNonFrames() {
        String html = "<html><head><link rel=stylesheet href=\"css/banner.css\"></head><body>"
                + "<div class=\"banner-container-inline\">"
                + "<div id=\"banner-1\"><a href=\"https://a.example\"><img src=\"a.webp\" alt=\"A\"></a></div>"
                + "<div class=\"legal\">Реклама</div>"
                + "<div id=\"banner-2\"><img src=\"b.webp\" alt=\"B\"></div>"
                + "</div>"
                + "<script src=\"js/banner.js\"></script><script>banner();</script></body></html>";

        List<BannerSlide> slides = parser.readSlides(html);

        assertThat(slides).extracting(BannerSlide::getImage).containsExactly("a.webp", "b.webp");
        // A frame without a link is legitimate -- a house ad that goes nowhere.
        assertThat(slides.get(1).getHref()).isNull();
    }

    @Test
    @DisplayName("what is read regenerates to markup that reads back the same")
    void roundTripsThroughTheGenerator() throws IOException {

        BannerLayoutStorage storage = new BannerLayoutStorage();
        ReflectionTestUtils.setField(storage, "bannerFolder", folder.toString());

        String original = fixture("acme2.html");
        BannerSkeletonParser.Split split = parser.parse(original);

        BannerSkeleton skeleton = new BannerSkeleton();
        skeleton.setHtmlHead(split.htmlHead());
        skeleton.setContainerTag(split.containerTag());
        skeleton.setHtmlTail(split.htmlTail());
        skeleton.setCss(".x {}");

        BannerLayout layout = new BannerLayout("acme2");
        layout.setGtmId(split.gtmId());
        layout.setSlides(parser.readSlides(original));

        // Adoption promises the folder keeps working. Generating from what was read and reading it
        // back must therefore produce the same frames -- otherwise the first save quietly changes
        // what the advertiser booked.
        List<BannerSlide> reread = parser.readSlides(storage.renderIndex(layout, skeleton));

        assertThat(reread).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(layout.getSlides());
    }
}
