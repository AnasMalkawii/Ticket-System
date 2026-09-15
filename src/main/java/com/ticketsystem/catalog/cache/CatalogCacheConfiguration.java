package com.ticketsystem.catalog.cache;

import java.util.Map;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

/** Redis-backed, bounded, JSON catalog caches with fail-soft error handling. */
@Configuration(proxyBeanMethods = false)
@EnableCaching
@EnableConfigurationProperties(CatalogCacheProperties.class)
public class CatalogCacheConfiguration implements CachingConfigurer {

    private final RedisConnectionFactory connectionFactory;
    private final CatalogCacheProperties properties;
    private final CacheErrorHandler errorHandler = new ResilientCacheErrorHandler();

    public CatalogCacheConfiguration(RedisConnectionFactory connectionFactory,
                                     CatalogCacheProperties properties) {
        this.connectionFactory = connectionFactory;
        this.properties = properties;
    }

    @Bean("catalogCacheManager")
    @Override
    public CacheManager cacheManager() {
        RedisSerializationContext.SerializationPair<String> keys =
                RedisSerializationContext.SerializationPair.fromSerializer(
                        RedisSerializer.string());
        RedisSerializationContext.SerializationPair<Object> values =
                RedisSerializationContext.SerializationPair.fromSerializer(
                        RedisSerializer.json());

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(keys)
                .serializeValuesWith(values)
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> "ticketing::" + cacheName + "::");

        Map<String, RedisCacheConfiguration> caches = Map.of(
                CatalogCaches.EVENT_DETAILS, base.entryTtl(properties.eventTtl()),
                CatalogCaches.EVENT_PAGES, base.entryTtl(properties.eventTtl()),
                CatalogCaches.AVAILABILITY, base.entryTtl(properties.availabilityTtl()));

        RedisCacheWriter writer = RedisCacheWriter.nonLockingRedisCacheWriter(
                connectionFactory, BatchStrategies.scan(1_000));
        return RedisCacheManager.builder(writer)
                .cacheDefaults(base.entryTtl(properties.eventTtl()))
                .withInitialCacheConfigurations(caches)
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return errorHandler;
    }
}
