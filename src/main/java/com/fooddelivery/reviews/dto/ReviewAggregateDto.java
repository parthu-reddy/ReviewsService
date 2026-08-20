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
    private EntityType entityType;
    private String entityId;
    private long totalReviews;
    private BigDecimal averageRating;
}
