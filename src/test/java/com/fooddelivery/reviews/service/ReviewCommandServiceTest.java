package com.fooddelivery.reviews.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.AggregateType;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.dto.order.OrderReviewContextDto;
import com.fooddelivery.common.outbox.entity.OutboxEventEntity;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.reviews.dto.CreateReviewRequest;
import com.fooddelivery.reviews.dto.ReviewDetailDto;
import com.fooddelivery.reviews.dto.ReviewEntryRequest;
import com.fooddelivery.reviews.entity.Review;
import com.fooddelivery.reviews.entity.ReviewAggregate;
import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.reviews.enums.ReviewRejectionReason;
import com.fooddelivery.reviews.event.AggregateUpdatedLocalEvent;
import com.fooddelivery.reviews.exception.ReviewNotAllowedException;
import com.fooddelivery.reviews.repository.AggregateRepository;
import com.fooddelivery.reviews.repository.ReviewRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Writing reviews: what lands, what is refused, and what is published.
 *
 * <p>The properties under test are the ones a customer can never repair if they are wrong. A review
 * cannot be edited, so a half-accepted submission is permanent, and an outbox key that collides
 * fails the whole transaction rather than one row.
 */
@ExtendWith(MockitoExtension.class)
class ReviewCommandServiceTest {

    private static final UUID ORDER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String OUTLET_ID = "66666666-6666-6666-6666-666666666666";
    private static final String DRIVER_ID = "77777777-7777-7777-7777-777777777777";
    private static final String USER_ID = "88888888-8888-8888-8888-888888888888";

    @Mock private ReviewRepository reviewRepository;
    @Mock private AggregateRepository aggregateRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ReviewEligibilityService eligibilityService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TransactionTemplate transactionTemplate;

