package io.pacod.cms.core.control.data;

import java.util.Map;

/**
 * What a {@link Job} does. A studio {@code JobHandler} implements each; the enum is in core because
 * the job document carries it.
 */
public enum JobType {

    /** PDFs of articles, one per language each article is written in, through Gotenberg. */
    PDF_ARTICLES(false),

    /** Articles translated through the AI gateway: title, description and body per language. */
    TRANSLATE_ARTICLES(true),

    /** The rest of a website through the AI gateway: standing pages, categories, tags, menus, social page. */
    TRANSLATE_SITE(true),

    /** A journal issue: cover, contents and its articles in one PDF per language, through Gotenberg. */
    PDF_ISSUE(false),

    /** The website's theme and every page type's layout drafted from one brief, through the AI gateway. */
    DESIGN_SITE(true);

    private final boolean usesAi;

    JobType(boolean usesAi) {
        this.usesAi = usesAi;
    }

    /** Whether it calls the AI gateway: then it needs {@code ai.use} and AI switched on. */
    public boolean usesAi() {
        return usesAi;
    }

    /**
     * Whether a job of this type with these parameters calls the AI gateway: always for the
     * translations, and for the PDF jobs in {@code mode=llm}, which have the gateway write each
     * article's abstract and keywords before rendering it.
     */
    public boolean usesAi(Map<String, String> params) {
        return usesAi || ((this == PDF_ARTICLES || this == PDF_ISSUE)
                && params != null && "llm".equals(params.get("mode")));
    }
}
