package com.fooddelivery.reviews.dto;

import java.time.Instant;
import java.util.UUID;

import com.fooddelivery.reviews.enums.EntityType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A review with its provenance intact.
 *
 * <p>Returned to exactly two audiences: the author reading their own reviews, and an administrator
 * moderating. Both already know who wrote it — for the author it is themselves, and for an
 * administrator investigating abuse the link to the order is the whole point.
 *
 * <p>It is a separate type from {@link ReviewDto} so that redaction is decided by which mapper ran
 * and is visible in the OpenAPI schema, rather than being a nullable field somebody forgets to null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewDetailDto {

    @NotNull
    private UUID id;

    @NotNull
    private EntityType entityType;

    @NotNull
    private String entityId;

    @NotNull
    private UUID orderId;

    @NotNull
    private String userId;

    private String authorDisplayName;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private int rating;

    private String comment;

    @NotNull
    private Instant createdAt;
}
