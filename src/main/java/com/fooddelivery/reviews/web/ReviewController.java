package com.fooddelivery.reviews.web;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.common.client.RestaurantServiceClient;
import com.fooddelivery.common.enums.RoleName;
import com.fooddelivery.reviews.dto.AggregateBatchDto;
import com.fooddelivery.reviews.dto.CreateReviewRequest;
import com.fooddelivery.reviews.dto.ReviewAggregateDto;
import com.fooddelivery.reviews.dto.ReviewDetailDto;
import com.fooddelivery.reviews.dto.ReviewDto;
import com.fooddelivery.reviews.dto.ReviewEligibilityDto;
import com.fooddelivery.reviews.dto.ReviewReceivedDto;
import com.fooddelivery.common.enums.ReviewEntityType;
import com.fooddelivery.reviews.security.ReviewActorResolver;
import com.fooddelivery.reviews.service.ReviewCommandService;
import com.fooddelivery.reviews.service.ReviewQueryService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The review surface.
 *
 * <p>The caller's identity comes from the verified {@link Authentication}, never from reading {@code X-User-Id}
 * directly. Two reasons, both load-bearing: {@code SecurityContextFilter} only populates the
 * security context once it has <em>verified</em> the gateway's identity signature, so the principal
 * is authenticated while the raw header is merely present; and a declared {@code @RequestHeader}
 * lands in the OpenAPI spec as a required parameter, which made the generated browser client demand
 * a header the gateway strips from inbound requests anyway.
 *
 * <p>There is deliberately no PUT, PATCH or DELETE here, and there is none anywhere else in this
 * module. A review is a statement someone made about an order they received; letting it be rewritten
 * afterwards would make the aggregate a running total of opinions that no longer exist. Resubmitting
 * is answered with 409 {@code ALREADY_REVIEWED} and the existing review, so the UI shows what was
 * said rather than an error.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
@Validated
public class ReviewController {

    private final ReviewCommandService reviewCommandService;
    private final ReviewQueryService reviewQueryService;
    private final ReviewActorResolver reviewActorResolver;
    private final RestaurantServiceClient restaurantServiceClient;

    /**
     * Submits all selected feedback for one order. {@code actorRole} selects the active portal, then
     * is checked against the gateway-verified authorities before it is used.
     */
    @PreAuthorize("hasAnyRole('CUSTOMER', 'RESTAURANT', 'DELIVERY')")
    @PostMapping
    public ResponseEntity<ApiResponse<List<ReviewDetailDto>>> createReviews(
            @Valid @RequestBody CreateReviewRequest request,
            @RequestParam("actorRole") RoleName actorRole,
            Authentication authentication) {
        RoleName verifiedRole = reviewActorResolver.requireReviewRole(authentication, actorRole);
        String userId = authentication.getName();
        log.info("POST /api/v1/reviews - orderId={}, entries={}, userId={}",
                request.getOrderId(), request.getEntries().size(), userId);

        List<ReviewDetailDto> created = reviewCommandService.createReviews(request, userId, verifiedRole);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Thanks — your review has been recorded."));
    }

    /**
     * What the selected order participant may still say, and what they already said.
     *
     * <p>Drives the whole rating sheet in a single call — including the already-reviewed entries,
     * which is what lets an immutable review read as "submitted" rather than as a dead button.
     */
    @PreAuthorize("hasAnyRole('CUSTOMER', 'RESTAURANT', 'DELIVERY')")
    @GetMapping("/orders/{orderId}/eligibility")
    public ResponseEntity<ApiResponse<ReviewEligibilityDto>> getEligibility(
            @PathVariable UUID orderId,
            @RequestParam("actorRole") RoleName actorRole,
            Authentication authentication) {
        RoleName verifiedRole = reviewActorResolver.requireReviewRole(authentication, actorRole);
        ReviewEligibilityDto eligibility =
                reviewQueryService.getEligibility(orderId, authentication.getName(), verifiedRole);
        return ResponseEntity.ok(ApiResponse.success(eligibility, "Eligibility resolved"));
    }

    /** The caller's own reviews. Unredacted — they wrote them. */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<PagedModel<ReviewDetailDto>>> getMyReviews(
            Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ReviewDetailDto> reviews =
                reviewQueryService.getMyReviews(authentication.getName(), pageable);
        return ResponseEntity.ok(
                ApiResponse.success(new PagedModel<>(reviews), "Your reviews retrieved"));
    }

