package com.fooddelivery.reviews.enums;

import com.fooddelivery.common.enums.ReviewEntityType;
import com.fooddelivery.common.enums.RoleName;

/** Controls whether a review is discoverable publicly or only by its order participants. */
public enum ReviewVisibility {
    PUBLIC,
    PRIVATE;

    /** Public discovery and rating aggregates are reserved for customer restaurant/product reviews. */
    public static ReviewVisibility forReview(RoleName authorRole, ReviewEntityType entityType) {
        return authorRole == RoleName.CUSTOMER
                        && (entityType == ReviewEntityType.RESTAURANT || entityType == ReviewEntityType.PRODUCT)
                ? PUBLIC
                : PRIVATE;
    }
}
