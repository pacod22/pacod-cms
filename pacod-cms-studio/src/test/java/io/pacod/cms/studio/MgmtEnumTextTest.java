package io.pacod.cms.studio;

import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.data.CrawlerCategory;
import io.pacod.cms.core.data.CrawlerVerification;
import io.pacod.cms.core.data.LayoutOrigin;
import io.pacod.cms.core.data.SocialIcon;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.data.SocialSource;
import io.pacod.cms.studio.i18n.MgmtText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every labelled enum the console shows has its texts in the bundles, constant by constant.
 *
 * <p>The enums' own labels are English strings in core, which has no message bundles, so they
 * stay where they are; {@link MgmtText#label(Enum)} looks for {@code enum.<Type>.<NAME>} first and
 * falls back to them. That fallback is exactly what makes a missing key invisible -- a Bulgarian
 * screen with one English status in it -- so the coverage is asserted here rather than noticed.
 *
 * <p>A constant added to one of these enums fails this test until its texts are written.
 */
class MgmtEnumTextTest {

    /** Each enum the console renders, and the attributes of it that it renders. */
    static final Map<Class<? extends Enum<?>>, Set<String>> ENUMS = Map.ofEntries(
            Map.entry(io.pacod.cms.core.data.ArticleStatus.class, Set.of("label")),
            Map.entry(BannerPage.class, Set.of("label")),
            Map.entry(BannerPlacement.class, Set.of("label")),
            Map.entry(BannerSize.class, Set.of("label")),
            Map.entry(BannerType.class, Set.of("label")),
            Map.entry(LayoutOrigin.class, Set.of("label")),
            Map.entry(CrawlerCategory.class, Set.of("label")),
            Map.entry(CrawlerVerification.class, Set.of("label")),
            Map.entry(SocialIcon.class, Set.of("label")),
            Map.entry(SocialLinkPlacement.class, Set.of("label")),
            Map.entry(SocialSource.class, Set.of("label")),
            Map.entry(io.pacod.cms.core.control.data.JobType.class, Set.of("label")),
            Map.entry(io.pacod.cms.core.control.data.JobStatus.class, Set.of("label")),
            Map.entry(io.pacod.cms.studio.ai.AiRefusal.class, Set.of("label")),
            Map.entry(MonitoringRange.class, Set.of("label")),
            Map.entry(io.pacod.cms.core.control.data.WebsiteStatus.class, Set.of("label")),
            Map.entry(io.pacod.cms.core.control.data.OrgCreation.class, Set.of("label")),
            Map.entry(io.pacod.cms.core.design.PageType.class, Set.of("label")),
            Map.entry(io.pacod.cms.core.data.MenuLinkKind.class, Set.of("label")));

    @Test
    @DisplayName("every constant of every console enum has its texts")
    void everyConstantHasItsTexts() {
        Set<String> keys = MgmtBundleTest.allKeys();
        List<String> missing = new ArrayList<>();
        ENUMS.forEach((type, attributes) -> {
            for (Enum<?> constant : type.getEnumConstants()) {
                for (String attribute : attributes) {
                    String key = MgmtText.enumKey(constant, attribute);
                    if (!keys.contains(key)) {
                        missing.add(key);
                    }
                }
            }
        });
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("no enum key names a constant or a type that does not exist")
    void noStaleEnumKeys() {
        Set<String> valid = new java.util.TreeSet<>();
        ENUMS.forEach((type, attributes) -> {
            for (Enum<?> constant : type.getEnumConstants()) {
                for (String attribute : attributes) {
                    valid.add(MgmtText.enumKey(constant, attribute));
                }
            }
        });
        List<String> stale = MgmtBundleTest.allKeys().stream()
                .filter(k -> k.startsWith("enum."))
                .filter(k -> !valid.contains(k))
                .toList();
        assertThat(stale).isEmpty();
    }
}
