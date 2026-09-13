package com.fooddelivery.reviews.service;

import com.fooddelivery.common.dto.order.OrderReviewContextDto;
import com.fooddelivery.common.dto.order.OrderReviewItemDto;
import com.fooddelivery.reviews.dto.AggregateBatchDto;
import com.fooddelivery.reviews.dto.ReviewAggregateDto;
import com.fooddelivery.reviews.dto.ReviewDetailDto;
import com.fooddelivery.reviews.dto.ReviewDto;
import com.fooddelivery.reviews.dto.ReviewEligibilityDto;
import com.fooddelivery.reviews.dto.ReviewTargetDto;
import com.fooddelivery.reviews.entity.EntityKey;
import com.fooddelivery.reviews.entity.Review;
import com.fooddelivery.reviews.entity.ReviewAggregate;
import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.reviews.exception.ReviewNotAllowedException;
import com.fooddelivery.reviews.mapper.ReviewMapper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewQueryService {

    /** Labels the driver target. The customer-side order records no rider name to use instead. */
    public static final String DRIVER_DISPLAY_NAME = "Delivery partner";

    /** A batch request beyond this is rejected rather than turned into an unbounded IN clause. */
    public static final int MAX_BATCH_IDS = 100;

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final AggregateRepository aggregateRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewEligibilityService eligibilityService;

    private static final Duration AGGREGATE_TTL = Duration.ofHours(24);
    private static final Duration EMPTY_TTL = Duration.ofMinutes(5);
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    public static String getCacheKey(EntityType entityType, String entityId) {
        return String.format("review_aggregate:%s:%s", entityType, entityId);
    }

    public ReviewAggregateDto getAggregate(EntityType entityType, String entityId) {
        String redisKey = getCacheKey(entityType, entityId);

        Object cachedObj = redisTemplate.opsForValue().get(redisKey);
        if (cachedObj instanceof ReviewAggregateDto cached) {
            log.debug("Cache hit for key: {}", redisKey);
            return cached;
        }

        log.debug("Cache miss for key: {}; falling through to the database", redisKey);

        // Stampede protection: one caller loads, the rest wait briefly for it.
        String lockKey = redisKey + ":lock";
        String token = UUID.randomUUID().toString();
        Boolean acquiredLock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);

        if (Boolean.TRUE.equals(acquiredLock)) {
            try {
                return fetchFromDbAndWarmCache(entityType, entityId, redisKey);
            } finally {
                // Fenced delete: release only a lock this thread still owns, so a slow loader whose
                // TTL expired cannot delete the lock a second loader has since taken.
                String script = "if redis.call('get', KEYS[1]) == ARGV[1] "
                        + "then return redis.call('del', KEYS[1]) else return 0 end";
                stringRedisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
                        Collections.singletonList(lockKey), token);
            }
        }

        int retries = 2;
        while (retries > 0) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            cachedObj = redisTemplate.opsForValue().get(redisKey);
            if (cachedObj instanceof ReviewAggregateDto cached) {
                return cached;
            }
            retries--;
        }
        // The lock holder never filled it — it failed, or is slower than we are willing to wait.
        // Reading through is better than starving the request.
        return fetchFromDbAndWarmCache(entityType, entityId, redisKey);
    }

    /**
     * Averages for many entities at once.
     *
     * <p>Reads the database directly rather than looping {@link #getAggregate}: the point of this
     * endpoint is one round trip, and a per-id cache lookup would restore the fan-out it exists to
     * remove. Ids with no reviews come back as an explicit zero, so the caller can tell "no reviews
     * yet" from "id dropped".
     */
    @Transactional(readOnly = true)
    public AggregateBatchDto getAggregates(EntityType entityType, List<String> entityIds) {
        List<String> distinct = entityIds.stream().filter(id -> id != null && !id.isBlank())
                .distinct().toList();

        Map<String, ReviewAggregateDto> found = aggregateRepository
                .findAllById(distinct.stream().map(id -> new EntityKey(entityType, id)).toList())
                .stream()
                .collect(Collectors.toMap(a -> a.getId().getEntityId(), ReviewQueryService::toDto));

        List<ReviewAggregateDto> aggregates = distinct.stream()
                .map(id -> found.getOrDefault(id, emptyAggregate(entityType, id)))
                .toList();

        return AggregateBatchDto.builder().entityType(entityType).aggregates(aggregates).build();
    }

    @Transactional(readOnly = true)
    public Page<ReviewDto> getReviews(EntityType entityType, String entityId, Pageable pageable) {
        return reviewRepository.findByEntityTypeAndEntityId(entityType, entityId, pageable)
                .map(ReviewMapper::toPublic);
    }

    /**
     * Every review written by one account, unredacted.
     *
     * <p>Serves two callers with the same shape for the same reason: {@code /reviews/me}, where the
     * author is reading their own, and the admin surface, where following a review back to its
     * author is the whole point. Authorization is the caller's business — this method does not
     * decide who may ask.
     */
    @Transactional(readOnly = true)
    public Page<ReviewDetailDto> getMyReviews(String userId, Pageable pageable) {
        return reviewRepository.findByUserId(userId, pageable).map(ReviewMapper::toDetail);
    }

    /** Reviews of one entity with author and order intact. Admin-only; see the controller. */
    @Transactional(readOnly = true)
    public Page<ReviewDetailDto> getReviewsForAdmin(EntityType entityType, String entityId,
                                                    Pageable pageable) {
        return reviewRepository.findByEntityTypeAndEntityId(entityType, entityId, pageable)
                .map(ReviewMapper::toDetail);
    }

    /**
     * What this customer may still say about this order, and what they have already said.
     *
     * <p>A refusal is returned as data rather than thrown: the sheet needs to render "the window
     * closed on the 24th" as readily as it renders the stars, and an exception would give the UI
     * only a status code to guess from.
     *
     * <p>Not {@code @Transactional}: resolving eligibility is a call to the order service, and
     * holding a database connection open across a network round trip is how a slow upstream becomes
     * an exhausted pool. The one repository read below carries its own transaction.
     */
    public ReviewEligibilityDto getEligibility(UUID orderId, String userId) {
        OrderReviewContextDto context;
        try {
            context = eligibilityService.resolve(orderId, userId);
        } catch (ReviewNotAllowedException e) {
            return ReviewEligibilityDto.builder()
                    .orderId(orderId)
                    .reviewable(false)
                    .reason(e.getReason())
                    .reasonDetail(e.getMessage())
                    .targets(List.of())
                    .build();
        }

        Map<String, Review> existing = reviewRepository.findByOrderId(orderId).stream()
                .collect(Collectors.toMap(r -> r.getEntityType() + ":" + r.getEntityId(),
                        r -> r, (a, b) -> a, LinkedHashMap::new));

        List<ReviewTargetDto> targets = new ArrayList<>();
        if (context.getRestaurantId() != null) {
            targets.add(target(EntityType.RESTAURANT, context.getRestaurantId().toString(),
                    context.getRestaurantName() != null ? context.getRestaurantName() : "Restaurant",
                    existing));
        }
        if (context.getDeliveryExecutiveId() != null) {
            targets.add(target(EntityType.DRIVER, context.getDeliveryExecutiveId().toString(),
                    DRIVER_DISPLAY_NAME, existing));
        }
        if (context.getItems() != null) {
            // Distinct by menu item: an order with the same dish twice is one reviewable product.
            context.getItems().stream()
                    .filter(item -> item.getMenuItemId() != null)
                    .collect(Collectors.toMap(item -> item.getMenuItemId().toString(),
                            item -> item, (a, b) -> a, LinkedHashMap::new))
                    .forEach((id, item) -> targets.add(
                            target(EntityType.PRODUCT, id, itemName(item), existing)));
        }

        return ReviewEligibilityDto.builder()
                .orderId(orderId)
                .reviewable(true)
                .windowClosesAt(eligibilityService.windowClosesAt(context))
                .targets(targets)
                .build();
    }

    private static String itemName(OrderReviewItemDto item) {
        return item.getName() != null && !item.getName().isBlank() ? item.getName() : "Item";
    }

    private static ReviewTargetDto target(EntityType type, String entityId, String displayName,
                                          Map<String, Review> existing) {
        Review review = existing.get(type + ":" + entityId);
        return ReviewTargetDto.builder()
                .entityType(type)
                .entityId(entityId)
                .displayName(displayName)
                .alreadyReviewed(review != null)
                .existingRating(review != null ? review.getRating() : null)
                .existingComment(review != null ? review.getComment() : null)
                .existingReviewedAt(review != null ? review.getCreatedAt() : null)
                .build();
    }

    /**
     * Deliberately carries no {@code @Transactional}. It is called from {@link #getAggregate},
     * which is a call on {@code this} and therefore never passes through the proxy — the annotation
     * would be decoration, not behaviour. It makes exactly one repository call, and
     * {@code SimpleJpaRepository} is already {@code @Transactional(readOnly = true)}, so the read
     * is transactional where it actually happens.
     */
    private ReviewAggregateDto fetchFromDbAndWarmCache(EntityType entityType, String entityId,
                                                       String redisKey) {
        ReviewAggregate aggregate = aggregateRepository.findById(new EntityKey(entityType, entityId))
                .orElse(null);

        if (aggregate == null) {
            ReviewAggregateDto emptyDto = emptyAggregate(entityType, entityId);
            // Cached too, on a short TTL: without it, every request for an entity nobody has
            // reviewed reaches the database, which is most entities most of the time.
            redisTemplate.opsForValue().set(redisKey, emptyDto, EMPTY_TTL);
            return emptyDto;
        }

        ReviewAggregateDto dto = toDto(aggregate);
        redisTemplate.opsForValue().set(redisKey, dto, AGGREGATE_TTL);
        return dto;
    }

    private static ReviewAggregateDto toDto(ReviewAggregate aggregate) {
        return ReviewAggregateDto.builder()
                .entityType(aggregate.getId().getEntityType())
                .entityId(aggregate.getId().getEntityId())
                .totalReviews(aggregate.getTotalReviews())
                .averageRating(aggregate.getAverageRating())
                .build();
    }

    private static ReviewAggregateDto emptyAggregate(EntityType entityType, String entityId) {
        return ReviewAggregateDto.builder()
                .entityType(entityType)
                .entityId(entityId)
                .totalReviews(0L)
                .averageRating(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .build();
    }
}
