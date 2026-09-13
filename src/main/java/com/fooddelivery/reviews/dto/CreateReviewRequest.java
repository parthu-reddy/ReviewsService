package com.fooddelivery.reviews.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Every review a customer is making about one order, submitted together.
 *
 * <p>Batched deliberately. A review cannot be edited once written, so a half-accepted submission
 * would be permanently wrong — the customer could never repair the entries that failed. One request
 * means one eligibility resolution and one transaction: all entries commit, or none do.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReviewRequest {

    @NotNull(message = "Order ID is required")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID orderId;

    /**
     * Bounded at 20: the largest possible target set is one outlet, one driver and every distinct
     * item on the order, and an order with eighteen distinct dishes is already an outlier.
     */
    @NotEmpty(message = "At least one review entry is required")
    @Size(max = 20, message = "At most 20 entries may be submitted at once")
    @Valid
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private List<ReviewEntryRequest> entries;
}
