package com.fooddelivery.reviews.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fooddelivery.common.dto.order.OrderReviewContextDto;
import com.fooddelivery.common.dto.order.OrderReviewItemDto;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.reviews.dto.AggregateBatchDto;
import com.fooddelivery.reviews.dto.ReviewEligibilityDto;
import com.fooddelivery.reviews.dto.ReviewTargetDto;
import com.fooddelivery.reviews.entity.EntityKey;
import com.fooddelivery.reviews.entity.Review;
import com.fooddelivery.reviews.entity.ReviewAggregate;
import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.reviews.enums.ReviewRejectionReason;
import com.fooddelivery.reviews.exception.ReviewNotAllowedException;
import com.fooddelivery.reviews.repository.AggregateRepository;
import com.fooddelivery.reviews.repository.ReviewRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What the rating sheet is built from, and what a listing reads.
 *
 * <p>`getEligibility` decides everything the customer sees when they open "Rate your order": which
 * targets appear, which are already done, and what was said about them. It had no test because the
 * class is a `@MockBean` in the OpenAPI context test — which names it without ever running it.
 */
@ExtendWith(MockitoExtension.class)
class ReviewQueryServiceTest {

    private static final UUID ORDER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID CUSTOMER_ID = UUID.fromString("12121212-1212-1212-1212-121212121212");
    private static final UUID OUTLET_ID = UUID.fromString("13131313-1313-1313-1313-131313131313");
    private static final UUID DRIVER_ID = UUID.fromString("14141414-1414-1414-1414-141414141414");
    private static final UUID DISH_A = UUID.fromString("15151515-1515-1515-1515-151515151515");
    private static final UUID DISH_B = UUID.fromString("16161616-1616-1616-1616-161616161616");

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private AggregateRepository aggregateRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private ReviewEligibilityService eligibilityService;

    private ReviewQueryService service() {
        return new ReviewQueryService(redisTemplate, stringRedisTemplate,
                aggregateRepository, reviewRepository, eligibilityService);
    }

    // ------------------------------------------------------------------ eligibility

    /**
     * A refusal comes back as data, not as an exception. The sheet has to render "the window closed
     * on the 24th" as readily as it renders stars; a thrown exception leaves the UI a status code.
     */
    @Test
    void aRefusalIsReturnedAsDataWithItsReason() {
        when(eligibilityService.resolve(any(), any())).thenThrow(
                new ReviewNotAllowedException(ReviewRejectionReason.REVIEW_WINDOW_CLOSED,
                        "The 14-day review window has closed."));

        ReviewEligibilityDto result = service().getEligibility(ORDER_ID, CUSTOMER_ID.toString());

        assertThat(result.isReviewable()).isFalse();
        assertThat(result.getReason()).isEqualTo(ReviewRejectionReason.REVIEW_WINDOW_CLOSED);
        assertThat(result.getReasonDetail()).contains("window has closed");
        assertThat(result.getTargets()).isEmpty();
        assertThat(result.getOrderId()).isEqualTo(ORDER_ID);
    }

