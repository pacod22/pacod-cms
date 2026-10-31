package io.pacod.cms.core.services.sitefiles;

import java.util.List;

/**
 * The AI crawlers a generated {@code robots.txt} can shut out, by the token each obeys in a
 * {@code User-agent:} line -- two groups, because they are two decisions:
 *
 * <ul>
 *   <li>{@link #TRAINING}: crawlers that gather text to train models. Shutting them out costs the
 *       website no reader.</li>
 *   <li>{@link #ANSWERING}: AI search and assistants fetching a page because a person asked
 *       about it. Shutting them out removes the website from those answers.</li>
 * </ul>
 *
 * <p>{@code Google-Extended} and {@code Applebot-Extended} are robots.txt tokens only: Google and
 * Apple crawl with their search bots and honour these for training. {@code AiCrawlersTest} checks
 * that {@code CrawlerClassifier} files every token as AI, so the crawler screen and this list agree.
 */
public final class AiCrawlers {

    public static final List<String> TRAINING = List.of("GPTBot", "ClaudeBot", "anthropic-ai", "CCBot",
            "Google-Extended", "Applebot-Extended", "Meta-ExternalAgent", "Bytespider", "cohere-ai");

    public static final List<String> ANSWERING = List.of("OAI-SearchBot", "ChatGPT-User", "Claude-SearchBot",
            "Claude-User", "PerplexityBot", "Perplexity-User");

    private AiCrawlers() {
    }
}
