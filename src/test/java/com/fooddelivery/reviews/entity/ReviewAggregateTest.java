package com.fooddelivery.reviews.entity;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.fooddelivery.reviews.enums.EntityType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The arithmetic behind every star the app renders.
 *
 * <p>The class documents a specific decision — {@code ratingSum} is the source of truth and
 * {@code averageRating} is a rounded projection of it, because recomputing the running total from a
 * scale-2 average reintroduces error on every write. These tests hold that decision to account.
 */
class ReviewAggregateTest {

    private static ReviewAggregate fresh() {
        return new ReviewAggregate(new EntityKey(EntityType.RESTAURANT, "outlet-1"));
    }

    @Test
    void aNewAggregateStartsAtZeroWithATwoPlaceScale() {
        ReviewAggregate aggregate = fresh();

        assertThat(aggregate.getTotalReviews()).isZero();
        assertThat(aggregate.getRatingSum()).isZero();
        // 0.00, not 0 -- the scale is part of the contract the UI formats against.
        assertThat(aggregate.getAverageRating()).isEqualByComparingTo("0.00");
        assertThat(aggregate.getAverageRating().scale()).isEqualTo(2);
    }

    @Test
    void oneReviewMakesTheAverageThatReview() {
        ReviewAggregate aggregate = fresh();

        aggregate.addReview(4);

        assertThat(aggregate.getTotalReviews()).isEqualTo(1);
        assertThat(aggregate.getRatingSum()).isEqualTo(4);
        assertThat(aggregate.getAverageRating()).isEqualByComparingTo("4.00");
    }

    @Test
    void theAverageTracksTheRunningSum() {
        ReviewAggregate aggregate = fresh();

        aggregate.addReview(5);
        aggregate.addReview(4);
        aggregate.addReview(3);

        assertThat(aggregate.getTotalReviews()).isEqualTo(3);
        assertThat(aggregate.getRatingSum()).isEqualTo(12);
        assertThat(aggregate.getAverageRating()).isEqualByComparingTo("4.00");
    }

    @Test
    void arecurringDecimalIsRoundedHalfUpToTwoPlaces() {
        ReviewAggregate aggregate = fresh();

        aggregate.addReview(5);
        aggregate.addReview(4);
        aggregate.addReview(4);

        // 13/3 = 4.333...
        assertThat(aggregate.getAverageRating()).isEqualByComparingTo("4.33");
    }

    @Test
    void aHalfwayValueRoundsUp() {
        ReviewAggregate aggregate = fresh();

        aggregate.addReview(5);
        aggregate.addReview(2);
        aggregate.addReview(2);
        aggregate.addReview(2);
        aggregate.addReview(2);
        aggregate.addReview(2);
        aggregate.addReview(2);
        aggregate.addReview(2);

        // 19/8 = 2.375 -> HALF_UP at scale 2 -> 2.38
        assertThat(aggregate.getAverageRating()).isEqualByComparingTo("2.38");
    }

    /**
     * The reason {@code ratingSum} exists, demonstrated on the shortest sequence that exposes it.
     *
     * <p>5, 3, 4, 4, 5, 5, 4 sums to 30 over 7 reviews — 4.2857…, which is 4.29 at scale 2. An
     * implementation that folded each new rating into the previous <em>rounded</em> average instead
     * would arrive at 4.28: every intermediate rounding is carried forward and multiplied by the
     * next count.
     *
     * <p>This replaces a weaker test that added 400 threes and asserted 3.00. That one passed under
     * both implementations — every intermediate average was exactly 3.00, so there was never any
     * rounding to accumulate. It described the property without testing it, which was confirmed by
     * substituting the broken arithmetic and watching it stay green.
     */
    @Test
    void theAverageIsComputedFromTheExactSumNotFromThePreviousRoundedAverage() {
        ReviewAggregate aggregate = fresh();

        for (int rating : new int[] {5, 3, 4, 4, 5, 5, 4}) {
            aggregate.addReview(rating);
        }

        assertThat(aggregate.getRatingSum()).isEqualTo(30);
        assertThat(aggregate.getTotalReviews()).isEqualTo(7);
        assertThat(aggregate.getAverageRating()).isEqualByComparingTo("4.29");
    }

    /** A long run of identical ratings stays exact — a sanity check, not the rounding guard above. */
    @Test
    void aLongRunOfIdenticalRatingsStaysExact() {
        ReviewAggregate aggregate = fresh();

        for (int i = 0; i < 400; i++) {
            aggregate.addReview(3);
        }

        assertThat(aggregate.getTotalReviews()).isEqualTo(400);
        assertThat(aggregate.getRatingSum()).isEqualTo(1200);
        assertThat(aggregate.getAverageRating()).isEqualByComparingTo("3.00");
    }

    @Test
    void alternatingExtremesAverageToTheMiddle() {
        ReviewAggregate aggregate = fresh();

        for (int i = 0; i < 50; i++) {
            aggregate.addReview(1);
            aggregate.addReview(5);
        }

        assertThat(aggregate.getAverageRating()).isEqualByComparingTo("3.00");
    }

    // ------------------------------------------------------------------ fail-fast policy

    @Test
    void aRatingBelowOneAbortsTheWriteRatherThanBeingClamped() {
        ReviewAggregate aggregate = fresh();

        assertThatThrownBy(() -> aggregate.addReview(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 5");
    }

    @Test
    void aRatingAboveFiveAbortsTheWrite() {
        assertThatThrownBy(() -> fresh().addReview(6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRejectedRatingLeavesTheAggregateUntouched() {
        ReviewAggregate aggregate = fresh();
        aggregate.addReview(5);

        assertThatThrownBy(() -> aggregate.addReview(99)).isInstanceOf(IllegalArgumentException.class);

        // Silently dropping or clamping would leave a total that no set of real ratings produces.
        assertThat(aggregate.getTotalReviews()).isEqualTo(1);
        assertThat(aggregate.getRatingSum()).isEqualTo(5);
        assertThat(aggregate.getAverageRating()).isEqualByComparingTo("5.00");
    }

    @Test
    void bothBoundsAreAccepted() {
        ReviewAggregate aggregate = fresh();

        aggregate.addReview(1);
        aggregate.addReview(5);

        assertThat(aggregate.getRatingSum()).isEqualTo(6);
        assertThat(aggregate.getAverageRating()).isEqualByComparingTo("3.00");
    }

    @Test
    void everyWriteAdvancesTheUpdatedTimestamp() throws InterruptedException {
        ReviewAggregate aggregate = fresh();
        var before = aggregate.getUpdatedAt();
        Thread.sleep(2);

        aggregate.addReview(5);

        assertThat(aggregate.getUpdatedAt()).isAfter(before);
    }

    @Test
    void zeroAverageIsTheScaledConstantTheColumnExpects() {
        assertThat(ReviewAggregate.zeroAverage()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ReviewAggregate.zeroAverage().scale()).isEqualTo(ReviewAggregate.AVERAGE_SCALE);
    }
}
