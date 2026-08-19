# Phase 6 Checklist

- [ ] Create `@ValidEntityType` annotation and `EntityTypeValidator` class.
- [ ] Create `CreateReviewDto` utilizing Jakarta validation annotations.
- [ ] Add Spring Cloud OpenFeign dependency.
- [ ] Add `spring-cloud-starter-contract-stub-runner` dependency for test scope.
- [ ] Implement `RestaurantServiceClient` interface.
- [ ] Implement `ValidationService` wrapping Feign calls.
- [ ] Implement `ReviewService` with `@Transactional` wrapping the entire dual-write logic.
- [ ] Add OCC Spring Retry (`@Retryable`) on `ReviewService` for `ObjectOptimisticLockingFailureException`.
- [ ] Ensure all Spring Cloud Feign client beans are natively instrumented for Micrometer trace context propagation.
- [ ] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
