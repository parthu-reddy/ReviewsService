package com.fooddelivery.reviews.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import com.fooddelivery.common.enums.RoleName;

/** Resolves the selected portal role only when it is present in the verified security context. */
@Component
public class ReviewActorResolver {

    public RoleName requireReviewRole(Authentication authentication, RoleName selectedRole) {
        if (authentication == null || !authentication.isAuthenticated()
                || selectedRole == null || selectedRole == RoleName.ADMIN) {
            throw new AccessDeniedException("This account role cannot take part in order reviews.");
        }

        boolean authenticatedWithRole = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals("ROLE_" + selectedRole.name()));
        if (!authenticatedWithRole) {
            throw new AccessDeniedException("The selected review role is not in the authenticated account.");
        }
        return selectedRole;
    }
}
