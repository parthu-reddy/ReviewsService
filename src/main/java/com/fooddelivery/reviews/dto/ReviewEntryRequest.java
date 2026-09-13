package com.fooddelivery.reviews.dto;

import com.fooddelivery.reviews.enums.EntityType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One target being rated within a {@link CreateReviewRequest}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewEntryRequest {

    @NotNull(message = "Entity type is required")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private EntityType entityType;

    /**
     * Must be a target that was actually on the order: the outlet, the assigned driver, or one of
     * the ordered items. Checked against the order's own record, not against a catalogue.
     */
    @NotBlank(message = "Entity ID is required")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String entityId;

    @NotNull(message = "Rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating must be at most 5")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer rating;

    /**
     * Optional free text. Capped at 1000 rather than the column's 4000: this is written in a
     * textarea with a live counter, and a 4000-character review is not a review anyone reads.
     */
    @Size(max = 1000, message = "Comment must not exceed 1000 characters")
    private String comment;
}
