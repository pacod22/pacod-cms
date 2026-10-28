package io.pacod.cms.studio.site;

import io.pacod.cms.core.seed.DemoContent;
import io.pacod.cms.core.tenant.SiteProvisioner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreateSiteCommandTest {

    private static SiteProvisioner.NewWebsite request(String templateSet) {
        return new SiteProvisioner.NewWebsite(null, "Acme", "acme", "Acme", "en", "UTC", List.of("acme.localhost"),
                null, templateSet);
    }

    @Test
    @DisplayName("--demo=space alone makes a magazine website; a named set, or another demo, keeps its own")
    void demoNamesItsSetWhenNoneIsGiven() {
        assertThat(CreateSiteCommand.forSet(request(null), DemoContent.Demo.SPACE.templateSet()).templateSet()).isEqualTo("magazine");
        assertThat(CreateSiteCommand.forSet(request(null), DemoContent.Demo.JOURNAL.templateSet()).templateSet()).isNull();
        assertThat(CreateSiteCommand.forSet(request(null), null).templateSet()).isNull();
        assertThat(CreateSiteCommand.forSet(request("minimal"), DemoContent.Demo.SPACE.templateSet()).templateSet()).isEqualTo("minimal");
        assertThat(CreateSiteCommand.forSet(request(null), DemoContent.Demo.SPACE.templateSet()).hosts()).containsExactly("acme.localhost");
    }
}
