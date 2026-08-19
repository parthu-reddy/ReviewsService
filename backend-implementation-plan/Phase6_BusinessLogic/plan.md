# Phase 6: Business Logic and Validation Plan

## 1. Architectural Objective
Enforce strict domain boundaries using custom validators and verify entity existence dynamically using OpenFeign. Implement the core review orchestration logic while strictly adhering to Consumer-Driven Contract (CDC) testing principles.

## 2. Validation Layer
- Implement `@ValidEntityType` and `EntityTypeValidator`.
- Create `CreateReviewDto` encapsulating input limits (e.g. `@Size`, `@Min`, `@Max`).

## 3. Validation via OpenFeign & Contract Testing
- **Avoid Duplication:** Per enterprise rules, DO NOT create a new `RestaurantServiceClient`. Instead, import `com.fooddelivery.common.client.RestaurantClient` from `CommonLibrary` and use its generated DTOs.
- Enable scanning: `@EnableFeignClients(basePackages = {"com.fooddelivery.common.client"})`.
- **Consumer Contract Testing:** All Feign clients must be validated via Spring Cloud Contract (`spring-cloud-starter-contract-stub-runner`). Ensure `@AutoConfigureStubRunner` is used in tests alongside `@ActiveProfiles("contract-test")` to prevent context loading crashes.
- **Provider Contract Testing:** Add `spring-cloud-starter-contract-verifier` and configure the Maven plugin so other services can safely consume our review endpoints.

## 4. Orchestration (`ReviewService`)
- Mark as `@Transactional`.
- Coordinates Feign validation -> JPA Save -> Aggregate math (with OCC retry) -> Outbox Event Save.

## 5. Edge Cases & Resilience Scenarios
- **Validation Drift (Duplicate Feign Clients):** Creating local Feign clients instead of using the `CommonLibrary` leads to contract drift and broken production logic. Always use the shared clients and OpenAPI-generated DTOs.
- **API Contract Violations:** If upstream providers change data types, standard unit tests using `@MockBean` pass but production fails. Strictly enforce CDC testing to halt the build on mismatch.
- **Optimistic Locking Retry Storm:** High-velocity entities receiving simultaneous reviews trigger locking failures. Use Spring's `@Retryable` specifically for `ObjectOptimisticLockingFailureException`.
- **Fail Fast Policy (Entity Existance):** If the upstream service is unavailable, do NOT assume the entity is valid. Fail fast with a `503 Service Unavailable`.
