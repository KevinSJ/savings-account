package com.takehome.savings.config;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.takehome.savings.domain.SavingsAccount;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Redis-backed cache in front of GET reads, with three deliberate choices:
 *
 * <ol>
 *   <li><b>Bounded staleness:</b> entries live 10 minutes. There is no mutation endpoint
 *       yet, but a TTL keeps the design correct for when one arrives (write path would
 *       evict/update the entry; the TTL is the safety net for bugs and multi-node races).</li>
 *   <li><b>Typed serialisation:</b> the account cache serialises with
 *       {@link Jackson2JsonRedisSerializer} bound to the domain entity and Boot's
 *       {@link ObjectMapper} (Java time module included). No polymorphic type headers -
 *       the cache knows exactly one value shape. Caching a JPA entity is safe here
 *       because it is all scalar fields with no lazy relations; a fetch-joined or
 *       lazy-loaded entity would forbid this shortcut.</li>
 *   <li><b>Graceful degradation:</b> a dead Redis must not take reads down - errors are
 *       logged and reads fall through to Postgres (see {@link FaultTolerantCacheErrorHandler}).
 *       {@code transactionAware()} defers cache writes to after-commit so failed
 *       transactions never poison the cache.</li>
 * </ol>
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    public static final String ACCOUNTS_CACHE = "accounts";
    public static final String KEY_PREFIX = "savings:";
    public static final Duration ENTRY_TTL = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public RedisCacheManagerBuilderCustomizer accountsCacheCustomizer(ObjectMapper objectMapper) {
        var serializer = new Jackson2JsonRedisSerializer<>(objectMapper, SavingsAccount.class);
        RedisSerializationContext.SerializationPair<SavingsAccount> values =
                RedisSerializationContext.SerializationPair.fromSerializer(serializer);

        RedisCacheConfiguration accounts = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ENTRY_TTL)
                .disableCachingNullValues()
                .computePrefixWith(name -> KEY_PREFIX + name + ":")
                .serializeValuesWith(values);

        return builder -> builder
                .withCacheConfiguration(ACCOUNTS_CACHE, accounts)
                .transactionAware();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new FaultTolerantCacheErrorHandler();
    }

    /**
     * Reads/writes that fail because Redis is unreachable are swallowed (after logging)
     * so the underlying method still runs against Postgres. Only programming errors
     * (illegal keys, serialisation bugs) are surfaced, to be caught in tests.
     */
    static final class FaultTolerantCacheErrorHandler implements CacheErrorHandler {

        private static final Logger log = LoggerFactory.getLogger(FaultTolerantCacheErrorHandler.class);

        @Override
        public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
            if (isConnectivityProblem(exception)) {
                log.warn("Cache '{}' GET failed (redis unreachable?), falling back to datastore: {}",
                        cache.getName(), shortMessage(exception));
                return;
            }
            throw exception;
        }

        @Override
        public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
            if (isConnectivityProblem(exception)) {
                log.warn("Cache '{}' PUT failed (redis unreachable?), continuing without cache: {}",
                        cache.getName(), shortMessage(exception));
                return;
            }
            throw exception;
        }

        @Override
        public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
            if (isConnectivityProblem(exception)) {
                log.warn("Cache '{}' EVICT failed: {}", cache.getName(), shortMessage(exception));
                return;
            }
            throw exception;
        }

        @Override
        public void handleCacheClearError(RuntimeException exception, Cache cache) {
            if (isConnectivityProblem(exception)) {
                log.warn("Cache '{}' CLEAR failed: {}", cache.getName(), shortMessage(exception));
                return;
            }
            throw exception;
        }

        private boolean isConnectivityProblem(RuntimeException ex) {
            Throwable cause = ex;
            while (cause != null) {
                if (cause instanceof java.io.IOException
                        || cause instanceof io.lettuce.core.RedisException
                        || cause instanceof org.springframework.data.redis.RedisConnectionFailureException) {
                    return true;
                }
                cause = cause.getCause();
            }
            return false;
        }

        private String shortMessage(Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}
