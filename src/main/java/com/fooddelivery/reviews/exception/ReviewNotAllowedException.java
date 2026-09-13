package com.fooddelivery.reviews.exception;

import com.fooddelivery.reviews.enums.ReviewRejectionReason;

import lombok.Getter;

/**
 * A review was refused for a reason the customer can act on. The reason carries its own HTTP status
 * so the handler does not have to re-derive one, and its name reaches the client as an error code.
 */
@Getter
public class ReviewNotAllowedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ReviewRejectionReason reason;

    public ReviewNotAllowedException(ReviewRejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
