package com.ticketsystem.catalog.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * F-09 containment: Redis errors degrade to database reads and never fail booking/catalog
 * correctness. Every failure is logged so degradation remains observable.
 */
public class ResilientCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ResilientCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        warn("get", cache, key, exception);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key,
                                    Object value) {
        warn("put", cache, key, exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        warn("evict", cache, key, exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        warn("clear", cache, "*", exception);
    }

    private void warn(String operation, Cache cache, Object key, RuntimeException exception) {
        log.warn("Redis catalog cache {} failed [cache={}, key={}]; continuing with PostgreSQL",
                operation, cache.getName(), key, exception);
    }
}
