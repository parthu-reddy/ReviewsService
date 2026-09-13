package contracts.messaging

/*
 * review-events: a customer's review being recorded.
 *
 * Published by ReviewCommandService through the outbox, and consumed by RestaurantApplication's
 * ReviewEventConsumer, which writes the outlet's displayed rating from it.
 *
 * Added 2026-09-12. The payload KEYS are already protected more strongly than a contract can manage:
 * they live in EventPayloadConstants, shared by producer and consumer, so a rename is a compile
 * error in both modules at once. What that does not cover is the envelope -- the topic the event
 * lands on, the eventType header OutboxProcessor stamps, and the JSON types the consumer parses.
 * This pins those.
 *
 * totalReviews and averageRating are the aggregate's state AFTER the write, not deltas. That is
 * deliberate and load-bearing: the consumer assigns them verbatim, so a redelivered event is
 * idempotent by construction rather than by remembering it has been seen. averageRating travels as
 * a STRING because it is a BigDecimal at both ends -- sending it as a JSON number would put it
 * through a double.
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("Should publish REVIEW_CREATED to review-events when a customer reviews an order")
    label("review_events_created")
    input { triggeredBy('fireReviewCreated()') }
    outputMessage {
        sentTo('review-events')
        headers { header('eventType', 'REVIEW_CREATED') }
        body([
            reviewId: $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            orderId: $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            entityType: "RESTAURANT",
            entityId: $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            userId: $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            rating: 5,
            totalReviews: 7,
            averageRating: "4.29",
            timestamp: $(producer(regex('.+')))
        ])
    }
}
