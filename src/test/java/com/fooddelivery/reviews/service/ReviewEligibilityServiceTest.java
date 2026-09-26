package com.fooddelivery.reviews.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fooddelivery.common.client.CustomerServiceClient;
import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.common.dto.order.OrderReviewContextDto;
import com.fooddelivery.common.dto.order.OrderReviewItemDto;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.reviews.config.ReviewProperties;
import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.reviews.enums.ReviewRejectionReason;
import com.fooddelivery.reviews.exception.ExternalServiceUnavailableException;
import com.fooddelivery.reviews.exception.ReviewNotAllowedException;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The gates that decide who may review what.
 *
 * <p>These are the rules the whole feature rests on: everything else — the UI, the gateway, the
 * access policy — either mirrors them or assumes they held. Until this class existed they had never
 * been executed, only read.
 */
@ExtendWith(MockitoExtension.class)
class ReviewEligibilityServiceTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OUTLET_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID DRIVER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID ITEM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    private static final Instant DELIVERED_AT = java.time.Instant.parse("2026-09-01T19:30:00Z");

    @Mock
    private CustomerServiceClient customerServiceClient;

    private ReviewProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ReviewProperties();
        properties.setWindowDays(14);
    }

    /** A clock fixed to `daysAfterDelivery` past the delivery instant. */
    private ReviewEligibilityService serviceAt(long daysAfterDelivery) {
        Instant now = DELIVERED_AT.plus(java.time.Duration.ofDays(daysAfterDelivery));
        return new ReviewEligibilityService(customerServiceClient, properties, Clock.fixed(now, java.time.ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ E2: the order exists

    @Test
    void anOrderTheServiceDoesNotKnowAboutIsNotFound() {
        when(customerServiceClient.getOrderReviewContext(eq(ORDER_ID.toString()), anyString()))
                .thenThrow(feignException(404));

        assertThatThrownBy(() -> serviceAt(1).resolve(ORDER_ID, CUSTOMER_ID.toString()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.ORDER_NOT_FOUND);
    }

    /**
     * A null body is the order service being unhealthy, not the order being ineligible. Accepting
     * the review would record a claim nothing verified, so this fails closed — and as a 503 rather
     * than a refusal the customer could act on.
     */
    @Test
    void anUnhealthyOrderServiceFailsClosedRatherThanAllowingTheReview() {
        when(customerServiceClient.getOrderReviewContext(anyString(), anyString()))
                .thenReturn(ResponseEntity.ok(ApiResponse.success(null, "empty")));

        assertThatThrownBy(() -> serviceAt(1).resolve(ORDER_ID, CUSTOMER_ID.toString()))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    // ------------------------------------------------------------------ E3: it is the caller's

    @Test
    void anotherCustomersOrderIsRefused() {
        stub(context(DeliveryStatus.DELIVERED, DELIVERED_AT));

        assertThatThrownBy(() -> serviceAt(1).resolve(ORDER_ID, UUID.randomUUID().toString()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.NOT_YOUR_ORDER);
    }

    /** A 403 from the order service is indistinguishable from "not yours", and saying so leaks nothing. */
    @Test
    void aForbiddenUpstreamIsReportedAsNotYourOrder() {
        when(customerServiceClient.getOrderReviewContext(anyString(), anyString()))
                .thenThrow(feignException(403));

        assertThatThrownBy(() -> serviceAt(1).resolve(ORDER_ID, CUSTOMER_ID.toString()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.NOT_YOUR_ORDER);
    }

    // ------------------------------------------------------------------ E4: it was delivered

    /**
     * The gate reads DeliveryStatus, never OrderStatus. HANDED_OVER — OrderStatus's last
     * non-terminal value — means the rider collected the food, not that the customer received it.
     * An order out for delivery must not be reviewable.
     */
    @Test
    void anOrderStillInTransitCannotBeReviewed() {
        stub(context(DeliveryStatus.OUT_FOR_DELIVERY, null));

        assertThatThrownBy(() -> serviceAt(0).resolve(ORDER_ID, CUSTOMER_ID.toString()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.ORDER_NOT_DELIVERED);
    }

    @Test
    void aFailedDeliveryCannotBeReviewed() {
        stub(context(DeliveryStatus.FAILED, null));

        assertThatThrownBy(() -> serviceAt(0).resolve(ORDER_ID, CUSTOMER_ID.toString()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.ORDER_NOT_DELIVERED);
    }

    /** DELIVERED with no timestamp leaves the window unevaluable; refusing beats reviewable forever. */
    @Test
    void deliveredWithNoTimestampIsRefused() {
        stub(context(DeliveryStatus.DELIVERED, null));

        assertThatThrownBy(() -> serviceAt(1).resolve(ORDER_ID, CUSTOMER_ID.toString()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.ORDER_NOT_DELIVERED);
    }

    // ------------------------------------------------------------------ E5: the window

    @Test
    void aDeliveredOrderInsideTheWindowResolves() {
        stub(context(DeliveryStatus.DELIVERED, DELIVERED_AT));

        OrderReviewContextDto resolved = serviceAt(13).resolve(ORDER_ID, CUSTOMER_ID.toString());

        assertThat(resolved.getOrderId()).isEqualTo(ORDER_ID);
    }

    @Test
    void theWindowClosesAfterTheConfiguredNumberOfDays() {
        stub(context(DeliveryStatus.DELIVERED, DELIVERED_AT));

        assertThatThrownBy(() -> serviceAt(15).resolve(ORDER_ID, CUSTOMER_ID.toString()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.REVIEW_WINDOW_CLOSED);
    }

    /** The boundary itself is open: "within 14 days" includes the fourteenth day. */
    @Test
    void theLastMomentOfTheWindowIsStillOpen() {
        stub(context(DeliveryStatus.DELIVERED, DELIVERED_AT));

        assertThatCode(() -> serviceAt(14).resolve(ORDER_ID, CUSTOMER_ID.toString()))
                .doesNotThrowAnyException();
    }

    @Test
    void shorteningTheWindowClosesItSooner() {
        properties.setWindowDays(7);
        stub(context(DeliveryStatus.DELIVERED, DELIVERED_AT));

        assertThatThrownBy(() -> serviceAt(8).resolve(ORDER_ID, CUSTOMER_ID.toString()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.REVIEW_WINDOW_CLOSED);
    }

    /**
     * Defect D2 (TimezoneCorrectness_2026-09-25): the deadline used to move with the clock's zone,
     * closing 5h30 early under the deployed Asia/Kolkata clock. It is now exactly 14 days after the
     * delivery instant, whatever zone the clock (or the JVM: the build runs in Pacific/Chatham) is in.
     */
    @Test
    void theWindowClosesFourteenDaysAfterTheDeliveryInstantInEveryZone() {
        OrderReviewContextDto ctx = context(DeliveryStatus.DELIVERED, java.time.Instant.parse("2026-09-25T00:00:00Z"));
        for (String zone : java.util.List.of("UTC", "Asia/Kolkata", "America/St_Johns", "Pacific/Chatham")) {
            ReviewEligibilityService service = new ReviewEligibilityService(customerServiceClient, properties,
                    Clock.fixed(Instant.EPOCH, ZoneId.of(zone)));
            assertThat(service.windowClosesAt(ctx)).as(zone).isEqualTo(java.time.Instant.parse("2026-10-09T00:00:00Z"));
        }
    }

    // ------------------------------------------------------------------ E6: target on the order

    @Test
    void theOrdersOwnOutletDriverAndItemAreAllReviewable() {
        OrderReviewContextDto ctx = context(DeliveryStatus.DELIVERED, DELIVERED_AT);
        ReviewEligibilityService service = serviceAt(1);

        assertThatCode(() -> {
            service.assertTargetOnOrder(ctx, EntityType.RESTAURANT, OUTLET_ID.toString());
            service.assertTargetOnOrder(ctx, EntityType.DRIVER, DRIVER_ID.toString());
            service.assertTargetOnOrder(ctx, EntityType.PRODUCT, ITEM_ID.toString());
        }).doesNotThrowAnyException();
    }

    @Test
    void adifferentOutletIsNotReviewableOnThisOrder() {
        OrderReviewContextDto ctx = context(DeliveryStatus.DELIVERED, DELIVERED_AT);

        assertThatThrownBy(() -> serviceAt(1)
                .assertTargetOnOrder(ctx, EntityType.RESTAURANT, UUID.randomUUID().toString()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.TARGET_NOT_ON_ORDER);
    }

    @Test
    void adishFromSomeoneElsesOrderIsNotReviewable() {
        OrderReviewContextDto ctx = context(DeliveryStatus.DELIVERED, DELIVERED_AT);

        assertThatThrownBy(() -> serviceAt(1)
                .assertTargetOnOrder(ctx, EntityType.PRODUCT, UUID.randomUUID().toString()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.TARGET_NOT_ON_ORDER);
    }

    /** An order nobody was assigned to has no reviewable driver, so DRIVER must be refused. */
    @Test
    void anOrderWithNoAssignedDriverHasNoDriverToReview() {
        OrderReviewContextDto ctx = context(DeliveryStatus.DELIVERED, DELIVERED_AT);
        ctx.setDeliveryExecutiveId(null);

        assertThatThrownBy(() -> serviceAt(1)
                .assertTargetOnOrder(ctx, EntityType.DRIVER, DRIVER_ID.toString()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.TARGET_NOT_ON_ORDER);
    }

    // ------------------------------------------------------------------ author display name

    @Test
    void aFullNameIsShownAsFirstNameAndSurnameInitial() {
        assertThat(ReviewEligibilityService.toDisplayName("Priya Raman")).isEqualTo("Priya R.");
    }

    @Test
    void onlyTheFinalNameIsAbbreviated() {
        assertThat(ReviewEligibilityService.toDisplayName("Ana Maria de Souza")).isEqualTo("Ana S.");
    }

    @Test
    void aSingleNameIsLeftWhole() {
        assertThat(ReviewEligibilityService.toDisplayName("Madonna")).isEqualTo("Madonna");
    }

    @Test
    void surroundingAndRepeatedWhitespaceIsIgnored() {
        assertThat(ReviewEligibilityService.toDisplayName("  Priya   Raman  ")).isEqualTo("Priya R.");
    }

    @Test
    void aLowercaseSurnameStillYieldsACapitalInitial() {
        assertThat(ReviewEligibilityService.toDisplayName("priya raman")).isEqualTo("priya R.");
    }

    /** Null means the reader is shown "A customer" rather than an empty byline. */
    @Test
    void anAbsentNameYieldsNull() {
        assertThat(ReviewEligibilityService.toDisplayName(null)).isNull();
        assertThat(ReviewEligibilityService.toDisplayName("   ")).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private void stub(OrderReviewContextDto ctx) {
        when(customerServiceClient.getOrderReviewContext(anyString(), anyString()))
                .thenReturn(ResponseEntity.ok(ApiResponse.success(ctx, "ok")));
    }

    private static OrderReviewContextDto context(DeliveryStatus status, Instant deliveredAt) {
        return OrderReviewContextDto.builder()
                .orderId(ORDER_ID)
                .customerId(CUSTOMER_ID)
                .customerName("Priya Raman")
                .restaurantId(OUTLET_ID)
                .restaurantName("Bombay Canteen")
                .deliveryExecutiveId(DRIVER_ID)
                .deliveryStatus(status)
                .deliveredAt(deliveredAt)
                .items(List.of(OrderReviewItemDto.builder()
                        .menuItemId(ITEM_ID).name("Butter Chicken").build()))
                .build();
    }

    private static FeignException feignException(int status) {
        return FeignException.errorStatus("getOrderReviewContext",
                feign.Response.builder()
                        .status(status)
                        .reason(HttpStatus.valueOf(status).getReasonPhrase())
                        .request(Request.create(Request.HttpMethod.GET, "/", java.util.Map.of(),
                                null, new RequestTemplate()))
                        .build());
    }
}
