package com.fooddelivery.reviews.dto;

import com.fooddelivery.reviews.enums.EntityType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewAggregateDto implements Serializable {
    @jakarta.validation.constraints.NotNull
    private EntityType entityType;
    @jakarta.validation.constraints.NotNull
    private String entityId;
    @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    private long totalReviews;
    @jakarta.validation.constraints.NotNull
    private BigDecimal averageRating;
}
