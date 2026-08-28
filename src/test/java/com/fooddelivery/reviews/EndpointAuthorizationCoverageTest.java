package com.fooddelivery.reviews;

import com.fooddelivery.common.test.EndpointAuthorizationCoverage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every HTTP endpoint in this module must carry an authorization rule, at class or method level.
 *
 * <p>Added 2026-08-28 (FOLLOW_UPS item 13a). Four modules had this test and thirteen did not, which
 * is how InternalUserController came to have authorization on none of its six endpoints, including
 * the one that grants a role.
 *
 * <p>Reflective: no Spring context, no database and no broker, so an infrastructure failure cannot
 * skip it.
 */
class EndpointAuthorizationCoverageTest {

    private static final String BASE_PACKAGE = "com.fooddelivery";

    /** Floor, not an exact count: adding endpoints must not break the build. */
    private static final int MINIMUM_EXPECTED_ENDPOINTS = 1;

    /**
     * Intentionally anonymous. Adding an entry is a deliberate, reviewable act; forgetting an
     * authorization annotation is not.
     *
     * <p>placeholder
     */
    private static final Set<String> INTENTIONALLY_ANONYMOUS = Set.of();

    /**
     * A scan finding no controllers would report "nothing unprotected" and pass while guarding
     * nothing. Assert it found endpoints before trusting what it says about them.
     */
    @Test
    void theScanActuallyFindsEndpoints() {
        assertThat(EndpointAuthorizationCoverage.countEndpoints(BASE_PACKAGE))
                .describedAs("endpoints discovered under " + BASE_PACKAGE)
                .isGreaterThanOrEqualTo(MINIMUM_EXPECTED_ENDPOINTS);
    }

    @Test
    void everyEndpointCarriesAnAuthorizationRule() {
        List<EndpointAuthorizationCoverage.Unprotected> unprotected =
                EndpointAuthorizationCoverage.scan(BASE_PACKAGE, INTENTIONALLY_ANONYMOUS);

        assertThat(unprotected)
                .describedAs("Endpoints with no authorization annotation at class or method level. "
                        + "Add one, or -- if the endpoint really is public -- add it to "
                        + "INTENTIONALLY_ANONYMOUS with a comment saying why.")
                .isEmpty();
    }

    /** An allowlist that outlives the endpoint it excused silently weakens the check. */
    @Test
    void theAllowlistHasNoStaleEntries() {
        assertThat(EndpointAuthorizationCoverage.staleAllowlistEntries(BASE_PACKAGE, INTENTIONALLY_ANONYMOUS))
                .describedAs("allowlist entries matching no endpoint")
                .isEmpty();
    }
}
