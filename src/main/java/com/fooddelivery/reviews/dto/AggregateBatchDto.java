package com.fooddelivery.reviews.dto;

import java.util.List;

import com.fooddelivery.reviews.enums.EntityType;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Averages for a page of entities at once.
 *
 * <p>Exists because listings are lists: a twenty-restaurant feed asking one at a time is twenty
 * round trips before anything renders. Every requested id appears in {@link #aggregates}, including
 * those with no reviews yet — a missing key would be indistinguishable from a dropped response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregateBatchDto {

    @NotNull
    private EntityType entityType;

    @NotNull
    private List<ReviewAggregateDto> aggregates;
}
