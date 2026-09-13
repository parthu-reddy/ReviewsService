package com.fooddelivery.reviews.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import com.fooddelivery.reviews.enums.EntityType;

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
 *   <li><b>RESTAURANT, PRODUCT</b> — any signed-in user. These are reviews of a business and of a
 *       dish, written to be read by whoever is deciding what to order.
 *   <li><b>DRIVER</b> — the driver themself, an administrator, or an internal service. A driver's
 *       average is performance data about a named person, and the individual reviews behind it are
 *       feedback on their work. Neither belongs to an arbitrary signed-in stranger.
 * </ul>
 */
@Slf4j
@Component("reviewAccessPolicy")
public class ReviewAccessPolicy {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SERVICE = "ROLE_SERVICE";

    /** Whether the caller may read this entity's average and count. */
    public boolean canReadAggregate(Authentication authentication, EntityType entityType, String entityId) {
        return isAllowed(authentication, entityType, entityId);
    }

    /** Whether the caller may list this entity's individual reviews. */
    public boolean canListReviews(Authentication authentication, EntityType entityType, String entityId) {
        return isAllowed(authentication, entityType, entityId);
    }

    private boolean isAllowed(Authentication authentication, EntityType entityType, String entityId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (entityType == null) {
            return false;
        }

        if (entityType != EntityType.DRIVER) {
            return true;
        }

        if (hasRole(authentication, ROLE_ADMIN) || hasRole(authentication, ROLE_SERVICE)) {
            return true;
        }

        boolean isSelf = entityId != null && entityId.equals(authentication.getName());
        if (!isSelf) {
            log.warn("Refused driver review access: principal={} requested driverId={}",
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
