# Phase 6: Business Logic and Validation Plan

## 1. Architectural Objective
Enforce strict domain boundaries using custom validators and verify entity existence dynamically using OpenFeign. Implement the core review orchestration logic while strictly adhering to Consumer-Driven Contract (CDC) testing principles.

## 2. Validation Layer
- Implement `@ValidEntityType` and `EntityTypeValidator`.
- Create `CreateReviewDto` encapsulating input limits (e.g. `@Size`, `@Min`, `@Max`).

## 3. Validation via OpenFeign & Contract Testing
- Create `RestaurantServiceClient` using Spring Cloud OpenFeign.
- Create `ValidationService` to dynamically lookup entities based on type.
- **Consumer-Driven Contract Testing:** All Feign clients must be validated via Spring Cloud Contract (`spring-cloud-starter-contract-stub-runner`). Ensure `@AutoConfigureStubRunner` is used in tests mapped to the provider's stub artifacts (e.g., `com.fooddelivery:restaurantservice:+:stubs:8090`).
- Use the `contract-test` profile for execution.

## 4. Orchestration (`ReviewService`)
- Mark as `@Transactional`.
- Coordinates Feign validation -> JPA Save -> Aggregate math (with OCC retry) -> Outbox Event Save.

## 5. Edge Cases & Resilience Scenarios
- **API Contract Violations:** If upstream providers change data types or drop properties, standard unit tests using `@MockBean` will pass but production will fail. Strictly enforce CDC testing to halt the build on any contract mismatch.
- **Optimistic Locking Retry Storm:** High-velocity entities receiving simultaneous reviews will trigger locking failures. Use Spring's `@Retryable` specifically for `ObjectOptimisticLockingFailureException` with an exponential backoff.
- **Feign Validation Latency / Cascading Failures:** If the upstream `RestaurantService` experiences high latency, synchronous validation calls will block Tomcat threads, eventually bringing down the Review Service. This validates the absolute necessity of Circuit Breakers (addressed in Phase 7) and strict read timeouts.
- **Fail Fast Policy (Entity Existance):** If the upstream service is unavailable, do NOT assume the entity is valid. Fail fast with a `503 Service Unavailable` or `502 Bad Gateway` to prevent orphaned reviews.
- **Data Truncation / Constraint Violations:** Very large payloads for comments or invalid ratings must be rejected natively by `@Valid` Jakarta annotations before the database transaction opens.
