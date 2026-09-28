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
import com.fooddelivery.common.dto.order.OrderReviewAuthorizationRequest;
import com.fooddelivery.common.dto.order.OrderReviewAuthorizationResult;
import com.fooddelivery.common.dto.order.OrderReviewContextDto;
import com.fooddelivery.common.dto.order.OrderReviewItemDto;
import com.fooddelivery.common.dto.order.OrderReviewTargetAuthorizationRequest;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.enums.RoleName;
import com.fooddelivery.reviews.config.ReviewProperties;
import com.fooddelivery.common.enums.ReviewEntityType;
import com.fooddelivery.reviews.enums.ReviewRejectionReason;
import com.fooddelivery.reviews.exception.ExternalServiceUnavailableException;
import com.fooddelivery.reviews.exception.ReviewNotAllowedException;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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

    private ReviewEligibilityService serviceAt(long daysAfterDelivery) {
        Instant now = DELIVERED_AT.plus(java.time.Duration.ofDays(daysAfterDelivery));
        return new ReviewEligibilityService(customerServiceClient, properties, Clock.fixed(now, java.time.ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ getOrderContext

    @Test
    void anOrderTheServiceDoesNotKnowAboutIsNotFound() {
        when(customerServiceClient.getOrderReviewContext(eq(ORDER_ID.toString()), anyString()))
                .thenThrow(feignException(404));

        assertThatThrownBy(() -> serviceAt(1).getOrderContext(ORDER_ID))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.ORDER_NOT_FOUND);
    }

    @Test
    void anUnhealthyOrderServiceFailsClosedRatherThanAllowingTheReview() {
        when(customerServiceClient.getOrderReviewContext(anyString(), anyString()))
                .thenReturn(ResponseEntity.ok(ApiResponse.success(null, "empty")));

        assertThatThrownBy(() -> serviceAt(1).getOrderContext(ORDER_ID))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    // ------------------------------------------------------------------ authorizeTargets

    @Test
    void authorizeTargetsWorksSuccessfully() {
        when(customerServiceClient.authorizeOrderReviewTargets(eq(ORDER_ID.toString()), any(OrderReviewAuthorizationRequest.class), anyString()))
                .thenReturn(ResponseEntity.ok(ApiResponse.success(List.of(
                        OrderReviewAuthorizationResult.builder()
                                .targetType(ReviewEntityType.RESTAURANT)
                                .targetId(OUTLET_ID.toString())
                                .allowed(true)
                                .build()
                ), "ok")));

        List<OrderReviewAuthorizationResult> results = serviceAt(1).authorizeTargets(
                ORDER_ID, CUSTOMER_ID, RoleName.CUSTOMER,
                List.of(OrderReviewTargetAuthorizationRequest.builder()
                        .targetType(ReviewEntityType.RESTAURANT)
                        .targetId(OUTLET_ID.toString())
                        .build())
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isAllowed()).isTrue();
    }

    @Test
    void aForbiddenUpstreamInAuthorizeIsReportedAsExternalServiceUnavailableOrNotFound() {
        when(customerServiceClient.authorizeOrderReviewTargets(anyString(), any(), anyString()))
                .thenThrow(feignException(404));

        assertThatThrownBy(() -> serviceAt(1).authorizeTargets(
                ORDER_ID, CUSTOMER_ID, RoleName.CUSTOMER, List.of()))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.ORDER_NOT_FOUND);
    }

    // ------------------------------------------------------------------ assertReviewWindowOpen

    @Test
    void anOrderStillInTransitCannotBeReviewed() {
        OrderReviewContextDto ctx = context(DeliveryStatus.OUT_FOR_DELIVERY, null);

        assertThatThrownBy(() -> serviceAt(0).assertReviewWindowOpen(ctx))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.ORDER_NOT_DELIVERED);
    }

    @Test
    void deliveredWithNoTimestampIsRefused() {
        OrderReviewContextDto ctx = context(DeliveryStatus.DELIVERED, null);

        assertThatThrownBy(() -> serviceAt(1).assertReviewWindowOpen(ctx))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.ORDER_NOT_DELIVERED);
    }

    @Test
    void aDeliveredOrderInsideTheWindowResolves() {
        OrderReviewContextDto ctx = context(DeliveryStatus.DELIVERED, DELIVERED_AT);

        assertThatCode(() -> serviceAt(13).assertReviewWindowOpen(ctx))
                .doesNotThrowAnyException();
    }

    @Test
    void theWindowClosesAfterTheConfiguredNumberOfDays() {
        OrderReviewContextDto ctx = context(DeliveryStatus.DELIVERED, DELIVERED_AT);

        assertThatThrownBy(() -> serviceAt(15).assertReviewWindowOpen(ctx))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.REVIEW_WINDOW_CLOSED);
    }

    @Test
    void theLastMomentOfTheWindowIsStillOpen() {
        OrderReviewContextDto ctx = context(DeliveryStatus.DELIVERED, DELIVERED_AT);

        assertThatCode(() -> serviceAt(14).assertReviewWindowOpen(ctx))
                .doesNotThrowAnyException();
    }

    @Test
    void theWindowClosesFourteenDaysAfterTheDeliveryInstantInEveryZone() {
        OrderReviewContextDto ctx = context(DeliveryStatus.DELIVERED, java.time.Instant.parse("2026-09-25T00:00:00Z"));
        for (String zone : java.util.List.of("UTC", "Asia/Kolkata", "America/St_Johns", "Pacific/Chatham")) {
            ReviewEligibilityService service = new ReviewEligibilityService(customerServiceClient, properties,
                    Clock.fixed(Instant.EPOCH, ZoneId.of(zone)));
            assertThat(service.windowClosesAt(ctx)).as(zone).isEqualTo(java.time.Instant.parse("2026-10-09T00:00:00Z"));
        }
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

    @Test
    void anAbsentNameYieldsNull() {
        assertThat(ReviewEligibilityService.toDisplayName(null)).isNull();
        assertThat(ReviewEligibilityService.toDisplayName("   ")).isNull();
    }

    // ------------------------------------------------------------------ helpers

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
