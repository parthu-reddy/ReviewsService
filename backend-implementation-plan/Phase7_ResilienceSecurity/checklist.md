# Phase 7 Checklist

- [ ] Add Bucket4j Redis dependencies.
- [ ] Create `RateLimitInterceptor` checking buckets.
- [ ] Add Resilience4j dependency.
- [ ] Configure `resilience4j.circuitbreaker` settings in `application.yml`.
- [ ] Annotate Feign Client with `@CircuitBreaker(name="restaurantService", fallbackMethod="fallback")`.
- [ ] Implement `GlobalExceptionHandler` with `@RestControllerAdvice`.
- [ ] Define standardized `ErrorResponse` record.
- [ ] Configure Kafka `DefaultErrorHandler` with `DeadLetterPublishingRecoverer`.
- [ ] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
