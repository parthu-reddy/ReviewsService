package com.fooddelivery.reviews.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration
public class Bucket4jConfig {

    @Bean
    @org.springframework.context.annotation.Lazy
    public LettuceBasedProxyManager<byte[]> lettuceBasedProxyManager(org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory) {
        if (!(connectionFactory instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory)) {
            throw new com.fooddelivery.reviews.exception.ExternalServiceUnavailableException("Expected LettuceConnectionFactory");
        }
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisConnectionFactory = (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) connectionFactory;
        Object nativeClient = redisConnectionFactory.getNativeClient();
        if (nativeClient instanceof io.lettuce.core.RedisClient) {
            return LettuceBasedProxyManager.builderFor((io.lettuce.core.RedisClient) nativeClient)
                    .withExpirationStrategy(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(60)))
                    .build();
        } else if (nativeClient instanceof io.lettuce.core.cluster.RedisClusterClient) {
            return LettuceBasedProxyManager.builderFor((io.lettuce.core.cluster.RedisClusterClient) nativeClient)
                    .withExpirationStrategy(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(60)))
                    .build();
        }
        throw new com.fooddelivery.reviews.exception.ExternalServiceUnavailableException("Unsupported Redis native client: " + (nativeClient != null ? nativeClient.getClass().getName() : "null"));
    }
}
