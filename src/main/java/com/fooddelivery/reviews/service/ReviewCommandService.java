package com.fooddelivery.reviews.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.AggregateType;
import com.fooddelivery.common.constants.EventPayloadConstants;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.dto.order.OrderReviewAuthorizationResult;
import com.fooddelivery.common.dto.order.OrderReviewContextDto;
import com.fooddelivery.common.dto.order.OrderReviewTargetAuthorizationRequest;
import com.fooddelivery.common.enums.ReviewEntityType;
import com.fooddelivery.common.enums.OutboxStatus;
import com.fooddelivery.common.enums.RoleName;
import com.fooddelivery.common.outbox.entity.OutboxEventEntity;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.reviews.dto.CreateReviewRequest;
import com.fooddelivery.reviews.dto.ReviewAggregateDto;
import com.fooddelivery.reviews.dto.ReviewDetailDto;
import com.fooddelivery.reviews.dto.ReviewEntryRequest;
import com.fooddelivery.reviews.entity.EntityKey;
import com.fooddelivery.reviews.entity.Review;
import com.fooddelivery.reviews.entity.ReviewAggregate;
import com.fooddelivery.reviews.enums.ReviewRejectionReason;
import com.fooddelivery.reviews.enums.ReviewVisibility;
import com.fooddelivery.reviews.event.AggregateUpdatedLocalEvent;
import com.fooddelivery.reviews.exception.ReviewEventSerializationException;
import com.fooddelivery.reviews.exception.ReviewNotAllowedException;
import com.fooddelivery.reviews.repository.AggregateRepository;
import com.fooddelivery.reviews.repository.ReviewRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes reviews. There is no method here that changes one, and there is no caller that could.
 *
 * <p>The whole submission for an order commits together. Because a review is immutable, a partially
 * accepted submission would leave the author permanently unable to repair the half that failed —
 * so either every entry lands or none does.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewCommandService {

    private final ReviewRepository reviewRepository;
    private final AggregateRepository aggregateRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ReviewEligibilityService eligibilityService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * Records every review in the request.
     *
     * <p>Eligibility is resolved before the transaction opens: it is a network call, and holding a
     * database transaction across one is how a slow upstream turns into exhausted connections.
     *
     * <p>Retried on optimistic-lock failure — two participants reviewing the same outlet at the same
     * moment both bump its aggregate, and one of them loses the version race. A retry re-enters
     * this method and mints fresh review ids, which is harmless: identity for the purposes of "has
     * this already been reviewed" is {@code (entityType, entityId, orderId, userId)}, not the generated id.
     */
    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class)
    public List<ReviewDetailDto> createReviews(CreateReviewRequest request, String userId, RoleName authorRole) {
        log.info("Creating {} review(s) for order={} by user={}",
                request.getEntries().size(), request.getOrderId(), userId);

        UUID reviewerId;
        try {
            reviewerId = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.NOT_YOUR_ORDER,
                    "The authenticated account cannot review this order.");
        }
        String authorId = reviewerId.toString();

        if (authorRole == null || authorRole == RoleName.ADMIN) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.ROLE_TARGET_NOT_ALLOWED,
                    "This account role cannot submit order reviews.");
        }

        // Reject a payload that rates the same thing twice before touching the database: the unique
        // index would catch it, but as a 409 that names neither entry.
        List<ReviewEntryRequest> entries = request.getEntries().stream()
                .map(ReviewCommandService::canonicalizeEntry)
                .toList();
        Set<String> seen = new HashSet<>();
        List<OrderReviewTargetAuthorizationRequest> requestedTargets = new ArrayList<>();
        for (ReviewEntryRequest entry : entries) {
            if (!seen.add(entry.getEntityType() + ":" + entry.getEntityId())) {
                throw new ReviewNotAllowedException(ReviewRejectionReason.DUPLICATE_ENTRY,
                        entry.getEntityType() + " " + entry.getEntityId()
                                + " appears more than once in this submission.");
            }
            requestedTargets.add(OrderReviewTargetAuthorizationRequest.builder()
                    .targetType(entry.getEntityType())
                    .targetId(entry.getEntityId())
                    .build());
        }

        List<OrderReviewAuthorizationResult> authorizations = eligibilityService.authorizeTargets(
                request.getOrderId(), reviewerId, authorRole, requestedTargets);
        OrderReviewAuthorizationResult denied = authorizations.stream()
                .filter(result -> !result.isAllowed())
                .findFirst()
                .orElse(null);
        if (denied != null) {
            throw authorizationFailure(denied);
        }

        OrderReviewContextDto context = eligibilityService.getOrderContext(request.getOrderId());
        eligibilityService.assertReviewWindowOpen(context);

        String authorDisplayName = authorRole == RoleName.CUSTOMER
                ? ReviewEligibilityService.toDisplayName(context.getCustomerName())
                : null;
        Instant createdAt = Instant.now();
        List<UUID> reviewIds = entries.stream().map(e -> UUID.randomUUID()).toList();

        List<ReviewAggregateDto> updatedAggregates = new ArrayList<>();
        List<ReviewDetailDto> written = transactionTemplate.execute(status -> {
            rejectAlreadyReviewed(request.getOrderId(), entries, authorId);

            List<ReviewDetailDto> results = new ArrayList<>();
            List<Review> reviews = new ArrayList<>();
            // Keyed so that entries sharing an aggregate row fold into one in-memory aggregate
            // rather than two copies that overwrite each other on flush.
            Map<EntityKey, ReviewAggregate> aggregates = new LinkedHashMap<>();

            for (int i = 0; i < entries.size(); i++) {
                ReviewEntryRequest entry = entries.get(i);
                UUID reviewId = reviewIds.get(i);
                ReviewVisibility visibility = ReviewVisibility.forReview(authorRole, entry.getEntityType());

                reviews.add(Review.builder()
                        .entityType(entry.getEntityType())
                        .entityId(entry.getEntityId())
                        .id(reviewId)
                        .orderId(request.getOrderId())
                        .userId(authorId)
                        .authorRole(authorRole)
                        .visibility(visibility)
                        .authorDisplayName(visibility == ReviewVisibility.PUBLIC ? authorDisplayName : null)
                        .rating(entry.getRating())
                        .comment(entry.getComment())
                        .createdAt(createdAt)
                        .build());

                EntityKey key = new EntityKey(entry.getEntityType(), entry.getEntityId());
                if (contributesToAggregate(entry.getEntityType(), visibility)) {
                    ReviewAggregate aggregate = aggregates.computeIfAbsent(key,
                            k -> aggregateRepository.findById(k).orElseGet(() -> new ReviewAggregate(k)));
                    aggregate.addReview(entry.getRating());
                }

                results.add(ReviewDetailDto.builder()
                        .id(reviewId)
                        .entityType(entry.getEntityType())
                        .entityId(entry.getEntityId())
                        .orderId(request.getOrderId())
                        .userId(authorId)
                        .authorRole(authorRole)
                        .visibility(visibility)
                        .authorDisplayName(visibility == ReviewVisibility.PUBLIC ? authorDisplayName : null)
                        .rating(entry.getRating())
                        .comment(entry.getComment())
                        .createdAt(createdAt)
                        .build());
            }

            // Flushed on their own, before the aggregates, so that a uq_reviews_entity_order_author
            // violation is attributable. Left to flush with the aggregate below, the same exception
            // type would arrive at the aggregate's catch block and be retried as a version race
            // until the attempts ran out — reporting "please retry" for something no retry fixes.
            try {
                reviewRepository.saveAllAndFlush(reviews);
            } catch (DataIntegrityViolationException e) {
                // Lost a race with a concurrent submission for the same author, order and target. The
                // caller is told what is true: it is already reviewed.
                throw new ReviewNotAllowedException(ReviewRejectionReason.ALREADY_REVIEWED,
                        "One of these targets has already been reviewed for order "
                                + request.getOrderId() + ".");
            }

            for (ReviewAggregate aggregate : aggregates.values()) {
                try {
                    aggregateRepository.saveAndFlush(aggregate);
                } catch (DataIntegrityViolationException e) {
                    // Two first-ever reviews for the same entity, racing on the primary key. The
                    // loser retries and finds the row the winner inserted.
                    log.warn("First-insert race detected for entity {}, retrying", aggregate.getId());
                    throw new ObjectOptimisticLockingFailureException(
                            ReviewAggregate.class, aggregate.getId());
                }
                updatedAggregates.add(toAggregateDto(aggregate));
            }

            // One outbox row per review, inside the same transaction as the review itself. Written
            // after the aggregates are flushed so each payload carries the post-write totals a
            // consumer can assign verbatim.
            for (int i = 0; i < entries.size(); i++) {
                ReviewEntryRequest entry = entries.get(i);
                if (ReviewVisibility.forReview(authorRole, entry.getEntityType()) != ReviewVisibility.PUBLIC) {
                    continue;
                }
                ReviewAggregate aggregate =
                        aggregates.get(new EntityKey(entry.getEntityType(), entry.getEntityId()));
                writeOutboxEvent(reviewIds.get(i), request.getOrderId(), authorId, entry, aggregate, createdAt);
            }

            return results;
        });

        // AFTER_COMMIT listeners evict the cached aggregate. Published outside the transaction
        // template's lambda only in the sense that the events fire on commit; publishing here keeps
        // the ordering obvious.
        updatedAggregates.forEach(dto ->
                eventPublisher.publishEvent(new AggregateUpdatedLocalEvent(this, dto)));

        log.info("Recorded {} review(s) for order={}", written == null ? 0 : written.size(),
                request.getOrderId());
        return written == null ? List.of() : written;
    }

    /**
     * Gate E7, checked before writing so the refusal names the reason rather than surfacing as a
     * constraint violation. The unique index remains the backstop for the concurrent case.
     */
    private static ReviewEntryRequest canonicalizeEntry(ReviewEntryRequest entry) {
        if (entry == null || entry.getEntityType() == null || entry.getEntityId() == null
                || entry.getEntityId().isBlank()) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.TARGET_NOT_ON_ORDER,
                    "A review target must identify an entity on the order.");
        }
        try {
            // Every current target identifier (outlet, user, or master menu item) is a UUID.
            // Normalize before authorization and persistence so alternate UUID casing cannot
            // bypass duplicate checks or create separate aggregates for the same target.
            String canonicalId = UUID.fromString(entry.getEntityId().trim()).toString();
            return ReviewEntryRequest.builder()
                    .entityType(entry.getEntityType())
                    .entityId(canonicalId)
                    .rating(entry.getRating())
                    .comment(entry.getComment())
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.TARGET_NOT_ON_ORDER,
                    entry.getEntityType() + " " + entry.getEntityId() + " was not part of this order.");
        }
    }

    private void rejectAlreadyReviewed(UUID orderId, List<ReviewEntryRequest> entries, String userId) {
        List<Review> existing = reviewRepository.findByOrderIdAndUserId(orderId, userId);
        if (existing.isEmpty()) {
            return;
        }
        Set<String> reviewed = existing.stream()
                .map(r -> r.getEntityType() + ":" + r.getEntityId())
                .collect(java.util.stream.Collectors.toSet());

        for (ReviewEntryRequest entry : entries) {
            if (reviewed.contains(entry.getEntityType() + ":" + entry.getEntityId())) {
                throw new ReviewNotAllowedException(ReviewRejectionReason.ALREADY_REVIEWED,
                        entry.getEntityType() + " " + entry.getEntityId()
                                + " has already been reviewed for order " + orderId
                                + ". Reviews cannot be changed once submitted.");
            }
        }
    }

    private static ReviewNotAllowedException authorizationFailure(OrderReviewAuthorizationResult result) {
        String target = result.getTargetType() + " " + result.getTargetId();
        return switch (result.getReasonCode() == null ? "" : result.getReasonCode()) {
            case "ORDER_NOT_DELIVERED" -> new ReviewNotAllowedException(
                    ReviewRejectionReason.ORDER_NOT_DELIVERED, "The order has not been delivered.");
            case "ACTOR_NOT_PARTICIPANT" -> new ReviewNotAllowedException(
                    ReviewRejectionReason.NOT_YOUR_ORDER, "You are not a participant in this order.");
            case "ROLE_TARGET_NOT_ALLOWED" -> new ReviewNotAllowedException(
                    ReviewRejectionReason.ROLE_TARGET_NOT_ALLOWED,
                    "Your account role cannot review " + target + ".");
            case "SELF_REVIEW" -> new ReviewNotAllowedException(
                    ReviewRejectionReason.SELF_REVIEW, "You cannot review yourself.");
            default -> new ReviewNotAllowedException(
                    ReviewRejectionReason.TARGET_NOT_ON_ORDER,
                    target + " was not part of this order.");
        };
    }

    private static boolean contributesToAggregate(ReviewEntityType entityType, ReviewVisibility visibility) {
        return visibility == ReviewVisibility.PUBLIC || entityType == ReviewEntityType.DRIVER;
    }

    private static ReviewAggregateDto toAggregateDto(ReviewAggregate aggregate) {
        return ReviewAggregateDto.builder()
                .entityType(aggregate.getId().getEntityType())
                .entityId(aggregate.getId().getEntityId())
                .totalReviews(aggregate.getTotalReviews())
                .averageRating(aggregate.getAverageRating())
                .build();
    }

    private void writeOutboxEvent(UUID reviewId, UUID orderId, String userId,
                                  ReviewEntryRequest entry, ReviewAggregate aggregate,
                                  Instant createdAt) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(EventPayloadConstants.REVIEW_ID, reviewId.toString());
            payload.put(EventPayloadConstants.ORDER_ID, orderId.toString());
            payload.put(EventPayloadConstants.ENTITY_TYPE, entry.getEntityType().name());
            payload.put(EventPayloadConstants.ENTITY_ID, entry.getEntityId());
            payload.put(EventPayloadConstants.USER_ID, userId);
            payload.put(EventPayloadConstants.RATING, entry.getRating());
            // Absolute, not a delta. A consumer assigns these, so a redelivered event is idempotent
            // by construction rather than by remembering it has seen the event before.
            payload.put(EventPayloadConstants.TOTAL_REVIEWS, aggregate.getTotalReviews());
            payload.put(EventPayloadConstants.AVERAGE_RATING, aggregate.getAverageRating().toPlainString());
            payload.put(EventPayloadConstants.TIMESTAMP, createdAt.toString());

            // Include the author: multiple participants may review the same target on the same order.
            String idempotencyKey = String.format("review:%s:%s:%s:%s",
                    entry.getEntityType(), entry.getEntityId(), orderId, userId);

            outboxEventRepository.save(OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType(AggregateType.REVIEW)
                    .aggregateId(entry.getEntityId())
                    .eventType(EventType.REVIEW_CREATED)
                    .idempotencyKey(idempotencyKey)
                    .payload(objectMapper.writeValueAsString(payload))
                    .createdAt(Instant.now())
                    .status(OutboxStatus.UNPROCESSED)
                    .retryCount(0)
                    .build());
        } catch (JsonProcessingException e) {
            throw new ReviewEventSerializationException(
                    "Failed to serialize review outbox event payload", e);
        }
    }
}
