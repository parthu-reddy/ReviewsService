# Phase 3: Entity and Persistence Layer Plan

## 1. Architectural Objective
Map the relational schema to JPA Entities using Hibernate 6 features like `@JdbcTypeCode` for JSONB, and implement Optimistic Concurrency Control (OCC) using `@Version`.

## 2. JPA Entities

### `ReviewAggregate`
- Marked with `@Entity`. 
- Composite ID handled via `@EmbeddedId EntityKey`.
- `@Version` on the `version` field for optimistic locking.
- Domain method: `addReview(int newRating)` to encapsulate aggregate math.

### `Review`
- Marked with `@Entity`.
- Fields marked `updatable = false` since reviews are append-only.
- `metadata` annotated with `@JdbcTypeCode(SqlTypes.JSON)`.

### `OutboxEvent`
- Marked with `@Entity`.
- JSONB payload mapped with `@JdbcTypeCode(SqlTypes.JSON)`.
- Method: `markProcessed()`.

## 3. Repositories
- `ReviewRepository extends JpaRepository<Review, UUID>`. Must enforce `Pageable` on list queries.
- `AggregateRepository extends JpaRepository<ReviewAggregate, EntityKey>`.
- `OutboxRepository extends JpaRepository<OutboxEvent, UUID>`. Custom query: `findTop100ByProcessedFalseOrderByCreatedAtAsc()`.

## 4. Edge Cases & Resilience Scenarios
- **Memory Exhaustion from Unbound Queries:** To prevent GC pauses, all queries returning multiple reviews MUST be strictly paginated using Spring Data's `Pageable`.
- **N+1 Query Problems:** While denormalized, any future relational additions must be rigorously checked for accidental eager fetching.
- **Optimistic Locking Failures:** Concurrent reviews will inevitably trigger `ObjectOptimisticLockingFailureException`. The application must handle this explicitly and not let it crash the runtime.
- **No Default Values for Mathematical Computations:** Per financial integrity rules, if the mathematical calculation for `average_rating` fails or receives invalid input, it MUST fail fast and throw an exception (e.g., `IllegalArgumentException`). Never use hardcoded fallback values like `0.0` or `5.0`.
