package com.fooddelivery.reviews.service;

import com.fooddelivery.common.dto.order.OrderReviewContextDto;
import com.fooddelivery.common.dto.order.OrderReviewItemDto;
import com.fooddelivery.common.dto.order.OrderReviewAuthorizationResult;
import com.fooddelivery.common.dto.order.OrderReviewTargetAuthorizationRequest;
import com.fooddelivery.common.enums.RoleName;
import com.fooddelivery.reviews.dto.AggregateBatchDto;
import com.fooddelivery.reviews.dto.ReviewAggregateDto;
import com.fooddelivery.reviews.dto.ReviewDetailDto;
import com.fooddelivery.reviews.dto.ReviewDto;
import com.fooddelivery.reviews.dto.ReviewEligibilityDto;
import com.fooddelivery.reviews.dto.ReviewReceivedDto;
import com.fooddelivery.reviews.dto.ReviewTargetDto;
import com.fooddelivery.reviews.entity.EntityKey;
import com.fooddelivery.reviews.entity.Review;
import com.fooddelivery.reviews.entity.ReviewAggregate;
import com.fooddelivery.common.enums.ReviewEntityType;
import com.fooddelivery.reviews.enums.ReviewRejectionReason;
import com.fooddelivery.reviews.enums.ReviewVisibility;
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

    /** Labels the driver target. The order snapshot does not carry a rider name. */
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

    public static String getCacheKey(ReviewEntityType entityType, String entityId) {
        return String.format("review_aggregate:%s:%s", entityType, entityId);
    }

    public ReviewAggregateDto getAggregate(ReviewEntityType entityType, String entityId) {
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
    public AggregateBatchDto getAggregates(ReviewEntityType entityType, List<String> entityIds) {
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
    public Page<ReviewDto> getReviews(ReviewEntityType entityType, String entityId, Pageable pageable) {
        ReviewVisibility visibility = entityType == ReviewEntityType.RESTAURANT
                        || entityType == ReviewEntityType.PRODUCT
                ? ReviewVisibility.PUBLIC
                : ReviewVisibility.PRIVATE;
        return reviewRepository.findByEntityTypeAndEntityIdAndVisibility(
                        entityType, entityId, visibility, pageable)
                .map(ReviewMapper::toPublic);
    }

    /** Private feedback for a target, invoked only after the controller verifies the recipient. */
    @Transactional(readOnly = true)
    public Page<ReviewReceivedDto> getReceivedReviews(
            ReviewEntityType entityType, String entityId, Pageable pageable) {
        return reviewRepository.findByEntityTypeAndEntityIdAndVisibility(
                        entityType, entityId, ReviewVisibility.PRIVATE, pageable)
                .map(ReviewMapper::toReceived);
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
    public Page<ReviewDetailDto> getReviewsForAdmin(ReviewEntityType entityType, String entityId,
                                                    Pageable pageable) {
        return reviewRepository.findByEntityTypeAndEntityId(entityType, entityId, pageable)
                .map(ReviewMapper::toDetail);
    }

    /**
     * What this order participant may still say about this order, and what they have already said.
     *
     * <p>A refusal is returned as data rather than thrown: the sheet needs to render "the window
     * closed on the 24th" as readily as it renders the stars, and an exception would give the UI
     * only a status code to guess from.
     *
     * <p>Not {@code @Transactional}: resolving eligibility is a call to the order service, and
     * holding a database connection open across a network round trip is how a slow upstream becomes
     * an exhausted pool. The one repository read below carries its own transaction.
     */
    public ReviewEligibilityDto getEligibility(UUID orderId, String userId, RoleName authorRole) {
        OrderReviewContextDto context;
        try {
            context = eligibilityService.getOrderContext(orderId);
        } catch (ReviewNotAllowedException e) {
            return refused(orderId, e.getReason(), e.getMessage());
        }

        UUID reviewerId;
        try {
            reviewerId = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return refused(orderId, ReviewRejectionReason.NOT_YOUR_ORDER,
                    "You are not a participant in this order.");
        }

        // The order service owns the role-to-target matrix. Send the complete order target set and
        // let it return only the exact targets this actor may review; keeping a second matrix here
        // would drift as roles or order participants change.
        List<ReviewTargetDto> candidates = targetsFor(context, authorRole);
        if (candidates.isEmpty()) {
            return refused(orderId, ReviewRejectionReason.NOT_YOUR_ORDER,
                    "You are not a participant in this order.");
        }

        List<OrderReviewTargetAuthorizationRequest> targetRequests = candidates.stream()
                .map(target -> OrderReviewTargetAuthorizationRequest.builder()
                        .targetType(target.getEntityType())
                        .targetId(target.getEntityId())
                        .build())
                .toList();
        List<OrderReviewAuthorizationResult> decisions = eligibilityService.authorizeTargets(
                orderId, reviewerId, authorRole, targetRequests);
        Map<String, OrderReviewAuthorizationResult> decisionByTarget = decisions.stream()
                .collect(Collectors.toMap(d -> d.getTargetType() + ":" + d.getTargetId(), d -> d));

        List<ReviewTargetDto> allowedTargets = candidates.stream()
                .filter(target -> {
                    OrderReviewAuthorizationResult decision = decisionByTarget.get(targetKey(target));
                    return decision != null && decision.isAllowed();
                })
                .toList();
        if (allowedTargets.isEmpty()) {
            ReviewRejectionReason reason = rejectionReason(decisions);
            return refused(orderId, reason, rejectionDetail(reason));
        }

        try {
            eligibilityService.assertReviewWindowOpen(context);
        } catch (ReviewNotAllowedException e) {
            return refused(orderId, e.getReason(), e.getMessage());
        }

        Map<String, Review> existing = reviewRepository.findByOrderIdAndUserId(orderId, userId).stream()
                .collect(Collectors.toMap(r -> r.getEntityType() + ":" + r.getEntityId(),
                        r -> r, (a, b) -> a, LinkedHashMap::new));
        List<ReviewTargetDto> targets = allowedTargets.stream()
                .map(target -> addExistingReview(target, existing.get(targetKey(target))))
                .toList();

        return ReviewEligibilityDto.builder()
                .orderId(orderId)
                .reviewable(true)
                .windowClosesAt(eligibilityService.windowClosesAt(context))
                .targets(targets)
                .build();
    }

    private static List<ReviewTargetDto> targetsFor(OrderReviewContextDto context, RoleName role) {
        List<ReviewTargetDto> targets = new ArrayList<>();
        if (context.getCustomerId() != null) {
            targets.add(target(ReviewEntityType.CUSTOMER, context.getCustomerId().toString(), "Customer", role));
        }
        if (context.getRestaurantId() != null) {
            targets.add(target(ReviewEntityType.RESTAURANT, context.getRestaurantId().toString(),
                    context.getRestaurantName() != null ? context.getRestaurantName() : "Restaurant", role));
        }
        if (context.getDeliveryExecutiveId() != null) {
            targets.add(target(ReviewEntityType.DRIVER, context.getDeliveryExecutiveId().toString(),
                    DRIVER_DISPLAY_NAME, role));
        }
        if (context.getItems() != null) {
            context.getItems().stream()
                    .filter(item -> item.getMenuItemId() != null)
                    .collect(Collectors.toMap(item -> item.getMenuItemId().toString(),
                            item -> item, (a, b) -> a, LinkedHashMap::new))
                    .forEach((id, item) -> targets.add(
                            target(ReviewEntityType.PRODUCT, id, itemName(item), role)));
        }
        return targets;
    }

    private static String itemName(OrderReviewItemDto item) {
        return item.getName() != null && !item.getName().isBlank() ? item.getName() : "Item";
    }

    private static ReviewTargetDto target(ReviewEntityType type, String entityId, String displayName,
                                         RoleName authorRole) {
        return ReviewTargetDto.builder()
                .entityType(type)
                .entityId(entityId)
                .displayName(displayName)
                .visibility(ReviewVisibility.forReview(authorRole, type))
                .alreadyReviewed(false)
                .build();
    }

    private static ReviewTargetDto addExistingReview(ReviewTargetDto target, Review review) {
        return ReviewTargetDto.builder()
                .entityType(target.getEntityType())
                .entityId(target.getEntityId())
                .displayName(target.getDisplayName())
                .visibility(target.getVisibility())
                .alreadyReviewed(review != null)
                .existingRating(review != null ? review.getRating() : null)
                .existingComment(review != null ? review.getComment() : null)
                .existingReviewedAt(review != null ? review.getCreatedAt() : null)
                .build();
    }

    private static String targetKey(ReviewTargetDto target) {
        return target.getEntityType() + ":" + target.getEntityId();
    }

    private static ReviewRejectionReason rejectionReason(List<OrderReviewAuthorizationResult> decisions) {
        List<String> reasons = decisions.stream()
                .map(OrderReviewAuthorizationResult::getReasonCode)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (reasons.contains("ORDER_NOT_DELIVERED")) return ReviewRejectionReason.ORDER_NOT_DELIVERED;
        if (reasons.contains("ACTOR_NOT_PARTICIPANT")) return ReviewRejectionReason.NOT_YOUR_ORDER;
        if (reasons.contains("SELF_REVIEW")) return ReviewRejectionReason.SELF_REVIEW;
        if (reasons.contains("ROLE_TARGET_NOT_ALLOWED")) return ReviewRejectionReason.ROLE_TARGET_NOT_ALLOWED;
        return ReviewRejectionReason.TARGET_NOT_ON_ORDER;
    }

    private static String rejectionDetail(ReviewRejectionReason reason) {
        return switch (reason) {
            case ORDER_NOT_DELIVERED -> "This order has not been delivered yet.";
            case SELF_REVIEW -> "You cannot leave feedback about yourself.";
            case ROLE_TARGET_NOT_ALLOWED -> "Your account role cannot review these order targets.";
            case TARGET_NOT_ON_ORDER -> "There are no reviewable targets on this order.";
            default -> "You are not a participant in this order.";
        };
    }

    private static ReviewEligibilityDto refused(UUID orderId, ReviewRejectionReason reason, String detail) {
        return ReviewEligibilityDto.builder()
                .orderId(orderId)
                .reviewable(false)
                .reason(reason)
                .reasonDetail(detail)
                .targets(List.of())
                .build();
    }

    /**
     * Deliberately carries no {@code @Transactional}. It is called from {@link #getAggregate},
     * which is a call on {@code this} and therefore never passes through the proxy — the annotation
     * would be decoration, not behaviour. It makes exactly one repository call, and
     * {@code SimpleJpaRepository} is already {@code @Transactional(readOnly = true)}, so the read
     * is transactional where it actually happens.
     */
    private ReviewAggregateDto fetchFromDbAndWarmCache(ReviewEntityType entityType, String entityId,
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

    private static ReviewAggregateDto emptyAggregate(ReviewEntityType entityType, String entityId) {
        return ReviewAggregateDto.builder()
                .entityType(entityType)
                .entityId(entityId)
                .totalReviews(0L)
                .averageRating(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .build();
    }
}
