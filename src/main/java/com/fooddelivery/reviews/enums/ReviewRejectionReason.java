package com.fooddelivery.reviews.enums;

import org.springframework.http.HttpStatus;

/**
 * Why a review was refused. Carried to the client as {@code ApiResponse.errorCode} so the UI can
 * explain the refusal — "this order was cancelled", "the window has closed" — rather than showing a
 * generic failure or, worse, hiding the action and leaving the customer wondering.
 */
public enum ReviewRejectionReason {

    /** No such order, or the order service does not know about it. */
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** The caller is not the customer who placed it. */
    NOT_YOUR_ORDER(HttpStatus.FORBIDDEN),

    /**
     * The order has not reached {@code DeliveryStatus.DELIVERED}.
     *
     * <p>Read from delivery status, not order status: {@code OrderStatus} has no DELIVERED value by
     * design, and its last non-terminal value, HANDED_OVER, means the rider has the food — not that
     * the customer does.
     */
    ORDER_NOT_DELIVERED(HttpStatus.CONFLICT),

    /** Delivered, but longer ago than {@code reviews.window-days}. */
    REVIEW_WINDOW_CLOSED(HttpStatus.CONFLICT),

    /** The outlet, driver or item being rated was not part of this order. */
    TARGET_NOT_ON_ORDER(HttpStatus.BAD_REQUEST),

    /** A review already exists for this target on this order. Reviews are never rewritten. */
    ALREADY_REVIEWED(HttpStatus.CONFLICT),

    /** The same target appears twice in one submission. */
    DUPLICATE_ENTRY(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    ReviewRejectionReason(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
