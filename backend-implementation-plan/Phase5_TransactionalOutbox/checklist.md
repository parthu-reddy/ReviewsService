# Phase 5 Checklist

- [x] Ensure `OutboxEvent` and `IdempotencyKey` entities are imported from `common-library`, not locally recreated.
- [x] Configure `spring.kafka.producer` and `observation-enabled` settings in `application.yml`.
- [x] Create `OutboxRelayService` using `@Scheduled` and `@Transactional`.
- [x] Provide explicit partition keys (e.g., `reviewId`) when calling `kafkaTemplate.send()`.
- [x] Create an `AdminDlqController` REST endpoint for replaying messages from the Dead Letter Queue.
- [x] Ensure any Kafka consumers contain `@DltHandler` methods to safely route terminal failures.
- [x] Enforce idempotency checks using the DB (not Redis) and hash via `objectMapper.writeValueAsString()`.
- [x] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
