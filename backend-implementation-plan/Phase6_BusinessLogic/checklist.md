# Phase 6 Checklist

- [ ] Create `@ValidEntityType` annotation and `EntityTypeValidator` class.
- [ ] Create `CreateReviewDto` utilizing Jakarta validation annotations.
- [ ] Add `@EnableFeignClients(basePackages = {"com.fooddelivery.common.client"})` to main configuration.
- [ ] Use `RestaurantClient` from `CommonLibrary` instead of creating a local Feign client.
- [ ] Add `spring-cloud-starter-contract-stub-runner` dependency for Consumer tests.
- [ ] Add `spring-cloud-starter-contract-verifier` and configure Maven plugin for Provider tests (`ContractTestBase.java`).
- [ ] Implement `ValidationService` wrapping Feign calls from the common library.
- [ ] Implement `ReviewService` with `@Transactional` wrapping the entire dual-write logic.
- [ ] Add OCC Spring Retry (`@Retryable`) on `ReviewService` for `ObjectOptimisticLockingFailureException`.
- [ ] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
