package io.pacod.cms.core.tenant;

/**
 * A cache key qualified by the website it belongs to. Two websites caching "the page list" under
 * the same key get two entries, by construction.
 */
public record TenantKey(String siteId, Object key) {
}
