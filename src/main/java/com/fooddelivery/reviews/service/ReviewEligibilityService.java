package com.fooddelivery.reviews.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.fooddelivery.common.client.CustomerServiceClient;
import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.common.dto.order.OrderReviewAuthorizationRequest;
import com.fooddelivery.common.dto.order.OrderReviewAuthorizationResult;
import com.fooddelivery.common.dto.order.OrderReviewContextDto;
import com.fooddelivery.common.dto.order.OrderReviewTargetAuthorizationRequest;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.enums.RoleName;
import com.fooddelivery.reviews.config.ReviewProperties;
import com.fooddelivery.reviews.enums.ReviewRejectionReason;
import com.fooddelivery.reviews.exception.ExternalServiceUnavailableException;
import com.fooddelivery.reviews.exception.ReviewNotAllowedException;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Coordinates review-specific rules with the order service's authoritative relationship check. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewEligibilityService {

    static final String CALLING_SERVICE = "reviews-service";

    private final CustomerServiceClient customerServiceClient;
    private final ReviewProperties reviewProperties;
    private final Clock clock;

    /** Fetches the order snapshot over the signed service-to-service OpenFeign client. */
    public OrderReviewContextDto getOrderContext(UUID orderId) {
        ResponseEntity<ApiResponse<OrderReviewContextDto>> response;
        try {
            response = customerServiceClient.getOrderReviewContext(orderId.toString(), CALLING_SERVICE);
        } catch (FeignException.NotFound e) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.ORDER_NOT_FOUND,
                    "Order " + orderId + " was not found.");
        } catch (FeignException e) {
            throw new ExternalServiceUnavailableException(
                    "The order service could not provide review context for order " + orderId, e);
        }

        ApiResponse<OrderReviewContextDto> body = response.getBody();
        if (body == null || body.getData() == null) {
            throw new ExternalServiceUnavailableException(
                    "No review context returned for order " + orderId);
        }
        return body.getData();
    }

    /**
     * Asks CustomerApplication to verify the actor and each exact target in a single batch. The
     * request is internal and authenticated as SERVICE by {@code FeignSecurityInterceptor}; the
     * actor identity comes from ReviewsService's already-verified principal.
     */
    public List<OrderReviewAuthorizationResult> authorizeTargets(
            UUID orderId,
            UUID reviewerId,
            RoleName reviewerRole,
            List<OrderReviewTargetAuthorizationRequest> targets) {
        ResponseEntity<ApiResponse<List<OrderReviewAuthorizationResult>>> response;
        try {
            response = customerServiceClient.authorizeOrderReviewTargets(
                    orderId.toString(),
                    OrderReviewAuthorizationRequest.builder()
                            .reviewerId(reviewerId)
                            .reviewerRole(reviewerRole)
                            .targets(targets)
                            .build(),
                    CALLING_SERVICE);
        } catch (FeignException.NotFound e) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.ORDER_NOT_FOUND,
                    "Order " + orderId + " was not found.");
        } catch (FeignException e) {
            throw new ExternalServiceUnavailableException(
                    "The order service could not authorize review targets for order " + orderId, e);
        }

        ApiResponse<List<OrderReviewAuthorizationResult>> body = response.getBody();
        if (body == null || !body.isSuccess() || body.getData() == null
                || body.getData().size() != targets.size()) {
            throw new ExternalServiceUnavailableException(
                    "The order service returned an incomplete review authorization response for order "
                            + orderId);
        }

        Set<String> requestedKeys = new HashSet<>();
        for (OrderReviewTargetAuthorizationRequest target : targets) {
            if (target == null || target.getTargetType() == null || target.getTargetId() == null
                    || !requestedKeys.add(targetKey(target.getTargetType().name(), target.getTargetId()))) {
                throw new ExternalServiceUnavailableException(
                        "The review service generated an invalid authorization request for order " + orderId);
            }
        }

        Map<String, OrderReviewAuthorizationResult> decisions = new HashMap<>();
        for (OrderReviewAuthorizationResult decision : body.getData()) {
            if (decision == null || decision.getTargetType() == null || decision.getTargetId() == null) {
                throw new ExternalServiceUnavailableException(
                        "The order service returned an invalid review authorization response for order "
                                + orderId);
            }
            String key = targetKey(decision.getTargetType().name(), decision.getTargetId());
            if (!requestedKeys.contains(key) || decisions.putIfAbsent(key, decision) != null
                    || (!decision.isAllowed() && (decision.getReasonCode() == null
                    || decision.getReasonCode().isBlank()))) {
                throw new ExternalServiceUnavailableException(
                        "The order service returned mismatched review authorization results for order "
                                + orderId);
            }
        }
        if (!decisions.keySet().equals(requestedKeys)) {
            throw new ExternalServiceUnavailableException(
                    "The order service returned mismatched review authorization results for order " + orderId);
        }
        return targets.stream()
                .map(target -> decisions.get(targetKey(target.getTargetType().name(), target.getTargetId())))
                .toList();
    }

    private static String targetKey(String targetType, String targetId) {
        return targetType + ":" + targetId;
    }

    /**
     * Applies the review service's delivery-window policy after the order service has confirmed
     * that the actor is a participant. This ordering prevents order state from being disclosed to
     * unrelated accounts.
     */
    public void assertReviewWindowOpen(OrderReviewContextDto context) {
        if (context.getDeliveryStatus() != DeliveryStatus.DELIVERED || context.getDeliveredAt() == null) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.ORDER_NOT_DELIVERED,
                    "This order has not been delivered, so there is nothing to review yet.");
        }

        if (clock.instant().isAfter(windowClosesAt(context))) {
            throw new ReviewNotAllowedException(ReviewRejectionReason.REVIEW_WINDOW_CLOSED,
                    "The " + reviewProperties.getWindowDays()
                            + "-day review window for order " + context.getOrderId() + " has closed.");
        }
    }

    /** Fixed-length window after the delivery instant; no local time zone participates. */
    public Instant windowClosesAt(OrderReviewContextDto context) {
        Instant deliveredAt = context.getDeliveredAt();
        return deliveredAt == null
                ? Instant.EPOCH
                : deliveredAt.plus(Duration.ofDays(reviewProperties.getWindowDays()));
    }

    /** "Priya Raman" becomes "Priya R."; absent names are kept absent. */
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
}
