package com.fooddelivery.reviews.dto;

import java.time.Instant;
import java.util.UUID;

import com.fooddelivery.common.enums.ReviewEntityType;
import com.fooddelivery.common.enums.RoleName;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The public projection of a review.
 *
 * <p>Carries neither {@code userId} nor {@code orderId} — deliberately, and as a property of the
 * type rather than of a runtime branch that a later edit could drop. Anything that needs those
 * fields uses {@link ReviewDetailDto}, which only the author and administrators ever receive.
 *
 * <p>{@code authorDisplayName} is null for {@code DRIVER} reviews regardless of what is stored:
 * feedback to a delivery partner stays anonymous, since the recipient may be able to infer the
 * reviewer from the order and delivery details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewDto {

    @NotNull
    private UUID id;

    @NotNull
    private ReviewEntityType entityType;

    @NotNull
    private String entityId;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private int rating;

    private String comment;

    /** "Priya R.", or null when the author is withheld or the order carried no name. */
    private String authorDisplayName;

    private RoleName authorRole;

    @NotNull
    private Instant createdAt;
}
