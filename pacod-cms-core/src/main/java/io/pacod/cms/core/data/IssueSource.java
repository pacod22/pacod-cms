package io.pacod.cms.core.data;

/**
 * How a {@link JournalIssue}'s article list was last filled, shown in the studio. Display only:
 * the list itself is the issue's content either way, so a published issue never changes because
 * an article was redated afterwards.
 */
public enum IssueSource {

    /** Filled from the published articles of the issue's month, then perhaps reordered. */
    MONTH,

    /** Articles picked one by one. */
    SELECTION
}