    private ReviewCommandService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ReviewCommandService(reviewRepository, aggregateRepository,
                outboxEventRepository, eligibilityService, new ObjectMapper(),
                eventPublisher, transactionTemplate);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
        lenient().when(eligibilityService.resolve(any(), any())).thenReturn(context());
        lenient().when(reviewRepository.findByOrderId(any())).thenReturn(List.of());
        lenient().when(aggregateRepository.findById(any())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void everyEntryIsWrittenWithTheOrderAndTheSnapshottedAuthor() {
        List<ReviewDetailDto> written = service.createReviews(
                request(entry(EntityType.RESTAURANT, OUTLET_ID, 5, "Great"),
                        entry(EntityType.DRIVER, DRIVER_ID, 4, null)),
                USER_ID);

        assertThat(written).hasSize(2);
        assertThat(written).allSatisfy(r -> {
            assertThat(r.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(r.getUserId()).isEqualTo(USER_ID);
        });

        ArgumentCaptor<List<Review>> saved = ArgumentCaptor.forClass(List.class);
        verify(reviewRepository).saveAllAndFlush(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue()).allSatisfy(r -> {
            assertThat(r.getOrderId()).isEqualTo(ORDER_ID);
            // Snapshotted at write time so reads never fan out to identity-service.
            assertThat(r.getAuthorDisplayName()).isEqualTo("Priya R.");
        });
    }

    /**
     * One flush for the whole submission. Because a review cannot be edited, a partially accepted
     * submission would leave the customer permanently unable to repair the half that failed.
     */
    @Test
    void theWholeSubmissionIsWrittenInOneFlush() {
        service.createReviews(
                request(entry(EntityType.RESTAURANT, OUTLET_ID, 5, null),
                        entry(EntityType.DRIVER, DRIVER_ID, 4, null)),
                USER_ID);

        verify(reviewRepository).saveAllAndFlush(any());
        verify(transactionTemplate).execute(any());
    }

    @Test
    void eachDistinctTargetGetsItsOwnAggregate() {
        service.createReviews(
                request(entry(EntityType.RESTAURANT, OUTLET_ID, 5, null),
                        entry(EntityType.DRIVER, DRIVER_ID, 3, null)),
                USER_ID);

        ArgumentCaptor<ReviewAggregate> agg = ArgumentCaptor.forClass(ReviewAggregate.class);
        verify(aggregateRepository, org.mockito.Mockito.times(2)).saveAndFlush(agg.capture());
        assertThat(agg.getAllValues()).extracting(a -> a.getId().getEntityType())
                .containsExactlyInAnyOrder(EntityType.RESTAURANT, EntityType.DRIVER);
        assertThat(agg.getAllValues()).allSatisfy(a -> assertThat(a.getTotalReviews()).isEqualTo(1));
    }

    @Test
    void oneCacheEvictionEventIsPublishedPerEntity() {
        service.createReviews(
                request(entry(EntityType.RESTAURANT, OUTLET_ID, 5, null),
                        entry(EntityType.DRIVER, DRIVER_ID, 3, null)),
                USER_ID);

        verify(eventPublisher, org.mockito.Mockito.times(2))
                .publishEvent(any(AggregateUpdatedLocalEvent.class));
    }

    // ------------------------------------------------------------------ the outbox

    @Test
    void theOutboxKeyIsScopedToTheOrderSoASecondOrderDoesNotCollide() {
        service.createReviews(request(entry(EntityType.RESTAURANT, OUTLET_ID, 5, null)), USER_ID);

        ArgumentCaptor<OutboxEventEntity> event = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(event.capture());

        // outbox_events.idempotency_key is UNIQUE. Keyed on the user, a customer's second order from
        // the same restaurant would collide on insert and fail the whole submission.
        assertThat(event.getValue().getIdempotencyKey())
                .isEqualTo("review:RESTAURANT:" + OUTLET_ID + ":" + ORDER_ID)
                .doesNotContain(USER_ID);
    }

    @Test
    void theOutboxPayloadCarriesEverythingTheConsumerReads() throws Exception {
        service.createReviews(request(entry(EntityType.RESTAURANT, OUTLET_ID, 4, null)), USER_ID);

        ArgumentCaptor<OutboxEventEntity> event = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(event.capture());
        OutboxEventEntity saved = event.getValue();

        assertThat(saved.getAggregateType()).isEqualTo(AggregateType.REVIEW);
        assertThat(saved.getEventType()).isEqualTo(EventType.REVIEW_CREATED);
        // Partition key: per-entity ordering is what makes absolute assignment safe downstream.
        assertThat(saved.getAggregateId()).isEqualTo(OUTLET_ID);

        var payload = new ObjectMapper().readTree(saved.getPayload());
        assertThat(payload.get("entityType").asText()).isEqualTo("RESTAURANT");
        assertThat(payload.get("entityId").asText()).isEqualTo(OUTLET_ID);
        assertThat(payload.get("orderId").asText()).isEqualTo(ORDER_ID.toString());
        // Absolute post-write state, not a delta -- RestaurantApplication assigns these verbatim.
        assertThat(payload.get("totalReviews").asLong()).isEqualTo(1L);
        assertThat(payload.get("averageRating").asText()).isEqualTo("4.00");
    }

    @Test
    void anOutboxRowIsWrittenPerReviewNotPerSubmission() {
        service.createReviews(
                request(entry(EntityType.RESTAURANT, OUTLET_ID, 5, null),
                        entry(EntityType.DRIVER, DRIVER_ID, 4, null)),
                USER_ID);

        verify(outboxEventRepository, org.mockito.Mockito.times(2)).save(any());
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void ratingTheSameTargetTwiceInOneSubmissionIsRefusedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.createReviews(
                request(entry(EntityType.RESTAURANT, OUTLET_ID, 5, null),
                        entry(EntityType.RESTAURANT, OUTLET_ID, 1, null)),
                USER_ID))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.DUPLICATE_ENTRY);

        verify(reviewRepository, never()).saveAllAndFlush(any());
    }

