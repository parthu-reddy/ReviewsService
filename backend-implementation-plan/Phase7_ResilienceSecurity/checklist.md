# Phase 7 Checklist

- [ ] Add Bucket4j Redis dependencies.
- [ ] Create `RateLimitInterceptor` checking buckets.
- [ ] Enable `spring.cloud.openfeign.circuitbreaker.enabled: true` in `application.yml`.
- [ ] Configure precise `resilience4j` blocks in `application.yml`.
- [ ] Implement Feign fallback classes that throw fast-failing Exceptions instead of returning defaults.
- [ ] Name Fallback components with a unique prefix (e.g., `@Component("reviewsRestaurantClientFallback")`) to avoid shared library bean collisions.
- [ ] Implement `GlobalExceptionHandler` with `@RestControllerAdvice` mapping core exception types.
- [ ] Configure Kafka `DefaultErrorHandler` with `DeadLetterPublishingRecoverer`.
- [ ] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
