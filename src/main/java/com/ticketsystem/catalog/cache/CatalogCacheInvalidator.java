package com.ticketsystem.catalog.cache;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/** Best-effort explicit invalidation invoked only after an admin transaction commits. */
@Component
public class CatalogCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(CatalogCacheInvalidator.class);

    private final CacheManager cacheManager;

    public CatalogCacheInvalidator(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** A new event can change every cached page boundary and count. */
    public void afterCreate() {
        safelyClear(CatalogCaches.EVENT_PAGES);
    }

    /** Metadata/status changes affect detail and pages; a top-up also affects availability. */
    public void afterUpdate(UUID eventId, boolean inventoryChanged) {
        safelyEvict(CatalogCaches.EVENT_DETAILS, eventId);
        safelyClear(CatalogCaches.EVENT_PAGES);
        if (inventoryChanged) {
            safelyEvict(CatalogCaches.AVAILABILITY, eventId);
        }
    }

    private void safelyEvict(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.warn("Catalog cache {} is not configured; key {} was not invalidated",
                    cacheName, key);
            return;
        }
        try {
            cache.evict(key);
        } catch (RuntimeException failure) {
            log.warn("Catalog cache eviction failed [cache={}, key={}]; TTL bounds staleness",
                    cacheName, key, failure);
        }
    }

    private void safelyClear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.warn("Catalog cache {} is not configured; cached pages were not invalidated",
                    cacheName);
            return;
        }
        try {
            cache.clear();
        } catch (RuntimeException failure) {
            log.warn("Catalog cache clear failed [cache={}]; TTL bounds staleness",
                    cacheName, failure);
        }
    }
}
