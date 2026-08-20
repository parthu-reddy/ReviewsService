package com.fooddelivery.reviews.dto;

import com.fooddelivery.reviews.enums.EntityType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewResponseDto {
    private UUID id;
    private EntityType entityType;
    private String entityId;
    private String userId;
    private int rating;
    private String comment;
    private String metadata;
    private Instant createdAt;
}
