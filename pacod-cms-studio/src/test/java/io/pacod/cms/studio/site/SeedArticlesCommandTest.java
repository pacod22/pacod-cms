package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.studio.demo.StudioDemo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SeedArticlesCommandTest {

    @Test
    @DisplayName("--articles takes numbers, separated by commas or spaces")
    void readsTheNumbers() {
        assertThat(SeedArticlesCommand.articles("14,15,16")).containsExactly(14, 15, 16);
        assertThat(SeedArticlesCommand.articles(" 14 15, 16 ")).containsExactly(14, 15, 16);
        assertThat(SeedArticlesCommand.articles("")).isEmpty();
        assertThatThrownBy(() -> SeedArticlesCommand.articles("14,latest"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("latest");
    }

    @Test
    @DisplayName("a command line without a website, a seed or articles, or naming a website that is not there, does nothing")
    void refusesAnIncompleteCommandLine() {
        WebsiteRepository websites = mock(WebsiteRepository.class);
        when(websites.findById(anyString())).thenReturn(Optional.empty());
        StudioDemo demo = mock(StudioDemo.class);

        assertThat(SeedArticlesCommand.run(new DefaultApplicationArguments("--seed-articles", "--site-id=pacod",
                "--seed-dir=/seed"), websites, demo)).isEqualTo(2);
        assertThat(SeedArticlesCommand.run(new DefaultApplicationArguments("--seed-articles", "--site-id=pacod",
                "--seed-dir=/seed", "--articles=14,x"), websites, demo)).isEqualTo(2);
        assertThat(SeedArticlesCommand.run(new DefaultApplicationArguments("--seed-articles", "--site-id=none",
                "--seed-dir=/seed", "--articles=14"), websites, demo)).isEqualTo(2);
        verifyNoInteractions(demo);
    }
}
