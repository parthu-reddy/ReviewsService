package com.fooddelivery.reviews.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Entity
@Table(name = "review_aggregates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewAggregate {

    /** Scale used when projecting the exact average for presentation. */
    public static final int AVERAGE_SCALE = 2;

    @EmbeddedId
    private EntityKey id;

    @Column(name = "total_reviews", nullable = false)
    private long totalReviews;

    /**
     * Exact sum of every rating ever accepted for this entity. This is the source of truth:
     * {@link #averageRating} is a rounded projection of it and is never read back as input,
     * because rounding error in a scale-2 average compounds across writes.
     */
    @Column(name = "rating_sum", nullable = false)
    private long ratingSum;

    @Column(name = "average_rating", nullable = false, precision = 3, scale = 2)
    private BigDecimal averageRating;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ReviewAggregate(EntityKey id) {
        this.id = id;
        this.totalReviews = 0L;
        this.ratingSum = 0L;
        this.averageRating = zeroAverage();
        this.updatedAt = Instant.now();
    }

    /**
     * Folds a new rating into the aggregate. Implements the 'Fail Fast Policy' — an out-of-range
     * rating aborts the write rather than being clamped or silently dropped.
     */
    public void addReview(int newRating) {
        if (newRating < 1 || newRating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5. Received: " + newRating);
        }

        this.totalReviews += 1;
        this.ratingSum += newRating;
        this.averageRating = computeAverage(this.ratingSum, this.totalReviews);
        this.updatedAt = Instant.now();
    }

    public static BigDecimal zeroAverage() {
        return BigDecimal.ZERO.setScale(AVERAGE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal computeAverage(long ratingSum, long totalReviews) {
        if (totalReviews == 0) {
            return zeroAverage();
        }
        return BigDecimal.valueOf(ratingSum)
                .divide(BigDecimal.valueOf(totalReviews), AVERAGE_SCALE, RoundingMode.HALF_UP);
    }
}
