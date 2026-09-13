package com.fooddelivery.reviews.web;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.reviews.dto.ReviewDetailDto;
import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.reviews.service.ReviewQueryService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Moderation surface. Unredacted, because investigating abuse means following a review back to the
 * account and the order that produced it — which is precisely what the public projection withholds.
 *
 * <p>Mounted under {@code /api/v1/internal/admin/} deliberately: {@code GlobalJwtAuthFilter} blocks
 * external access to {@code /api/v1/internal/**} except for that prefix, and {@code ADMIN}
 * short-circuits the gateway's rbac table, so no rbac rule is needed or wanted.
 *
 * <p>There is no moderation *write* here — no hide, no delete. A review is immutable, and an
 * administrator being able to remove an inconvenient one quietly is a different product with a
 * different audit story. When that is wanted it arrives as an explicit, logged action, not as a
 * DELETE bolted onto this class.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/admin/reviews")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Validated
public class AdminReviewController {

    private final ReviewQueryService reviewQueryService;

    /** Every review of one entity, with its author and order intact. */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedModel<ReviewDetailDto>>> getReviewsForEntity(
            @RequestParam EntityType entityType,
            @RequestParam String entityId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        log.info("ADMIN review lookup: entityType={} entityId={}", entityType, entityId);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ReviewDetailDto> reviews =
                reviewQueryService.getReviewsForAdmin(entityType, entityId, pageable);
        return ResponseEntity.ok(ApiResponse.success(new PagedModel<>(reviews), "Reviews retrieved"));
    }

    /** Everything one account has written. The view that makes a review-bombing pattern visible. */
    @GetMapping("/by-user/{userId}")
    public ResponseEntity<ApiResponse<PagedModel<ReviewDetailDto>>> getReviewsByUser(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        log.info("ADMIN review lookup by author: userId={}", userId);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ReviewDetailDto> reviews = reviewQueryService.getMyReviews(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(new PagedModel<>(reviews), "Reviews retrieved"));
    }
}
