package com.fooddelivery.reviews.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.fooddelivery.common.client.CustomerServiceClient;
import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.common.dto.order.OrderReviewContextDto;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.reviews.config.ReviewProperties;
import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.reviews.enums.ReviewRejectionReason;
import com.fooddelivery.reviews.exception.ReviewNotAllowedException;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Decides whether a customer may review an order, and what on it.
 *
 * <p>Every gate here is server-side and none of them is re-derived elsewhere. The UI mirrors them
 * only so it does not offer an action that will fail; it is not trusted to enforce them.
 *
 * <p>The previous design asked "does this restaurant exist", "does this product exist", "is this
 * user a driver" of three separate services. That was the wrong question — existence is not
 * eligibility, and a customer who never ordered could review anything — and it could not succeed
 * anyway, because those endpoints require SERVICE, RESTAURANT or ADMIN while the identity
 * propagated on a review request is the customer's. One call to the order's owner answers the
 * question that actually matters.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewEligibilityService {

    static final String CALLING_SERVICE = "reviews-service";

    private final CustomerServiceClient customerServiceClient;
    private final ReviewProperties reviewProperties;

    /**
     * Zoned to {@code platform.business-zone} — see {@link com.fooddelivery.reviews.config.ClockConfig}.
     * Injected so the expiry branch is reachable in a test without waiting out the window.
     */
    private final Clock clock;

    /**
     * Fetches the order's review context and applies gates E2–E5: the order exists, the caller
     * placed it, it was delivered, and the window is still open.
     *
     * @throws ReviewNotAllowedException when any gate fails
     */
    public OrderReviewContextDto resolve(UUID orderId, String callerUserId) {
        OrderReviewContextDto context = fetchContext(orderId);

        if (context.getCustomerId() == null
                || !context.getCustomerId().toString().equals(callerUserId)) {
            // Deliberately does not say whose order it is.
            throw new ReviewNotAllowedException(ReviewRejectionReason.NOT_YOUR_ORDER,
                    "Order " + orderId + " does not belong to you.");
        }

        if (context.getDeliveryStatus() != DeliveryStatus.DELIVERED) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.ORDER_NOT_DELIVERED,
                    "Order " + orderId + " has not been delivered, so there is nothing to review yet.");
        }

        if (context.getDeliveredAt() == null) {
            // DELIVERED without a delivery timestamp means the window cannot be evaluated. Refusing
            // is the safe answer: the alternative is an order that stays reviewable forever.
            log.warn("Order {} is DELIVERED but carries no deliveredAt; refusing to open a review window",
                    orderId);
            throw new ReviewNotAllowedException(ReviewRejectionReason.ORDER_NOT_DELIVERED,
                    "Order " + orderId + " has no recorded delivery time.");
        }

        if (clock.instant().isAfter(windowClosesAt(context))) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.REVIEW_WINDOW_CLOSED,
                    "The " + reviewProperties.getWindowDays()
                            + "-day review window for order " + orderId + " has closed.");
        }

        return context;
    }

    /** When the review window shuts. Exposed so the eligibility response can show it. */
    public Instant windowClosesAt(OrderReviewContextDto context) {
        LocalDateTime deliveredAt = context.getDeliveredAt();
        if (deliveredAt == null) {
            return Instant.EPOCH;
        }
        // deliveredAt is a LocalDateTime: a wall-clock reading with no offset. Resolve it in the
        // platform's business zone rather than assuming UTC, which would move the deadline by the
        // zone's offset -- five and a half hours for the configured Asia/Kolkata.
        return deliveredAt.atZone(clock.getZone()).toInstant()
                .plus(Duration.ofDays(reviewProperties.getWindowDays()));
    }

    /**
     * Gate E6 — the target was actually part of this order.
     *
     * <p>{@code restaurantId} is an OUTLET id and {@code menuItemId} is a MASTER menu item id; both
     * comparisons are against what the order itself recorded, so a renamed or delisted item is
     * still reviewable by the person who ate it.
     */
    public void assertTargetOnOrder(OrderReviewContextDto context, EntityType entityType, String entityId) {
        boolean onOrder = switch (entityType) {
            case RESTAURANT -> context.getRestaurantId() != null
                    && context.getRestaurantId().toString().equals(entityId);
            case DRIVER -> context.getDeliveryExecutiveId() != null
                    && context.getDeliveryExecutiveId().toString().equals(entityId);
            case PRODUCT -> context.getItems() != null
                    && context.getItems().stream()
                            .anyMatch(item -> item.getMenuItemId() != null
                                    && item.getMenuItemId().toString().equals(entityId));
        };

        if (!onOrder) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.TARGET_NOT_ON_ORDER,
                    entityType + " " + entityId + " was not part of order " + context.getOrderId() + ".");
        }
    }

    /**
     * How the author is shown publicly: "Priya Raman" becomes "Priya R.".
     *
     * <p>A surname initial rather than a full name — a review is a public statement attached to a
     * person who ate at a specific place, and the full legal name is more than the reader needs.
     * Returns null when the order carries no name, and the reader is then shown "A customer".
     */
    public static String toDisplayName(String customerName) {
        if (customerName == null || customerName.isBlank()) {
            return null;
        }
        String[] parts = customerName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        String surname = parts[parts.length - 1];
        return parts[0] + " " + Character.toUpperCase(surname.charAt(0)) + ".";
    }

    private OrderReviewContextDto fetchContext(UUID orderId) {
        ResponseEntity<ApiResponse<OrderReviewContextDto>> response;
        try {
            response = customerServiceClient.getOrderReviewContext(orderId.toString(), CALLING_SERVICE);
        } catch (FeignException.NotFound e) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.ORDER_NOT_FOUND,
                    "Order " + orderId + " was not found.");
        } catch (FeignException.Forbidden e) {
            // The order service refused us. From the customer's side that is indistinguishable from
            // "not yours", and saying so leaks nothing.
            throw new ReviewNotAllowedException(ReviewRejectionReason.NOT_YOUR_ORDER,
                    "Order " + orderId + " does not belong to you.");
        }

        ApiResponse<OrderReviewContextDto> body = response.getBody();
        if (body == null || body.getData() == null) {
            // Fail closed. A null body here is the order service being unhealthy, not the order
            // being ineligible, and accepting the review would record an unprovable claim.
            throw new com.fooddelivery.reviews.exception.ExternalServiceUnavailableException(
                    "No review context returned for order " + orderId);
        }
        return body.getData();
    }
}
