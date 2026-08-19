# Phase 7: Resilience, Security, and Error Handling Plan

## 1. Architectural Objective
Guarantee system stability against extreme load and external failure via Bucket4j, Resilience4j, Dead Letter Queues (DLQ), and a uniform `@RestControllerAdvice`.

## 2. Bucket4j Rate Limiting
- Implement interceptor tied to clustered Redis limits.
- E.g., 100 requests / minute / user.

## 3. Resilience4j & OpenFeign Integration
- Explicitly enable OpenFeign circuit breakers in `application.yml`.
- Define specific Resilience4j instances (`retry`, `circuitbreaker`, `timelimiter`) directly in `application.yml`.
- **Fail-Fast Fallbacks:** All Feign client fallback implementations MUST throw explicit, fast-failing `RuntimeException`s (e.g. `ExternalServiceUnavailableException`) rather than silently returning default/null values, to preserve data integrity.
- **Fallback Bean Naming:** Fallback beans MUST be prefixed with the service name or explicitly named (e.g., `@Component("reviewsRestaurantClientFallback")`) to prevent `ConflictingBeanDefinitionException` with shared library fallbacks.

## 4. Uniform Exception Handling
- `@RestControllerAdvice` mapping:
  - `MethodArgumentNotValidException` -> 400 Bad Request
  - `EntityNotFoundException` -> 404 Not Found
  - `ObjectOptimisticLockingFailureException` -> 409 Conflict
  - `Exception` -> 500 Internal Server Error

## 5. DLQ Configuration
- Configure `DeadLetterPublishingRecoverer` for any Kafka consumer configurations.

## 6. Edge Cases & Resilience Scenarios
- **Proxy/LB Rate Limit Blackholing:** Parse the `X-Forwarded-For` headers accurately to avoid blocking all legitimate users via Bucket4j.
- **Circuit Breaker Half-Open State:** Ensure proper wait duration in open state to prevent rapid flapping.
- **Poison Pill Blocking Partitions:** Without a DLQ and a `DeadLetterPublishingRecoverer`, bad messages block partitions indefinitely.
- **Silent Failure Cascades:** Returning default mock objects from Feign Fallbacks when an upstream service is down causes downstream math and transactions to silently corrupt data. Fallbacks must fail-fast and throw.
