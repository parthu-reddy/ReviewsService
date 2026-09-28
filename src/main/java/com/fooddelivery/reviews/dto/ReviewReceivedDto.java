package com.fooddelivery.reviews.dto;

import java.time.Instant;
import java.util.UUID;

import com.fooddelivery.common.enums.ReviewEntityType;
import com.fooddelivery.common.enums.RoleName;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Private feedback shown only to the participant it is about; never identifies the author or order. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewReceivedDto {

    @NotNull
    private UUID id;

    @NotNull
    private ReviewEntityType entityType;

    @NotNull
    private String entityId;

    @NotNull
    private RoleName authorRole;

    @NotNull
    private int rating;

    private String comment;

    @NotNull
    private Instant createdAt;
}
