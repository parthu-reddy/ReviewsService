package com.fooddelivery.reviews.mapper;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fooddelivery.reviews.dto.ReviewDetailDto;
import com.fooddelivery.reviews.dto.ReviewDto;
import com.fooddelivery.reviews.entity.Review;
import com.fooddelivery.reviews.enums.EntityType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redaction, exercised.
 *
 * <p>`ReviewDto` not declaring `userId` is a compile-time guarantee; that the mapper withholds the
 * author on driver reviews is not, and is the half that could regress silently.
 */
class ReviewMapperTest {

    private static final UUID REVIEW_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ORDER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant CREATED = Instant.parse("2026-09-11T10:15:30Z");

    private static Review review(EntityType type) {
        return Review.builder()
                .entityType(type)
                .entityId("entity-1")
                .id(REVIEW_ID)
                .orderId(ORDER_ID)
                .userId("customer-9")
                .authorDisplayName("Priya R.")
                .rating(4)
                .comment("Hot and on time")
                .createdAt(CREATED)
                .build();
    }

    @Test
    void aRestaurantReviewKeepsTheAuthorLabel() {
        ReviewDto dto = ReviewMapper.toPublic(review(EntityType.RESTAURANT));

        assertThat(dto.getAuthorDisplayName()).isEqualTo("Priya R.");
        assertThat(dto.getRating()).isEqualTo(4);
        assertThat(dto.getComment()).isEqualTo("Hot and on time");
        assertThat(dto.getId()).isEqualTo(REVIEW_ID);
        assertThat(dto.getCreatedAt()).isEqualTo(CREATED);
    }

    @Test
    void aProductReviewKeepsTheAuthorLabel() {
        assertThat(ReviewMapper.toPublic(review(EntityType.PRODUCT)).getAuthorDisplayName())
                .isEqualTo("Priya R.");
    }

    /**
     * The rule this class exists for: a driver reading their own feedback must not be able to work
     * out which customer left it.
     */
    @Test
    void aDriverReviewWithholdsTheAuthorEvenThoughOneIsStored() {
        Review stored = review(EntityType.DRIVER);
        assertThat(stored.getAuthorDisplayName()).isEqualTo("Priya R.");

        assertThat(ReviewMapper.toPublic(stored).getAuthorDisplayName()).isNull();
    }

    @Test
    void thePublicProjectionCarriesNoCommentWhenNoneWasLeft() {
        Review noComment = Review.builder()
                .entityType(EntityType.RESTAURANT).entityId("e").id(REVIEW_ID).orderId(ORDER_ID)
                .userId("u").authorDisplayName("Priya R.").rating(5).comment(null)
                .createdAt(CREATED).build();

        assertThat(ReviewMapper.toPublic(noComment).getComment()).isNull();
    }

    @Test
    void theDetailProjectionCarriesProvenanceForEveryEntityType() {
        for (EntityType type : EntityType.values()) {
            ReviewDetailDto detail = ReviewMapper.toDetail(review(type));

            assertThat(detail.getUserId()).describedAs("%s userId", type).isEqualTo("customer-9");
            assertThat(detail.getOrderId()).describedAs("%s orderId", type).isEqualTo(ORDER_ID);
            // Unredacted on purpose: the only readers are the author and an administrator, and both
            // already know who wrote it.
            assertThat(detail.getAuthorDisplayName()).describedAs("%s author", type)
                    .isEqualTo("Priya R.");
        }
    }
}
