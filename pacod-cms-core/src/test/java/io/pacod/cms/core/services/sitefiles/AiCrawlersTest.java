package io.pacod.cms.core.services.sitefiles;

import io.pacod.cms.core.data.CrawlerCategory;
import io.pacod.cms.core.util.CrawlerClassifier;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The robots.txt tokens and the crawler screen agree: every token is a crawler it files as AI. */
class AiCrawlersTest {

    @Test
    void everyTokenIsAnAiCrawler() {
        Stream.concat(AiCrawlers.TRAINING.stream(), AiCrawlers.ANSWERING.stream()).forEach(token ->
                assertThat(CrawlerClassifier.classify("Mozilla/5.0 (compatible; " + token + "/1.0)").category())
                        .as(token).isEqualTo(CrawlerCategory.AI));
    }

    @Test
    void theGroupsDoNotOverlap() {
        assertThat(AiCrawlers.TRAINING).doesNotContainAnyElementsOf(AiCrawlers.ANSWERING);
    }
}
