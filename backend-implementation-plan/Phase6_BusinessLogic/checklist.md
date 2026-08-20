# Phase 6 Checklist

- [x] Create `@ValidEntityType` annotation and `EntityTypeValidator` class.
- [x] Create `CreateReviewDto` utilizing Jakarta validation annotations.
- [x] Add `@EnableFeignClients(basePackages = {"com.fooddelivery.common.client"})` to main configuration.
- [x] Use `RestaurantClient` from `CommonLibrary` instead of creating a local Feign client.
- [x] Add `spring-cloud-starter-contract-stub-runner` dependency for Consumer tests.
- [x] Add `spring-cloud-starter-contract-verifier` and configure Maven plugin for Provider tests (`ContractTestBase.java`).
- [x] Implement `ValidationService` wrapping Feign calls from the common library.
- [x] Implement `ReviewService` with `@Transactional` wrapping the entire dual-write logic.
- [x] Add OCC Spring Retry (`@Retryable`) on `ReviewService` for `ObjectOptimisticLockingFailureException`.
- [x] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
