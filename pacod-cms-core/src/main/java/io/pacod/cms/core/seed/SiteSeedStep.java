package io.pacod.cms.core.seed;

/**
 * One part of what a new website starts with -- its languages, its standing pages, its settings
 * and menus, its banner skeletons -- written by {@code SiteProvisioner} into the website's own
 * database.
 *
 * <p>Runs with the website bound in {@code TenantContext}, so every repository it uses reads and
 * writes that website. Each step must be idempotent, writing only what is absent: the provisioner
 * runs them again when it brings an older website up to date, and must never overwrite what an
 * operator has since changed.
 */
public interface SiteSeedStep {

    /** For the log: what this step seeds. */
    String name();

    void seed(Website website);

    /**
     * What a step may need to know about the website beyond its database: what it is called and
     * which language it is written in.
     */
    record Website(String id, String name, String defaultLanguage) {
    }
}
