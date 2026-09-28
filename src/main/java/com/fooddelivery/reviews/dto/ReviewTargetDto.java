package com.fooddelivery.reviews.dto;

import java.time.Instant;

import com.fooddelivery.common.enums.ReviewEntityType;
import com.fooddelivery.reviews.enums.ReviewVisibility;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One thing on an order that can be rated, and what the participant has already said about it.
 *
 * <p>Carrying the existing review rather than merely a boolean is what lets the rating sheet render
 * "You said: ★★★★☆ — Quick and hot" for something already reviewed. A review cannot be edited, so
 * the honest presentation is the statement itself, not a disabled button with no explanation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewTargetDto {

    @NotNull
    private ReviewEntityType entityType;

    @NotNull
    private String entityId;

    /**
     * What to call this target in the sheet — the outlet's name, the dish's name, or
     * "Delivery partner" for the driver, whose name the order does not record.
     */
    @NotNull
    private String displayName;

    private ReviewVisibility visibility;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean alreadyReviewed;

    private Integer existingRating;

    private String existingComment;

    private Instant existingReviewedAt;
}
