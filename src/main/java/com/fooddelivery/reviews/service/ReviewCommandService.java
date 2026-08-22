package com.fooddelivery.reviews.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.client.IdentityServiceClient;
import com.fooddelivery.common.client.RestaurantServiceClient;
import com.fooddelivery.common.dto.identity.IdentityUserDTO;
import com.fooddelivery.common.constants.AggregateType;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.common.enums.OutboxStatus;
import com.fooddelivery.common.outbox.entity.OutboxEventEntity;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.reviews.dto.CreateReviewRequest;
import com.fooddelivery.reviews.dto.ReviewAggregateDto;
import com.fooddelivery.reviews.dto.ReviewResponseDto;
import com.fooddelivery.reviews.entity.EntityKey;
import com.fooddelivery.reviews.entity.Review;
import com.fooddelivery.reviews.entity.ReviewAggregate;
import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.reviews.event.AggregateUpdatedLocalEvent;
import com.fooddelivery.reviews.repository.AggregateRepository;
import com.fooddelivery.reviews.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewCommandService {

    private final ReviewRepository reviewRepository;
    private final AggregateRepository aggregateRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final RestaurantServiceClient restaurantServiceClient;
    private final IdentityServiceClient identityServiceClient;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * Creates a new review.
     * 
     * Steps:
     * 1. Validate the target entity exists via Feign client (outside DB transaction)
     * 2. DB Transaction begins:
     *    a. Persist the Review entity (append-only, immutable)
     *    b. Update the ReviewAggregate (OCC-protected, recalculates average)
     *    c. Write the OutboxEventEntity (for Kafka relay)
     * 3. Publish local event (for async Redis cache warming)
     */
    @org.springframework.retry.annotation.Retryable(retryFor = org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ReviewResponseDto createReview(CreateReviewRequest request, String userId) {
        log.info("Creating review for entityType={}, entityId={}, userId={}",
                request.getEntityType(), request.getEntityId(), userId);

        // Step 1: Validate entity existence (OUTSIDE transaction)
        validateEntityExists(request.getEntityType(), request.getEntityId());

        // Generate ReviewId outside transaction so retries reuse the same ID
        UUID reviewId = UUID.randomUUID();

        // Step 2: Database updates (INSIDE transaction)
        return transactionTemplate.execute(status -> {
            Review review = Review.builder()
                    .entityType(request.getEntityType())
                    .entityId(request.getEntityId())
                    .id(reviewId)
                    .userId(userId)
                .rating(request.getRating())
                .comment(request.getComment())
                    .metadata(request.getMetadata())
                    .createdAt(Instant.now())
                    .build();
        reviewRepository.save(review);

        // Step 3: Update the ReviewAggregate (OCC with @Version)
        EntityKey entityKey = new EntityKey(request.getEntityType(), request.getEntityId());
        ReviewAggregate aggregate = aggregateRepository.findById(entityKey)
                .orElseGet(() -> new ReviewAggregate(entityKey));
        aggregate.addReview(request.getRating());
        
        try {
            aggregateRepository.saveAndFlush(aggregate);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("First-insert race detected for entity {}, retrying...", entityKey);
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(ReviewAggregate.class, entityKey);
        }

        // Step 4: Write Outbox Event (same transaction — guaranteed consistency)
        writeOutboxEvent(review, aggregate);

        // Step 5: Publish local event for async Redis cache warming (fires AFTER_COMMIT)
        ReviewAggregateDto aggregateDto = ReviewAggregateDto.builder()
                .entityType(aggregate.getId().getEntityType())
                .entityId(aggregate.getId().getEntityId())
                .totalReviews(aggregate.getTotalReviews())
                .averageRating(aggregate.getAverageRating())
                .build();
        eventPublisher.publishEvent(new AggregateUpdatedLocalEvent(this, aggregateDto));

        log.info("Review created successfully: id={}, entityType={}, entityId={}",
                reviewId, request.getEntityType(), request.getEntityId());

            return ReviewResponseDto.builder()
                    .id(reviewId)
                    .entityType(review.getEntityType())
                    .entityId(review.getEntityId())
                    .userId(review.getUserId())
                    .rating(review.getRating())
                    .comment(review.getComment())
                    .metadata(review.getMetadata())
                    .createdAt(review.getCreatedAt())
                    .build();
        });
    }

    /**
     * Validates that the target entity exists before accepting the review.
     * Fails fast if the upstream service is unreachable.
     */
    private void validateEntityExists(EntityType entityType, String entityId) {
        switch (entityType) {
            case RESTAURANT:
                validateRestaurantExists(entityId);
                break;
            case DRIVER:
                validateDriverExists(entityId);
                break;
            case PRODUCT:
                validateProductExists(entityId);
                break;
            default:
                throw new IllegalArgumentException("Unsupported entity type: " + entityType);
        }
    }

    private void validateDriverExists(String entityId) {
        try {
            UUID id = UUID.fromString(entityId);
            ResponseEntity<ApiResponse<IdentityUserDTO>> response = identityServiceClient.getUserById(id, "reviews-service");
            if (response.getBody() == null || response.getBody().getData() == null) {
                throw new com.fooddelivery.reviews.exception.ExternalServiceUnavailableException("Received null response from identity service for driver: " + entityId);
            }
            IdentityUserDTO user = response.getBody().getData();
            if (user.getRoles() == null || user.getRoles().stream().noneMatch(r -> r.equalsIgnoreCase("DRIVER"))) {
                throw new IllegalArgumentException("User is not a driver: " + entityId);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid driver entityId format. Must be a valid UUID.", e);
        }
    }

    private void validateProductExists(String entityId) {
        try {
            UUID.fromString(entityId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid product entityId format. Must be a valid UUID.", e);
        }

        ResponseEntity<ApiResponse<Boolean>> response = restaurantServiceClient.productExists(entityId, "reviews-service");

        if (response.getBody() == null || response.getBody().getData() == null) {
            throw new com.fooddelivery.reviews.exception.ExternalServiceUnavailableException("Received null response from restaurant service for product: " + entityId);
        }

        if (!Boolean.TRUE.equals(response.getBody().getData())) {
            throw new IllegalArgumentException("Product with ID " + entityId + " does not exist.");
        }
    }

    private void validateRestaurantExists(String entityId) {
        try {
            UUID.fromString(entityId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid restaurant entityId format. Must be a valid UUID.", e);
        }

        ResponseEntity<ApiResponse<Boolean>> response =
                restaurantServiceClient.outletExists(entityId, "reviews-service");

        if (response.getBody() == null || response.getBody().getData() == null) {
            throw new com.fooddelivery.reviews.exception.ExternalServiceUnavailableException("Received null response from restaurant service for entityId: " + entityId);
        }

        if (!Boolean.TRUE.equals(response.getBody().getData())) {
            throw new IllegalArgumentException("Restaurant with ID " + entityId + " does not exist.");
        }
    }

    private void writeOutboxEvent(Review review, ReviewAggregate aggregate) {
        try {
            Map<String, Object> payload = Map.of(
                    "reviewId", review.getId().getId().toString(),
                    "entityType", review.getEntityType().name(),
                    "entityId", review.getEntityId(),
                    "userId", review.getUserId(),
                    "rating", review.getRating(),
                    "totalReviews", aggregate.getTotalReviews(),
                    "averageRating", aggregate.getAverageRating().toPlainString(),
                    "timestamp", review.getCreatedAt().toString()
            );

            String idempotencyKey = String.format("review:%s:%s:%s",
                    review.getEntityType(), review.getEntityId(), review.getUserId());

            OutboxEventEntity outboxEvent = OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType(AggregateType.REVIEW)
                    .aggregateId(review.getEntityId())
                    .eventType(EventType.REVIEW_CREATED)
                    .idempotencyKey(idempotencyKey)
                    .payload(objectMapper.writeValueAsString(payload))
                    .createdAt(LocalDateTime.now())
                    .status(OutboxStatus.UNPROCESSED)
                    .retryCount(0)
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            throw new com.fooddelivery.reviews.exception.ReviewEventSerializationException("Failed to serialize review outbox event payload", e);
        }
    }
}
