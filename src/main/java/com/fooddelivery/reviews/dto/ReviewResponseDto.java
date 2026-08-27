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
    @jakarta.validation.constraints.NotNull
    private UUID id;
    @jakarta.validation.constraints.NotNull
    private EntityType entityType;
    @jakarta.validation.constraints.NotNull
    private String entityId;
    @jakarta.validation.constraints.NotNull
    private String userId;
    @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    private int rating;
    private String comment;
    private String metadata;
    @jakarta.validation.constraints.NotNull
    private Instant createdAt;
}
