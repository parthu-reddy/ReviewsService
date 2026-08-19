# Phase 2 Checklist

- [ ] Configure Flyway in `application.yml`.
- [ ] Create `V1__init_schema.sql` containing the DDL for `review_aggregates`.
- [ ] Add DDL for the partitioned `reviews` table and the 3 LIST partitions to `V1__init_schema.sql`.
- [ ] Add DDL for the HASH sub-partitions for the `reviews_restaurant` list partition.
- [ ] Add DDL for the `outbox_events` table.
- [ ] Write CREATE INDEX statements for the GIN JSONB index and the partial Outbox index.
- [ ] Boot the application and allow Flyway to execute against the local PostgreSQL container.
- [ ] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
