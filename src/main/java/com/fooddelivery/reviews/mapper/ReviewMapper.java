package com.fooddelivery.reviews.mapper;

import com.fooddelivery.reviews.dto.ReviewDetailDto;
import com.fooddelivery.reviews.dto.ReviewDto;
import com.fooddelivery.reviews.entity.Review;
import com.fooddelivery.reviews.enums.EntityType;

/**
 * Turns a stored review into one of the two projections the API exposes.
 *
 * <p>Redaction lives here and only here. It is a property of which method ran — and therefore of
 * the declared return type and the generated OpenAPI schema — rather than a nullable field on a
 * shared DTO that a later edit forgets to clear.
 */
public final class ReviewMapper {

    private ReviewMapper() {
    }

    /**
     * The projection anyone entitled to read this entity receives.
     *
     * <p>The author is withheld for {@code DRIVER} reviews. A driver who can attach a one-star
     * rating to a name knows that customer's address — they delivered to it. The reader is shown
     * "A customer" instead, which is the whole of what they need.
     */
    public static ReviewDto toPublic(Review review) {
        return ReviewDto.builder()
                .id(review.getReviewId())
                .entityType(review.getEntityType())
                .entityId(review.getEntityId())
                .rating(review.getRating())
                .comment(review.getComment())
                .authorDisplayName(review.getEntityType() == EntityType.DRIVER
                        ? null
                        : review.getAuthorDisplayName())
                .createdAt(review.getCreatedAt())
                .build();
    }

    /** The unredacted projection: the author reading their own, or an administrator moderating. */
    public static ReviewDetailDto toDetail(Review review) {
        return ReviewDetailDto.builder()
                .id(review.getReviewId())
                .entityType(review.getEntityType())
                .entityId(review.getEntityId())
                .orderId(review.getOrderId())
                .userId(review.getUserId())
                .authorDisplayName(review.getAuthorDisplayName())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(review.getCreatedAt())
                .build();
    }
}
