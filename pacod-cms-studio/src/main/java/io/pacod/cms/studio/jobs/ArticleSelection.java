package io.pacod.cms.studio.jobs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which articles, and which languages, a job names: {@code ids} (numbers, commas) or
 * {@code from}/{@code to}, at most {@value #MAX_ARTICLES} articles; {@code languages}, codes.
 * The controller validates the same shapes before it queues; this is the handlers' reading.
 */
public final class ArticleSelection {

    public static final int MAX_ARTICLES = 500;

    private ArticleSelection() {
    }

    public static List<Integer> articleIds(JobContext context) {
        List<Integer> ids = new ArrayList<>();
        String list = context.param("ids");
        if (list != null) {
            for (String part : list.split("[,\\s]+")) {
                if (part.matches("\\d{1,9}")) {
                    ids.add(Integer.valueOf(part));
                }
            }
        } else if (context.param("from") != null && context.param("to") != null) {
            int from = Integer.parseInt(context.param("from"));
            int to = Integer.parseInt(context.param("to"));
            for (int id = from; id <= to && ids.size() <= MAX_ARTICLES; id++) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("No articles named: give ids, or from and to");
        }
        if (ids.size() > MAX_ARTICLES) {
            throw new IllegalArgumentException("At most " + MAX_ARTICLES + " articles a job");
        }
        return ids.stream().distinct().toList();
    }

    public static Set<String> languages(String list) {
        Set<String> codes = new LinkedHashSet<>();
        if (list != null) {
            for (String part : list.split("[,\\s]+")) {
                if (part.matches("[a-z]{2,3}")) {
                    codes.add(part);
                }
            }
        }
        return codes;
    }
}
