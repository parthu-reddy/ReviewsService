# Phase 7: Resilience, Security, and Error Handling Plan

## 1. Architectural Objective
Guarantee system stability against extreme load and external failure via Bucket4j, Resilience4j, Dead Letter Queues (DLQ), and a uniform `@RestControllerAdvice`.

## 2. Bucket4j Rate Limiting
- Implement interceptor tied to clustered Redis limits.
- E.g., 100 requests / minute / user.

## 3. Resilience4j Circuit Breakers
- Wrap `RestaurantServiceClient` with `@CircuitBreaker`.
- Define `fallbackMethod` for graceful degradation (`503 Service Unavailable`).

## 4. Uniform Exception Handling
- `@RestControllerAdvice` mapping:
  - `MethodArgumentNotValidException` -> 400 Bad Request
  - `EntityNotFoundException` -> 404 Not Found
  - `ObjectOptimisticLockingFailureException` -> 409 Conflict
  - `Exception` -> 500 Internal Server Error

## 5. DLQ Configuration
- Configure `DeadLetterPublishingRecoverer` for any Kafka consumer configurations.

## 6. Edge Cases & Resilience Scenarios
- **Proxy/LB Rate Limit Blackholing:** A malicious actor could spoof IPs, or a Load Balancer might mask the true IP (showing only the LB's IP). If Bucket4j limits on the raw remote address, it will ban all legitimate users. We must accurately parse the `X-Forwarded-For` headers.
- **Circuit Breaker Half-Open State:** Misconfiguring the timeout limits when the breaker tests connectivity (Half-Open) can cause the breaker to flap repeatedly, destabilizing downstream services further.
- **Poison Pill Blocking Partitions:** Deterministic consumer errors (e.g. malformed JSON that will fail parsing every time it's retried) will perpetually block the Kafka partition. Spring's `DeadLetterPublishingRecoverer` is mandatory to shunt these messages to a secondary topic and allow the main stream to advance.
- **Data Leakage in Exceptions:** Returning a raw stack trace or SQL exception in an HTTP 500 response creates severe security vulnerabilities. The global handler must heavily sanitize production errors.
