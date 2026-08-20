# Phase 3 Checklist

- [x] Create `EntityKey` record/class with `@Embeddable`.
- [x] Create `ReviewAggregate` entity with `@Version` and `addReview` method.
- [x] Create `Review` entity with `@JdbcTypeCode(SqlTypes.JSON)` for `metadata`.
- [x] Create `OutboxEvent` entity.
- [x] Create `ReviewRepository` and ensure paginated queries.
- [x] Create `AggregateRepository`.
- [x] Create `OutboxRepository` and add the Top 100 unprocessed query.
- [x] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
