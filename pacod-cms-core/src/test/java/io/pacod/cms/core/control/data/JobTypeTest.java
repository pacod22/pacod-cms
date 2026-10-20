package io.pacod.cms.core.control.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobTypeTest {

    @Test
    @DisplayName("a PDF job uses AI exactly when its mode is llm; translations always do")
    void usesAiFollowsTheMode() {
        assertThat(JobType.PDF_ARTICLES.usesAi(Map.of())).isFalse();
        assertThat(JobType.PDF_ARTICLES.usesAi(Map.of("mode", "code"))).isFalse();
        assertThat(JobType.PDF_ARTICLES.usesAi(Map.of("mode", "llm"))).isTrue();
        assertThat(JobType.PDF_ISSUE.usesAi(Map.of("mode", "llm"))).isTrue();
        assertThat(JobType.PDF_ISSUE.usesAi(null)).isFalse();
        assertThat(JobType.TRANSLATE_ARTICLES.usesAi(Map.of())).isTrue();
    }
}