    /** Immutability, at the point it is enforced: a second review for the same target is refused. */
    @Test
    void aTargetAlreadyReviewedOnThisOrderIsRefusedWithItsOwnReason() {
        when(reviewRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(
                Review.builder().entityType(EntityType.RESTAURANT).entityId(OUTLET_ID)
                        .id(UUID.randomUUID()).orderId(ORDER_ID).userId(USER_ID)
                        .rating(3).createdAt(java.time.Instant.now()).build()));

        assertThatThrownBy(() -> service.createReviews(
                request(entry(EntityType.RESTAURANT, OUTLET_ID, 5, null)), USER_ID))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.ALREADY_REVIEWED);

        verify(reviewRepository, never()).saveAllAndFlush(any());
        verify(outboxEventRepository, never()).save(any());
    }

    /**
     * The concurrent case the pre-check cannot cover: two submissions race, the unique index rejects
     * the loser. It must surface as ALREADY_REVIEWED -- not as a retryable lock failure, which would
     * tell the customer to retry something no retry can fix.
     */
    @Test
    void losingTheUniqueIndexRaceIsReportedAsAlreadyReviewed() {
        when(reviewRepository.saveAllAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_reviews_entity_order"));

        assertThatThrownBy(() -> service.createReviews(
                request(entry(EntityType.RESTAURANT, OUTLET_ID, 5, null)), USER_ID))
                .isInstanceOf(ReviewNotAllowedException.class)
                .extracting(e -> ((ReviewNotAllowedException) e).getReason())
                .isEqualTo(ReviewRejectionReason.ALREADY_REVIEWED);
    }

    /** An ineligible order must never reach the database. */
    @Test
    void anIneligibleOrderIsRefusedBeforeAnythingIsWritten() {
        when(eligibilityService.resolve(any(), any())).thenThrow(
                new ReviewNotAllowedException(ReviewRejectionReason.REVIEW_WINDOW_CLOSED, "closed"));

        assertThatThrownBy(() -> service.createReviews(
                request(entry(EntityType.RESTAURANT, OUTLET_ID, 5, null)), USER_ID))
                .isInstanceOf(ReviewNotAllowedException.class);

        verify(transactionTemplate, never()).execute(any());
        verify(reviewRepository, never()).saveAllAndFlush(any());
    }

    /** A target not on the order is refused by the eligibility service, and nothing is written. */
    @Test
    void aTargetNotOnTheOrderStopsTheWholeSubmission() {
        org.mockito.Mockito.doThrow(new ReviewNotAllowedException(
                        ReviewRejectionReason.TARGET_NOT_ON_ORDER, "not on order"))
                .when(eligibilityService).assertTargetOnOrder(any(), any(), any());

        assertThatThrownBy(() -> service.createReviews(
                request(entry(EntityType.RESTAURANT, OUTLET_ID, 5, null),
                        entry(EntityType.DRIVER, DRIVER_ID, 4, null)),
                USER_ID))
                .isInstanceOf(ReviewNotAllowedException.class);

        verify(reviewRepository, never()).saveAllAndFlush(any());
    }

    // ------------------------------------------------------------------ helpers

    private static OrderReviewContextDto context() {
        return OrderReviewContextDto.builder()
                .orderId(ORDER_ID)
                .customerId(UUID.fromString(USER_ID))
                .customerName("Priya Raman")
                .restaurantId(UUID.fromString(OUTLET_ID))
                .deliveryExecutiveId(UUID.fromString(DRIVER_ID))
                .deliveryStatus(com.fooddelivery.common.enums.DeliveryStatus.DELIVERED)
                .deliveredAt(java.time.Instant.now())
                .items(List.of())
                .build();
    }

    private static CreateReviewRequest request(ReviewEntryRequest... entries) {
        return CreateReviewRequest.builder().orderId(ORDER_ID).entries(List.of(entries)).build();
    }

    private static ReviewEntryRequest entry(EntityType type, String id, int rating, String comment) {
        return ReviewEntryRequest.builder()
                .entityType(type).entityId(id).rating(rating).comment(comment).build();
    }
}
