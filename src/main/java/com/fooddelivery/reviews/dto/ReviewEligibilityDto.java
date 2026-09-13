package com.fooddelivery.reviews.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fooddelivery.reviews.enums.ReviewRejectionReason;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything the rating sheet needs for one order, in one call.
 *
 * <p>When {@link #reviewable} is false, {@link #reason} says why so the UI can explain it — "this
 * order was cancelled", "the 14-day window has closed" — instead of hiding the action and leaving
 * the customer to wonder where it went.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewEligibilityDto {

    @NotNull
    private UUID orderId;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean reviewable;

    /** Null when {@link #reviewable} is true. */
    private ReviewRejectionReason reason;

    /** Human-readable form of {@link #reason}, safe to show as-is. */
    private String reasonDetail;

    /** Null when the order is not reviewable at all. */
    private Instant windowClosesAt;

    /**
     * Every rateable target on the order, including those already reviewed. Empty only when the
     * order is not reviewable.
     */
    @NotNull
    private List<ReviewTargetDto> targets;
}
