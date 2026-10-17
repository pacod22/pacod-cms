package io.pacod.cms.core.control.data;

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
    TRANSLATE_SITE(true);

    private final boolean usesAi;

    JobType(boolean usesAi) {
        this.usesAi = usesAi;
    }

    /** Whether it calls the AI gateway: then it needs {@code ai.use} and AI switched on. */
    public boolean usesAi() {
        return usesAi;
    }
}
