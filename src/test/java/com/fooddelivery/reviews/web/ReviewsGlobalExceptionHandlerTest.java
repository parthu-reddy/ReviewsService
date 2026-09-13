package com.fooddelivery.reviews.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.reviews.enums.ReviewRejectionReason;
import com.fooddelivery.reviews.exception.ExternalServiceUnavailableException;
import com.fooddelivery.reviews.exception.ReviewNotAllowedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The translation layer between a domain refusal and what the browser sees.
 *
 * <p>This is a cross-layer contract with no compiler behind it. The UI branches on the literal
 * string in {@code ApiResponse.errorCode} — {@code isAlreadyReviewed(code)} in `reviewCopy.ts`
 * decides whether a failed submission is shown as "you already said this" or as a red error banner.
 * If {@code ALREADY_REVIEWED} stopped arriving as a 409 carrying that exact code, the rating sheet
 * would report a failure for something that actually succeeded, and nothing in either codebase would
 * notice.
 *
 * <p>So these tests assert the wire values, not the enum. Comparing {@code reason.name()} to itself
 * would pass no matter what the client was told.
 */
class ReviewsGlobalExceptionHandlerTest {

    private final ReviewsGlobalExceptionHandler handler = new ReviewsGlobalExceptionHandler();

    @Test
    void anAlreadyReviewedRefusalIsA409CarryingTheCodeTheUiBranchesOn() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleReviewNotAllowed(
                new ReviewNotAllowedException(ReviewRejectionReason.ALREADY_REVIEWED,
                        "RESTAURANT x has already been reviewed for order y."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Literal, deliberately: this is the string RateOrderModal compares against.
        assertThat(response.getBody().getErrorCode()).isEqualTo("ALREADY_REVIEWED");
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("already been reviewed");
    }

    @Test
    void aClosedWindowIsA409SoTheSheetCanExplainIt() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleReviewNotAllowed(
                new ReviewNotAllowedException(ReviewRejectionReason.REVIEW_WINDOW_CLOSED, "closed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getErrorCode()).isEqualTo("REVIEW_WINDOW_CLOSED");
    }

    @Test
    void anUndeliveredOrderIsA409NotA400() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleReviewNotAllowed(
                new ReviewNotAllowedException(ReviewRejectionReason.ORDER_NOT_DELIVERED, "in transit"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getErrorCode()).isEqualTo("ORDER_NOT_DELIVERED");
    }

    @Test
    void someoneElsesOrderIsA403AndSaysNothingAboutWhoseItIs() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleReviewNotAllowed(
                new ReviewNotAllowedException(ReviewRejectionReason.NOT_YOUR_ORDER,
                        "Order abc does not belong to you."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getErrorCode()).isEqualTo("NOT_YOUR_ORDER");
        assertThat(response.getBody().getMessage()).doesNotContainIgnoringCase("customer");
    }

    @Test
    void anUnknownOrderIsA404() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleReviewNotAllowed(
                new ReviewNotAllowedException(ReviewRejectionReason.ORDER_NOT_FOUND, "nope"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aTargetNotOnTheOrderIsA400() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleReviewNotAllowed(
                new ReviewNotAllowedException(ReviewRejectionReason.TARGET_NOT_ON_ORDER, "not yours"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aDuplicateEntryIsA400() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleReviewNotAllowed(
                new ReviewNotAllowedException(ReviewRejectionReason.DUPLICATE_ENTRY, "twice"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Exhaustive on purpose. A reason added later without a status would otherwise reach the client
     * as whatever the enum happened to default to, and the UI would render the generic fallback
     * sentence for something the customer could have acted on.
     */
    @Test
    void everyRejectionReasonProducesAClientErrorAndItsOwnCode() {
        for (ReviewRejectionReason reason : ReviewRejectionReason.values()) {
            ResponseEntity<ApiResponse<Void>> response = handler.handleReviewNotAllowed(
                    new ReviewNotAllowedException(reason, "because"));

            assertThat(response.getStatusCode().is4xxClientError())
                    .describedAs("%s must be a 4xx -- a refusal is the caller's to fix, not a server fault", reason)
                    .isTrue();
            assertThat(response.getBody().getErrorCode())
                    .describedAs("%s must reach the client as its own code", reason)
                    .isEqualTo(reason.name());
        }
    }

    /**
     * An upstream outage is not a refusal. It has to stay a 5xx so the customer is told to try again
     * rather than being told their order is ineligible.
     */
    @Test
    void anUpstreamOutageIsA503NotARefusal() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleExternalServiceUnavailable(
                new ExternalServiceUnavailableException("order service down"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    /** The concurrent-write backstop, if it ever reaches the handler unmapped. */
    @Test
    void aDataIntegrityViolationIsAConflict() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolationException(
                new org.springframework.dao.DataIntegrityViolationException("uq_reviews_entity_order"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** An unexpected fault must not leak its message to the caller. */
    @Test
    void anUnexpectedFailureIsA500WithNoInternalDetail() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAllOtherExceptions(new IllegalStateException("jdbc url is postgres://secret@host"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).doesNotContain("secret");
    }
}
