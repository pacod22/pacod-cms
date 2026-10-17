package io.pacod.cms.core.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SocialPageTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 11, 12, 0);

    @Test
    @DisplayName("a blank translation is removed, never stored as an empty string")
    void blankIsRemoved() {
        SocialPage page = new SocialPage();
        page.putName("bg", "Примерен вестник");
        page.putName("de", "Beispielzeitung");
        page.putName("de", "   ");

        assertThat(page.getNames()).extracting(TextItem::getLang).containsExactly("bg");
        assertThat(page.rawName("de")).isNull();
    }

    @Test
    @DisplayName("a stored list that is immutable or null does not break a write")
    void putCopiesTheStoredList() {
        SocialPage page = new SocialPage();
        page.setBios(List.of(new TextItem("Списание", "bg")));
        page.putBio("en", "A magazine");
        page.setLatestHeadings(null);
        page.putLatestHeading("bg", "Последни статии");

        assertThat(page.rawBio("bg")).isEqualTo("Списание");
        assertThat(page.rawBio("en")).isEqualTo("A magazine");
        assertThat(page.rawLatestHeading("bg")).isEqualTo("Последни статии");
    }

    @Test
    @DisplayName("resolution walks the chain and reports which language answered")
    void resolutionFollowsTheChain() {
        SocialPage page = new SocialPage();
        page.putName("bg", "Примерен вестник");
        page.putName("en", "Example Gazette");

        assertThat(page.name(LanguageChain.forRequest("de"))).isEqualTo("Example Gazette");
        assertThat(page.nameLang(LanguageChain.forRequest("de"))).isEqualTo("en");
        assertThat(page.name(LanguageChain.forRequest(null))).isEqualTo("Примерен вестник");
        // Raw never falls back: the console's German tab must show an empty field.
        assertThat(page.rawName("de")).isNull();
    }

    @Test
    @DisplayName("writtenIn collects every text on the page, links included, once each")
    void writtenInCoversLinks() {
        SocialPage page = new SocialPage();
        page.putName("bg", "Примерен вестник");
        SocialLink link = new SocialLink("abcdefgh");
        link.putTitle("ro", "Abonament");
        link.putTitle("bg", "Абонамент");
        page.getLinks().add(link);

        assertThat(page.writtenIn()).containsExactly("bg", "ro");
    }

    @Test
    @DisplayName("generated link ids are safe map keys: a letter, then seven of [a-z0-9]")
    void linkIdsHaveTheirShape() {
        SocialPage page = new SocialPage();
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            String id = page.newLinkId(random);
            assertThat(SocialPage.isLinkId(id)).as(id).isTrue();
            assertThat(page.link(id)).isEmpty();
            page.getLinks().add(new SocialLink(id));
        }
        assertThat(page.getLinks()).extracting(SocialLink::getId).doesNotHaveDuplicates();
        assertThat(SocialPage.isLinkId("a.b$c")).isFalse();
        assertThat(SocialPage.isLinkId("12345678")).isFalse();
    }

    @Test
    @DisplayName("a link is live only when switched on and inside its window")
    void liveLinksRespectTheSchedule() {
        SocialPage page = new SocialPage();
        SocialLink always = link("aaaaaaaa", SocialLinkPlacement.BUTTON);
        SocialLink off = link("bbbbbbbb", SocialLinkPlacement.BUTTON);
        off.setActive(false);
        SocialLink later = link("cccccccc", SocialLinkPlacement.BUTTON);
        later.setActiveFrom(NOW.plusHours(1));
        SocialLink over = link("dddddddd", SocialLinkPlacement.BUTTON);
        over.setActiveTo(NOW.minusMinutes(1));
        SocialLink icon = link("eeeeeeee", SocialLinkPlacement.ICON);
        page.getLinks().addAll(List.of(always, off, later, over, icon));

        assertThat(page.liveLinks(SocialLinkPlacement.BUTTON, NOW)).containsExactly(always);
        assertThat(page.liveLinks(SocialLinkPlacement.ICON, NOW)).containsExactly(icon);
        assertThat(page.linksOf(SocialLinkPlacement.BUTTON)).hasSize(4);
    }

    @Test
    @DisplayName("an icon without a title is labelled by its icon")
    void iconLabelFallsBackToTheIcon() {
        SocialLink icon = link("aaaaaaaa", SocialLinkPlacement.ICON);
        icon.setIcon(SocialIcon.INSTAGRAM);

        assertThat(icon.label(LanguageChain.forRequest("bg"))).isEqualTo("Instagram");
    }

    @Test
    @DisplayName("the latest-articles count is clamped whatever was stored")
    void latestCountIsClamped() {
        SocialPage page = new SocialPage();
        page.setLatestArticlesCount(0);
        assertThat(page.latestCount()).isEqualTo(SocialPage.MIN_LATEST);
        page.setLatestArticlesCount(500);
        assertThat(page.latestCount()).isEqualTo(SocialPage.MAX_LATEST);
    }

    /**
     * Spring Data maps any property called {@code id} to {@code _id} -- inside an embedded
     * document too, with no {@code @Id} in sight. So a stored link is {@code {_id: "k3x9a2bq", …}},
     * and a hand-written {@code find({"links.id": …})} in mongosh matches nothing, silently. The
     * round trip is what the application depends on; this pins both halves of it in the shape
     * MongoDB holds, the {@code SiteMetricMappingTest} arrangement.
     */
    @Test
    @DisplayName("an embedded link's id is stored as _id, and reads back as id")
    void embeddedLinkIdIsStoredAsUnderscoreId() {
        org.springframework.data.mongodb.core.convert.MongoCustomConversions conversions =
                new org.springframework.data.mongodb.core.convert.MongoCustomConversions(List.of());
        org.springframework.data.mongodb.core.mapping.MongoMappingContext context =
                new org.springframework.data.mongodb.core.mapping.MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        org.springframework.data.mongodb.core.convert.MappingMongoConverter converter =
                new org.springframework.data.mongodb.core.convert.MappingMongoConverter(
                        org.springframework.data.mongodb.core.convert.NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();

        SocialPage page = new SocialPage();
        page.getLinks().add(link("k3x9a2bq", SocialLinkPlacement.BUTTON));

        org.bson.Document written = new org.bson.Document();
        converter.write(page, written);
        org.bson.Document storedLink = written.getList("links", org.bson.Document.class).getFirst();
        assertThat(storedLink).containsEntry("_id", "k3x9a2bq").doesNotContainKey("id");

        SocialPage read = converter.read(SocialPage.class, written);
        assertThat(read.link("k3x9a2bq")).isPresent();
    }

    private static SocialLink link(String id, SocialLinkPlacement placement) {
        SocialLink link = new SocialLink(id);
        link.setPlacement(placement);
        link.setUrl("https://example.com/" + id);
        return link;
    }
}