    @Test
    void everyParticipantOnTheOrderBecomesATarget() {
        when(eligibilityService.resolve(any(), any())).thenReturn(context(DISH_A, DISH_B));
        when(reviewRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        ReviewEligibilityDto result = service().getEligibility(ORDER_ID, CUSTOMER_ID.toString());

        assertThat(result.isReviewable()).isTrue();
        assertThat(result.getTargets()).extracting(ReviewTargetDto::getEntityType)
                .containsExactly(EntityType.RESTAURANT, EntityType.DRIVER,
                        EntityType.PRODUCT, EntityType.PRODUCT);
        assertThat(result.getTargets()).extracting(ReviewTargetDto::getDisplayName)
                .containsExactly("Bombay Canteen", ReviewQueryService.DRIVER_DISPLAY_NAME,
                        "Butter Chicken", "Naan");
    }

    /** Ordering the same dish twice is still one reviewable dish. */
    @Test
    void aRepeatedDishCollapsesToASingleTarget() {
        when(eligibilityService.resolve(any(), any())).thenReturn(context(DISH_A, DISH_A));
        when(reviewRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        ReviewEligibilityDto result = service().getEligibility(ORDER_ID, CUSTOMER_ID.toString());

        assertThat(result.getTargets()).filteredOn(t -> t.getEntityType() == EntityType.PRODUCT)
                .hasSize(1);
    }

    /** No driver was ever assigned, so there is nobody to rate. */
    @Test
    void anOrderWithNoDriverOffersNoDriverTarget() {
        OrderReviewContextDto ctx = context(DISH_A);
        ctx.setDeliveryExecutiveId(null);
        when(eligibilityService.resolve(any(), any())).thenReturn(ctx);
        when(reviewRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        ReviewEligibilityDto result = service().getEligibility(ORDER_ID, CUSTOMER_ID.toString());

        assertThat(result.getTargets()).extracting(ReviewTargetDto::getEntityType)
                .doesNotContain(EntityType.DRIVER);
    }

    /**
     * The behaviour that makes immutability legible: an already-reviewed target is still returned,
     * carrying what was said, so the sheet shows "You said ★★★★☆" instead of a missing button.
     */
    @Test
    void anAlreadyReviewedTargetCarriesTheReviewThatWasLeft() {
        Instant reviewedAt = Instant.parse("2026-09-05T12:00:00Z");
        when(eligibilityService.resolve(any(), any())).thenReturn(context(DISH_A));
        when(reviewRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(
                Review.builder()
                        .entityType(EntityType.RESTAURANT).entityId(OUTLET_ID.toString())
                        .id(UUID.randomUUID()).orderId(ORDER_ID).userId(CUSTOMER_ID.toString())
                        .rating(4).comment("Quick and hot").createdAt(reviewedAt).build()));

        ReviewEligibilityDto result = service().getEligibility(ORDER_ID, CUSTOMER_ID.toString());

        ReviewTargetDto restaurant = result.getTargets().stream()
                .filter(t -> t.getEntityType() == EntityType.RESTAURANT).findFirst().orElseThrow();
        assertThat(restaurant.isAlreadyReviewed()).isTrue();
        assertThat(restaurant.getExistingRating()).isEqualTo(4);
        assertThat(restaurant.getExistingComment()).isEqualTo("Quick and hot");
        assertThat(restaurant.getExistingReviewedAt()).isEqualTo(reviewedAt);

        // The driver was not reviewed, so that target stays open.
        ReviewTargetDto driver = result.getTargets().stream()
                .filter(t -> t.getEntityType() == EntityType.DRIVER).findFirst().orElseThrow();
        assertThat(driver.isAlreadyReviewed()).isFalse();
        assertThat(driver.getExistingRating()).isNull();
    }

    @Test
    void theWindowClosingTimeIsReturnedSoTheSheetCanShowADeadline() {
        Instant closesAt = Instant.parse("2026-09-24T19:04:11Z");
        when(eligibilityService.resolve(any(), any())).thenReturn(context(DISH_A));
        when(eligibilityService.windowClosesAt(any())).thenReturn(closesAt);
        when(reviewRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        assertThat(service().getEligibility(ORDER_ID, CUSTOMER_ID.toString()).getWindowClosesAt())
                .isEqualTo(closesAt);
    }

    /** An item with no name still needs a label the sheet can render. */
    @Test
    void anUnnamedDishFallsBackToAReadableLabel() {
        OrderReviewContextDto ctx = context();
        ctx.setItems(List.of(OrderReviewItemDto.builder().menuItemId(DISH_A).name(null).build()));
        when(eligibilityService.resolve(any(), any())).thenReturn(ctx);
        when(reviewRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        assertThat(service().getEligibility(ORDER_ID, CUSTOMER_ID.toString()).getTargets())
                .filteredOn(t -> t.getEntityType() == EntityType.PRODUCT)
                .extracting(ReviewTargetDto::getDisplayName)
                .containsExactly("Item");
    }

    // ------------------------------------------------------------------ batch aggregates

    /**
     * Every requested id comes back, including those nobody has reviewed. A missing key would be
     * indistinguishable from a dropped response, and the menu would silently show no rating rather
     * than "no reviews yet".
     */
    @Test
    void idsWithNoReviewsComeBackAsAnExplicitZero() {
        when(aggregateRepository.findAllById(any())).thenReturn(List.of(withReviews(DISH_A, 3, "4.33")));

        AggregateBatchDto batch = service().getAggregates(EntityType.PRODUCT,
                List.of(DISH_A.toString(), DISH_B.toString()));

        assertThat(batch.getAggregates()).hasSize(2);
        assertThat(batch.getAggregates()).extracting(a -> a.getEntityId())
                .containsExactly(DISH_A.toString(), DISH_B.toString());

        var missing = batch.getAggregates().get(1);
        assertThat(missing.getTotalReviews()).isZero();
        assertThat(missing.getAverageRating()).isEqualByComparingTo("0.00");
    }

    @Test
    void aRepeatedOrBlankIdIsRequestedOnlyOnce() {
        when(aggregateRepository.findAllById(any())).thenReturn(List.of());

        AggregateBatchDto batch = service().getAggregates(EntityType.PRODUCT,
                java.util.Arrays.asList(DISH_A.toString(), DISH_A.toString(), "  ", null));

        assertThat(batch.getAggregates()).hasSize(1);
        assertThat(batch.getAggregates().get(0).getEntityId()).isEqualTo(DISH_A.toString());
    }

    @Test
    void aKnownAggregateKeepsItsStoredAverage() {
        when(aggregateRepository.findAllById(any())).thenReturn(List.of(withReviews(DISH_A, 7, "4.29")));

        AggregateBatchDto batch = service().getAggregates(EntityType.PRODUCT, List.of(DISH_A.toString()));

        assertThat(batch.getEntityType()).isEqualTo(EntityType.PRODUCT);
        assertThat(batch.getAggregates().get(0).getTotalReviews()).isEqualTo(7);
        assertThat(batch.getAggregates().get(0).getAverageRating()).isEqualByComparingTo("4.29");
    }

    // ------------------------------------------------------------------ helpers

    private ReviewAggregate withReviews(UUID entityId, int count, String ignoredAverage) {
        ReviewAggregate aggregate = new ReviewAggregate(new EntityKey(EntityType.PRODUCT, entityId.toString()));
        // Built through the real fold so the average is whatever the arithmetic actually produces,
        // rather than a value the test asserts against itself.
        int[] ratings = switch (count) {
            case 3 -> new int[] {5, 4, 4};
            case 7 -> new int[] {5, 3, 4, 4, 5, 5, 4};
            default -> new int[] {5};
        };
        for (int r : ratings) {
            aggregate.addReview(r);
        }
        return aggregate;
    }

    private OrderReviewContextDto context(UUID... dishes) {
        List<OrderReviewItemDto> items = java.util.Arrays.stream(dishes)
                .map(d -> OrderReviewItemDto.builder()
                        .menuItemId(d)
                        .name(d.equals(DISH_A) ? "Butter Chicken" : "Naan")
                        .build())
                .toList();
        return OrderReviewContextDto.builder()
                .orderId(ORDER_ID)
                .customerId(CUSTOMER_ID)
                .customerName("Priya Raman")
                .restaurantId(OUTLET_ID)
                .restaurantName("Bombay Canteen")
                .deliveryExecutiveId(DRIVER_ID)
                .deliveryStatus(DeliveryStatus.DELIVERED)
                .deliveredAt(java.time.Instant.parse("2026-09-01T19:30:00Z"))
                .items(items)
                .build();
    }
}
