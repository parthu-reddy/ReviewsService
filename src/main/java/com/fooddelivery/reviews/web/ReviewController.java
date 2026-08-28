package com.fooddelivery.reviews.web;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.reviews.dto.CreateReviewRequest;
import com.fooddelivery.reviews.dto.ReviewAggregateDto;
import com.fooddelivery.reviews.dto.ReviewResponseDto;
import com.fooddelivery.reviews.entity.Review;
import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.common.constants.HeaderConstants;

import com.fooddelivery.reviews.service.ReviewCommandService;
import com.fooddelivery.reviews.service.ReviewQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

@Slf4j
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
@Validated
public class ReviewController {

    private final ReviewCommandService reviewCommandService;
    private final ReviewQueryService reviewQueryService;

    private static final int MAX_PAGE_SIZE = 50;

    /**
     * Create a new review.
     * The unique constraint (entity_type, entity_id, user_id) prevents duplicate reviews.
     * Duplicate attempts will return 409 via the GlobalExceptionHandler's DataIntegrityViolationException handler.
     */
    /** Requires a principal, matching the default-deny chain. If review reads should be public, that is a product change, not an omission. */
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<ApiResponse<ReviewResponseDto>> createReview(
            @Valid @RequestBody CreateReviewRequest request,
            @RequestHeader(HeaderConstants.HEADER_USER_ID) String userId) {
        log.info("POST /api/v1/reviews - entityType={}, entityId={}, userId={}",
                request.getEntityType(), request.getEntityId(), userId);

        ReviewResponseDto response = reviewCommandService.createReview(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Review created successfully"));
    }

    /**
     * Get the aggregate rating summary for an entity (from Redis cache-aside).
     */
    /** Requires a principal, matching the default-deny chain. If review reads should be public, that is a product change, not an omission. */
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    @GetMapping("/aggregate")
    public ResponseEntity<ApiResponse<ReviewAggregateDto>> getAggregate(
            @RequestParam EntityType entityType,
            @RequestParam String entityId) {
        log.info("GET /api/v1/reviews/aggregate - entityType={}, entityId={}", entityType, entityId);

        ReviewAggregateDto aggregate = reviewQueryService.getAggregate(entityType, entityId);
        return ResponseEntity.ok(ApiResponse.success(aggregate, "Aggregate retrieved successfully"));
    }

    /**
     * Get paginated reviews for an entity.
     * Page size is capped at 50 to prevent abuse.
     */
    /** Requires a principal, matching the default-deny chain. If review reads should be public, that is a product change, not an omission. */
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<ApiResponse<org.springframework.data.web.PagedModel<ReviewResponseDto>>> getReviews(
            @RequestParam EntityType entityType,
            @RequestParam String entityId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        log.info("GET /api/v1/reviews - entityType={}, entityId={}, page={}, size={}",
                entityType, entityId, page, size);

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<ReviewResponseDto> reviewsPage = reviewQueryService.getReviews(entityType, entityId, pageable);

        return ResponseEntity.ok(ApiResponse.success(new org.springframework.data.web.PagedModel<>(reviewsPage), "Reviews retrieved successfully"));
    }
}
