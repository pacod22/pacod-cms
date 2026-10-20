package io.pacod.cms.core.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JournalIssueTest {

    @Test
    @DisplayName("an issue naming no language generates the original; its texts resolve through the chain")
    void languagesAndTexts() {
        JournalIssue issue = new JournalIssue();
        assertThat(issue.languagesOrOriginal()).containsExactly(LanguageChain.original());
        issue.setLanguages(List.of("en", "de"));
        assertThat(issue.languagesOrOriginal()).containsExactly("en", "de");

        issue.setTitles(List.of(TextItem.builder().lang("bg").text("Есен").build()));
        assertThat(issue.title(List.of("de", "bg"))).isEqualTo("Есен");
        assertThat(issue.rawTitle("de")).isNull();
        assertThat(issue.published()).isFalse();
    }

    @Test
    @DisplayName("a document written without the lists reads as empty, never null")
    void nullListsReadEmpty() {
        JournalIssue issue = new JournalIssue();
        issue.setArticleIds(null);
        issue.setRenditions(null);
        issue.setLanguages(null);
        assertThat(issue.getArticleIds()).isEmpty();
        assertThat(issue.getRenditions()).isEmpty();
        assertThat(issue.languagesOrOriginal()).containsExactly(LanguageChain.original());
    }
}
