# Phase 3 Checklist

- [ ] Create `EntityKey` record/class with `@Embeddable`.
- [ ] Create `ReviewAggregate` entity with `@Version` and `addReview` method.
- [ ] Create `Review` entity with `@JdbcTypeCode(SqlTypes.JSON)` for `metadata`.
- [ ] Create `OutboxEvent` entity.
- [ ] Create `ReviewRepository` and ensure paginated queries.
- [ ] Create `AggregateRepository`.
- [ ] Create `OutboxRepository` and add the Top 100 unprocessed query.
- [ ] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
