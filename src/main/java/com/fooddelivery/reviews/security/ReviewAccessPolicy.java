package com.fooddelivery.reviews.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import com.fooddelivery.common.enums.ReviewEntityType;

import lombok.extern.slf4j.Slf4j;

/**
 * Who may read what.
 *
 * <p>One object, referenced from every read endpoint's {@code @PreAuthorize}, rather than the rule
 * restated per handler — three handlers is exactly the number where two get remembered and one does
 * not. This mirrors how {@code MoneyAccessPolicy} is used on the money surface.
 *
 * <p>The asymmetry is deliberate:
 *
 * <ul>
 *   <li><b>RESTAURANT, PRODUCT</b> — any signed-in user for public reviews. These are reviews of a business and of a
 *       dish, written to be read by whoever is deciding what to order.
 *   <li><b>DRIVER, CUSTOMER</b> — the reviewed person themself, an administrator, or an internal
 *       service. These are private participant feedback, not public reputation pages.
 * </ul>
 */
@Slf4j
@Component("reviewAccessPolicy")
public class ReviewAccessPolicy {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SERVICE = "ROLE_SERVICE";

    /** Whether the caller may read this entity's average and count. */
    public boolean canReadAggregate(Authentication authentication, ReviewEntityType entityType, String entityId) {
        if (entityType == ReviewEntityType.CUSTOMER) {
            return false;
        }
        return isAllowed(authentication, entityType, entityId);
    }

    /** Whether the caller may list this entity's individual reviews. */
    public boolean canListReviews(Authentication authentication, ReviewEntityType entityType, String entityId) {
        return isAllowed(authentication, entityType, entityId);
    }

    private boolean isAllowed(Authentication authentication, ReviewEntityType entityType, String entityId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (entityType == null) {
            return false;
        }

        if (entityType == ReviewEntityType.RESTAURANT || entityType == ReviewEntityType.PRODUCT) {
            return true;
        }

        if (hasRole(authentication, ROLE_ADMIN) || hasRole(authentication, ROLE_SERVICE)) {
            return true;
        }

        boolean isSelf = entityId != null && authentication.getName() != null && 
                entityId.trim().equalsIgnoreCase(authentication.getName().trim());
        if (!isSelf) {
            log.warn("Refused private participant review access: principal={} requested entityId={}",
                    authentication.getName(), entityId);
        }
        return isSelf;
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role::equals);
    }
}
