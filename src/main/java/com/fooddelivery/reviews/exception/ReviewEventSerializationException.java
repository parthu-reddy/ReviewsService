package com.fooddelivery.reviews.exception;

public class ReviewEventSerializationException extends RuntimeException {
    public ReviewEventSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
