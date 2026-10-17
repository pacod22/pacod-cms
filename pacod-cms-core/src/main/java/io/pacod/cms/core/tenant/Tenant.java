package io.pacod.cms.core.tenant;

import java.time.ZoneId;
import java.util.Objects;

/**
 * One website, as everything that reads or writes its data needs to know it.
 *
 * @param siteId          the website's id: immutable, and the name every one of its resources
 *                        is filed under -- its database, its storage folder, its cache keys
 * @param organisationId  the organisation that owns it
 * @param dbName          the MongoDB database holding all of its content
 * @param defaultLanguage the language its content is written in ({@code LanguageChain.original()});
 *                        {@code null} means the platform's configured default
 * @param timezone        the zone its editors and readers count days in
 * @param templateSet     the template set its public pages render with ({@link #DEFAULT_TEMPLATE_SET}
 *                        when none is recorded)
 */
public record Tenant(String siteId, String organisationId, String dbName, String defaultLanguage,
                     ZoneId timezone, String templateSet) {

    /** The set every website renders with until it chooses another. */
    public static final String DEFAULT_TEMPLATE_SET = "editorial";

    public Tenant {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(dbName, "dbName");
        defaultLanguage = defaultLanguage == null || defaultLanguage.isBlank() ? null : defaultLanguage;
        timezone = timezone == null ? ZoneId.of("UTC") : timezone;
        templateSet = templateSet == null || templateSet.isBlank() ? DEFAULT_TEMPLATE_SET : templateSet;
    }

    public Tenant(String siteId, String organisationId, String dbName, String defaultLanguage, ZoneId timezone) {
        this(siteId, organisationId, dbName, defaultLanguage, timezone, null);
    }

    /** The same website rendering with another set: what a draft preview of a template switch binds. */
    public Tenant withTemplateSet(String set) {
        return new Tenant(siteId, organisationId, dbName, defaultLanguage, timezone, set);
    }
}
