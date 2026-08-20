package com.fooddelivery.reviews.dto;

import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.reviews.validator.ValidJson;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReviewRequest {

    @NotNull(message = "Entity type is required")
    private EntityType entityType;

    @NotBlank(message = "Entity ID is required")
    private String entityId;

    @NotNull(message = "Rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating must be at most 5")
    private Integer rating;

    @Size(max = 4000, message = "Comment must not exceed 4000 characters")
    private String comment;

    /**
     * Optional JSONB metadata (e.g., order ID, photos, tags).
     * Stored as a raw JSON string.
     */
    @Size(max = 8000, message = "Metadata must not exceed 8000 characters")
    @ValidJson(message = "Metadata must be a valid JSON string")
    private String metadata;
}
