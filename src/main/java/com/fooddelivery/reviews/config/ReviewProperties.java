package com.fooddelivery.reviews.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Tunables for the review domain. */
@Getter
@Setter
@ConfigurationProperties(prefix = "reviews")
public class ReviewProperties {

    /**
     * How long after delivery a customer may still review the order.
     *
     * <p>A window exists because a rating written months later is about a memory, not a meal, and
     * because an unbounded window makes every order in history a permanent write target. Fourteen
     * days is long enough to cover someone who orders on a Friday and gets round to it a fortnight
     * later.
     */
    private int windowDays = 14;

    /** Requests per minute per principal for writes. */
    private int writeRateLimitPerMinute = 10;

    /** Requests per minute per principal for reads. */
    private int readRateLimitPerMinute = 100;
}
