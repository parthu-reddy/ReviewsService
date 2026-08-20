package com.fooddelivery.reviews.listener;

import com.fooddelivery.reviews.dto.ReviewAggregateDto;
import com.fooddelivery.reviews.event.AggregateUpdatedLocalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;

import com.fooddelivery.reviews.service.ReviewQueryService;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheUpdater {

    private final RedisTemplate<String, Object> redisTemplate;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAggregateUpdated(AggregateUpdatedLocalEvent event) {
        try {
            ReviewAggregateDto dto = event.getAggregateDto();
            String redisKey = ReviewQueryService.getCacheKey(dto.getEntityType(), dto.getEntityId());
            
            redisTemplate.delete(redisKey);
            log.info("Successfully evicted Redis cache for key: {}", redisKey);
        } catch (Exception e) {
            log.error("Failed to update Redis cache for aggregate update", e);
        }
    }
}
