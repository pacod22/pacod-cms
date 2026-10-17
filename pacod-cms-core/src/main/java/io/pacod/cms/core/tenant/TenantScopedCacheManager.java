package io.pacod.cms.core.tenant;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Puts the bound website's id into every cache key, so no {@code @Cacheable} anywhere can serve one
 * website's entry to another.
 *
 * <p>At the {@link Cache} level rather than as a {@code KeyGenerator}, deliberately: every
 * {@code @Cacheable} in the code carries an explicit SpEL key ({@code 'all'}, {@code #id}), and an
 * explicit key bypasses the generator -- so a generator would have scoped nothing. Every method
 * of {@link Cache} that takes a key is wrapped; {@link Cache#clear()} and
 * {@link Cache#invalidate()} remove only the bound website's entries, which is what an
 * {@code @CacheEvict(allEntries = true)} means on a multi-website platform.
 *
 * <p>With no website bound, every operation throws ({@link TenantContext.NoTenantException}):
 * a cache read outside a website is a bug, and caching it under no website would share it with
 * all of them.
 */
public class TenantScopedCacheManager implements CacheManager {

    private final CacheManager delegate;
    private final Map<String, Cache> caches = new ConcurrentHashMap<>();

    public TenantScopedCacheManager(CacheManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = caches.get(name);
        if (cache != null) {
            return cache;
        }
        Cache underlying = delegate.getCache(name);
        if (underlying == null) {
            return null;
        }
        return caches.computeIfAbsent(name, n -> new TenantScopedCache(underlying));
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    static final class TenantScopedCache implements Cache {

        private final Cache delegate;

        TenantScopedCache(Cache delegate) {
            this.delegate = delegate;
        }

        private static TenantKey key(Object key) {
            return new TenantKey(TenantContext.current().siteId(), key);
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            return delegate.get(key(key));
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            return delegate.get(key(key), type);
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            return delegate.get(key(key), valueLoader);
        }

        @Override
        public CompletableFuture<?> retrieve(Object key) {
            return delegate.retrieve(key(key));
        }

        @Override
        public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
            return delegate.retrieve(key(key), valueLoader);
        }

        @Override
        public void put(Object key, Object value) {
            delegate.put(key(key), value);
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            return delegate.putIfAbsent(key(key), value);
        }

        @Override
        public void evict(Object key) {
            delegate.evict(key(key));
        }

        @Override
        public boolean evictIfPresent(Object key) {
            return delegate.evictIfPresent(key(key));
        }

        @Override
        public void clear() {
            clearThisWebsite();
        }

        @Override
        public boolean invalidate() {
            return clearThisWebsite();
        }

        /**
         * Removes the bound website's entries only. A cache whose native form cannot be walked
         * is refused rather than cleared for everyone: one website's edit must not empty every
         * other website's cache, and must not silently leave its own stale either.
         */
        private boolean clearThisWebsite() {
            String site = TenantContext.current().siteId();
            if (delegate.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine) {
                return caffeine.asMap().keySet().removeIf(k -> k instanceof TenantKey t && t.siteId().equals(site));
            }
            throw new UnsupportedOperationException("Cannot clear one website's entries from cache "
                    + delegate.getName() + " (" + delegate.getNativeCache().getClass().getName() + ")");
        }
    }
}
