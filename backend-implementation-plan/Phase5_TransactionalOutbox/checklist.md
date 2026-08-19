# Phase 5 Checklist

- [ ] Ensure `OutboxEvent` and `IdempotencyKey` entities are imported from `common-library`, not locally recreated.
- [ ] Configure `spring.kafka.producer` and `observation-enabled` settings in `application.yml`.
- [ ] Create `OutboxRelayService` using `@Scheduled` and `@Transactional`.
- [ ] Provide explicit partition keys (e.g., `reviewId`) when calling `kafkaTemplate.send()`.
- [ ] Create an `AdminDlqController` REST endpoint for replaying messages from the Dead Letter Queue.
- [ ] Ensure any Kafka consumers contain `@DltHandler` methods to safely route terminal failures.
- [ ] Enforce idempotency checks using the DB (not Redis) and hash via `objectMapper.writeValueAsString()`.
- [ ] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
