# Phase 5 Checklist

- [ ] Add Spring Kafka dependencies.
- [ ] Configure `spring.kafka.producer` settings in `application.yml`.
- [ ] Create `OutboxRelayService`.
- [ ] Annotate the relay method with `@Scheduled` and `@Transactional`.
- [ ] Fetch the events using the `findTop100ByProcessedFalseOrderByCreatedAtAsc` repository method.
- [ ] Publish to Kafka and catch `ExecutionException` / `InterruptedException` to prevent data loss.
- [ ] Configure Kafka producer factory to enable Micrometer Observation (`producerFactory.setObservationEnabled(true)`) to propagate Trace context.
- [ ] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
