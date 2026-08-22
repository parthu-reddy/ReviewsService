package com.fooddelivery.reviews.service;

import com.fooddelivery.reviews.dto.ReviewAggregateDto;
import com.fooddelivery.reviews.dto.ReviewResponseDto;
import com.fooddelivery.reviews.entity.EntityKey;
import com.fooddelivery.reviews.entity.ReviewAggregate;
import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.reviews.repository.AggregateRepository;
import com.fooddelivery.reviews.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewQueryService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final AggregateRepository aggregateRepository;
    private final ReviewRepository reviewRepository;

    private static final Duration AGGREGATE_TTL = Duration.ofHours(24);
    private static final Duration EMPTY_TTL = Duration.ofMinutes(5);
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    public static String getCacheKey(EntityType entityType, String entityId) {
        return String.format("review_aggregate:%s:%s", entityType, entityId);
    }

    public ReviewAggregateDto getAggregate(EntityType entityType, String entityId) {
        String redisKey = getCacheKey(entityType, entityId);

        // 1. Attempt Cache Read
        Object cachedObj = redisTemplate.opsForValue().get(redisKey);
        if (cachedObj instanceof ReviewAggregateDto) {
            log.debug("Cache hit for key: {}", redisKey);
            return (ReviewAggregateDto) cachedObj;
        }

        log.info("Cache miss for key: {}. Attempting to fetch from Database.", redisKey);

        // 2. Cache Stampede Protection (Distributed Lock)
        String lockKey = redisKey + ":lock";
        String token = UUID.randomUUID().toString();
        Boolean acquiredLock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);

        if (Boolean.TRUE.equals(acquiredLock)) {
            try {
                // We got the lock, fetch from DB
                return fetchFromDbAndWarmCache(entityType, entityId, redisKey);
            } finally {
                // Atomic fencing token check to avoid deleting a lock acquired by another thread
                String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
                stringRedisTemplate.execute(new DefaultRedisScript<>(script, Long.class), Collections.singletonList(lockKey), token);
            }
        } else {
            // 3. Fallback for blocked threads
            int retries = 2;
            while (retries > 0) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                
                cachedObj = redisTemplate.opsForValue().get(redisKey);
                if (cachedObj instanceof ReviewAggregateDto) {
                    return (ReviewAggregateDto) cachedObj;
                }
                retries--;
            }
            // If still missing after retries, fallback to DB to prevent starving the request
            return fetchFromDbAndWarmCache(entityType, entityId, redisKey);
        }
    }

    private ReviewAggregateDto fetchFromDbAndWarmCache(EntityType entityType, String entityId, String redisKey) {
        EntityKey id = new EntityKey(entityType, entityId);
        ReviewAggregate aggregate = aggregateRepository.findById(id).orElse(null);

        if (aggregate == null) {
            ReviewAggregateDto emptyDto = ReviewAggregateDto.builder()
                    .entityType(entityType)
                    .entityId(entityId)
                    .totalReviews(0L)
                    .averageRating(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .build();
            // Cache penetration protection
            redisTemplate.opsForValue().set(redisKey, emptyDto, EMPTY_TTL);
            return emptyDto;
        }

        ReviewAggregateDto dto = ReviewAggregateDto.builder()
                .entityType(aggregate.getId().getEntityType())
                .entityId(aggregate.getId().getEntityId())
                .totalReviews(aggregate.getTotalReviews())
                .averageRating(aggregate.getAverageRating())
                .build();

        redisTemplate.opsForValue().set(redisKey, dto, AGGREGATE_TTL);
        return dto;
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponseDto> getReviews(EntityType entityType, String entityId, Pageable pageable) {
        return reviewRepository.findByEntityTypeAndEntityId(entityType, entityId, pageable)
                .map(review -> ReviewResponseDto.builder()
                        .id(review.getReviewId())
                        .entityType(review.getEntityType())
                        .entityId(review.getEntityId())
                        .userId(review.getUserId())
                        .rating(review.getRating())
                        .comment(review.getComment())
                        .metadata(review.getMetadata())
                        .createdAt(review.getCreatedAt())
                        .build());
    }
}
