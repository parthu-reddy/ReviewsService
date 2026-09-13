package com.fooddelivery.reviews.security;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.fooddelivery.reviews.enums.EntityType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The visibility rule, exercised.
 *
 * <p>This is the most privacy-sensitive decision in the feature: a driver who could attach a
 * one-star rating to a customer's name would also know that customer's address, because they
 * delivered to it. The rule had a structural check before it had a behavioural one, and that check
 * stayed green when the comparison was replaced with `true` — which is why these exist.
 */
class ReviewAccessPolicyTest {

    private static final String DRIVER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_DRIVER_ID = "22222222-2222-2222-2222-222222222222";

    private final ReviewAccessPolicy policy = new ReviewAccessPolicy();

    private static Authentication user(String id, String... roles) {
        return new UsernamePasswordAuthenticationToken(id, null,
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    // ---------------------------------------------------------------- restaurants and products

    @Test
    void anySignedInUserMayReadRestaurantAndProductReviews() {
        for (EntityType type : List.of(EntityType.RESTAURANT, EntityType.PRODUCT)) {
            for (String role : List.of("CUSTOMER", "RESTAURANT", "DELIVERY", "ADMIN", "SERVICE")) {
                Authentication auth = user("whoever", role);
                assertThat(policy.canReadAggregate(auth, type, "any-entity"))
                        .describedAs("%s reading %s aggregate", role, type).isTrue();
                assertThat(policy.canListReviews(auth, type, "any-entity"))
                        .describedAs("%s listing %s reviews", role, type).isTrue();
            }
        }
    }

    // ---------------------------------------------------------------- drivers

    @Test
    void aDriverMayReadTheirOwnRating() {
        Authentication auth = user(DRIVER_ID, "DELIVERY");

        assertThat(policy.canReadAggregate(auth, EntityType.DRIVER, DRIVER_ID)).isTrue();
        assertThat(policy.canListReviews(auth, EntityType.DRIVER, DRIVER_ID)).isTrue();
    }

    /** The case the whole rule exists for. */
    @Test
    void aDriverMayNotReadAnotherDriversRating() {
        Authentication auth = user(DRIVER_ID, "DELIVERY");

        assertThat(policy.canReadAggregate(auth, EntityType.DRIVER, OTHER_DRIVER_ID)).isFalse();
        assertThat(policy.canListReviews(auth, EntityType.DRIVER, OTHER_DRIVER_ID)).isFalse();
    }

    @Test
    void aCustomerMayNotReadAnyDriversRating() {
        Authentication auth = user("some-customer", "CUSTOMER");

        assertThat(policy.canReadAggregate(auth, EntityType.DRIVER, DRIVER_ID)).isFalse();
        assertThat(policy.canListReviews(auth, EntityType.DRIVER, DRIVER_ID)).isFalse();
    }

    /** A restaurant owner has no more claim on a driver's performance data than a customer does. */
    @Test
    void aRestaurantOwnerMayNotReadADriversRating() {
        assertThat(policy.canReadAggregate(user("owner", "RESTAURANT"), EntityType.DRIVER, DRIVER_ID))
                .isFalse();
    }

    @Test
    void anAdminMayReadAnyDriversRating() {
        assertThat(policy.canReadAggregate(user("root", "ADMIN"), EntityType.DRIVER, DRIVER_ID)).isTrue();
        assertThat(policy.canListReviews(user("root", "ADMIN"), EntityType.DRIVER, DRIVER_ID)).isTrue();
    }

    @Test
    void anInternalServiceMayReadAnyDriversRating() {
        assertThat(policy.canReadAggregate(user("reviews-service", "SERVICE"), EntityType.DRIVER, DRIVER_ID))
                .isTrue();
    }

    // ---------------------------------------------------------------- degenerate inputs

    @Test
    void noPrincipalMeansNoAccess() {
        assertThat(policy.canReadAggregate(null, EntityType.RESTAURANT, "x")).isFalse();
        assertThat(policy.canListReviews(null, EntityType.DRIVER, DRIVER_ID)).isFalse();
    }

    /**
     * Anonymous authentication reports `isAuthenticated() == true`, so "not null" is not enough on
     * its own -- but an anonymous principal is named "anonymousUser" and can never equal a driver id,
     * which is what keeps the DRIVER branch closed to it.
     */
    @Test
    void anAnonymousPrincipalCannotReachDriverData() {
        Authentication anon = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        assertThat(policy.canReadAggregate(anon, EntityType.DRIVER, DRIVER_ID)).isFalse();
    }

    @Test
    void aNullEntityTypeIsRefusedRatherThanDefaultingOpen() {
        assertThat(policy.canReadAggregate(user("someone", "CUSTOMER"), null, "x")).isFalse();
    }

    /** A null entity id must not accidentally match a null principal name. */
    @Test
    void aNullDriverIdIsRefused() {
        assertThat(policy.canReadAggregate(user(DRIVER_ID, "DELIVERY"), EntityType.DRIVER, null)).isFalse();
    }
}