    /** Average and count for one entity. Who may ask is decided by {@code ReviewAccessPolicy}. */
    @PreAuthorize("@reviewAccessPolicy.canReadAggregate(authentication, #entityType, #entityId)")
    @GetMapping("/aggregate")
    public ResponseEntity<ApiResponse<ReviewAggregateDto>> getAggregate(
            @RequestParam ReviewEntityType entityType,
            @RequestParam String entityId) {
        ReviewAggregateDto aggregate = reviewQueryService.getAggregate(entityType, entityId);
        return ResponseEntity.ok(ApiResponse.success(aggregate, "Aggregate retrieved successfully"));
    }

    /**
     * Averages for a page of entities at once — a restaurant feed, a menu.
     *
     * <p>{@code DRIVER} is refused outright rather than filtered per id. A batch driver-rating
     * lookup has no legitimate caller, and permitting it would be the cheapest way to enumerate the
     * whole fleet's performance one page at a time.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/aggregates")
    public ResponseEntity<ApiResponse<AggregateBatchDto>> getAggregates(
            @RequestParam ReviewEntityType entityType,
            @RequestParam List<String> entityIds) {
        if (entityType == ReviewEntityType.DRIVER || entityType == ReviewEntityType.CUSTOMER) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    "Private participant ratings cannot be read in bulk.", "BATCH_NOT_ALLOWED_FOR_PRIVATE_TARGET"));
        }
        if (entityIds.size() > ReviewQueryService.MAX_BATCH_IDS) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    "At most " + ReviewQueryService.MAX_BATCH_IDS + " ids may be requested at once.",
                    "TOO_MANY_IDS"));
        }
        return ResponseEntity.ok(ApiResponse.success(
                reviewQueryService.getAggregates(entityType, entityIds), "Aggregates retrieved"));
    }

    /**
     * Reviews for one entity, newest first.
     *
     * <p>The sort is fixed rather than client-supplied: a {@code Sort} built from a request
     * parameter is a property-name injection surface, and the only index that serves this query is
     * {@code (entity_type, entity_id, created_at DESC, id)}.
     */
    @PreAuthorize("@reviewAccessPolicy.canListReviews(authentication, #entityType, #entityId)")
    @GetMapping
    public ResponseEntity<ApiResponse<PagedModel<ReviewDto>>> getReviews(
            @RequestParam ReviewEntityType entityType,
            @RequestParam String entityId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ReviewDto> reviews = reviewQueryService.getReviews(entityType, entityId, pageable);
        return ResponseEntity.ok(
                ApiResponse.success(new PagedModel<>(reviews), "Reviews retrieved successfully"));
    }

    /** Private feedback for the active participant. Reviewer id and order id are never returned. */
    @PreAuthorize("hasAnyRole('CUSTOMER', 'RESTAURANT', 'DELIVERY')")
    @GetMapping("/received")
    public ResponseEntity<ApiResponse<PagedModel<ReviewReceivedDto>>> getReceivedReviews(
            @RequestParam("actorRole") RoleName actorRole,
            @RequestParam(value = "outletId", required = false) String outletId,
            Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        RoleName verifiedRole = reviewActorResolver.requireReviewRole(authentication, actorRole);
        String entityId;
        ReviewEntityType entityType;

        switch (verifiedRole) {
            case CUSTOMER -> {
                entityType = ReviewEntityType.CUSTOMER;
                entityId = authentication.getName();
                if (outletId != null) {
                    throw new AccessDeniedException("Customers can only read feedback addressed to themselves.");
                }
            }
            case DELIVERY -> {
                entityType = ReviewEntityType.DRIVER;
                entityId = authentication.getName();
                if (outletId != null) {
                    throw new AccessDeniedException("Delivery partners can only read feedback addressed to themselves.");
                }
            }
            case RESTAURANT -> {
                if (outletId == null || outletId.isBlank()) {
                    throw new IllegalArgumentException("An outletId is required for restaurant feedback.");
                }
                boolean ownsOutlet = restaurantServiceClient
                        .getOwnerOutlets(authentication.getName(), "reviews-service")
                        .stream()
                        .anyMatch(outletId::equalsIgnoreCase);
                if (!ownsOutlet) {
                    throw new AccessDeniedException("This outlet does not belong to the authenticated restaurant owner.");
                }
                entityType = ReviewEntityType.RESTAURANT;
                entityId = outletId;
            }
            case ADMIN -> throw new AccessDeniedException("Admin accounts use the moderation review surface.");
            default -> throw new AccessDeniedException("This role cannot read received reviews.");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ReviewReceivedDto> reviews = reviewQueryService.getReceivedReviews(entityType, entityId, pageable);
        return ResponseEntity.ok(ApiResponse.success(new PagedModel<>(reviews), "Private feedback retrieved"));
    }
}
