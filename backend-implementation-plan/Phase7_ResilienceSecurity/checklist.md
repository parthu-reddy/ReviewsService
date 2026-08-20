# Phase 7 Checklist

- [x] Add Bucket4j Redis dependencies.
- [x] Create `RateLimitInterceptor` checking buckets.
- [x] Enable `spring.cloud.openfeign.circuitbreaker.enabled: true` in `application.yml`.
- [x] Configure precise `resilience4j` blocks in `application.yml`.
- [x] Implement Feign fallback classes that throw fast-failing Exceptions instead of returning defaults.
- [x] Name Fallback components with a unique prefix (e.g., `@Component("reviewsRestaurantClientFallback")`) to avoid shared library bean collisions.
- [x] Implement `GlobalExceptionHandler` with `@RestControllerAdvice` mapping core exception types.
- [x] Configure Kafka `DefaultErrorHandler` with `DeadLetterPublishingRecoverer`.
- [x] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
