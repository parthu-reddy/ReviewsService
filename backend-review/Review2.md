🔴 Three problems the fixes introduced
1. application.yml — cloud: and kafka: are nested under eureka:, not spring:

Parsed it to be sure:

spring keys : ['application', 'cache', 'config', 'data', 'datasource', 'flyway']
eureka keys : ['client', 'cloud', 'instance', 'kafka']

spring.kafka           -> MISSING
spring.cloud.openfeign -> MISSING
eureka.kafka           -> PRESENT (wrong)
eureka.cloud           -> PRESENT (wrong)

Silently lost: the Kafka producer config (acks: all, enable.idempotence, retries) that the outbox relay publishes through, and spring.cloud.openfeign.circuitbreaker.enabled + the 2s/3s Feign timeouts. The circuit breaker that H3 and Phase 7 depend on is off. Deployment/reviews-service.yml sets these correctly, so it only bites outside the config server — but that includes every local run and every test.

2. The typed Redis serializer breaks the stampede lock → 500 on every cache-miss read

RedisConfiguration now uses Jackson2JsonRedisSerializer<ReviewAggregateDto> for all values (good for M4), but ReviewQueryService still writes a String lock token through the same template and reads it back for the fencing check. I ran the exact serializer pair:

token stored as: "3f2a9c1e-0000-4444-8888-abcdefabcdef"
DESERIALIZE THREW -> org.springframework.data.redis.serializer.SerializationException
   message: Cannot construct instance of ReviewAggregateDto ... no String-argument
            constructor/factory method to deserialize from String value

That read is in the finally block of the lock-acquired branch, so it replaces the return value. Every cache miss that wins the lock throws — i.e. the first read of any entity after a write eviction. The fencing check is also still GET-then-DELETE (not atomic), and the losing branch still Thread.sleep(100) × 5 before hitting the DB anyway, so M6's original complaint stands.

3. Rate limiting on a raw X-User-Id is weaker than the IP version it replaced

RateLimitInterceptor now keys solely on the X-User-Id header, falling back to the literal string "anonymous". Two consequences:

Every unauthenticated caller on earth shares one 100-req/min bucket. That's a self-inflicted DoS on the public read path.
The header is client-supplied and unverified at this layer, so a caller can rotate X-User-Id per request for unlimited buckets — the same bypass H6 described, just moved from XFF to a different header. The IP resolution was removed entirely, so H6's actual fix (take the client IP from the right of X-Forwarded-For) isn't in.
🟡 Partially fixed
H1 — userId is out of the request body ✅, but the controller reads @RequestHeader("X-User-Id") rather than the authenticated principal. CommonSecurityConfig is active so the gateway is the real trust boundary today, but the decision is now duplicated in two places, a missing header yields 400 instead of 401, and it hardcodes a string HeaderConstants.HEADER_USER_ID already defines.
H5 — the Feign-fallback case correctly returns 503 ✅, but it's implemented as a blanket IllegalStateException → 503. So writeOutboxEvent's serialization failure and Bucket4jConfig's "Unsupported Redis native client" also report 503. I'd created ExternalServiceUnavailableException (503) and ReviewEventSerializationException (500) for exactly this — they're sitting unused.
M9 — @Retryable covers ObjectOptimisticLockingFailureException ✅, but the first-insert race is still open: two concurrent first reviews for an entity both INSERT the aggregate, one gets a PK violation → DataIntegrityViolationException → 409 "duplicate entry", which isn't retried and is the wrong message. Needs an INSERT … ON CONFLICT DO NOTHING pre-step.
L5 — regressed, proven: ?size=5000 returns 500, not 400.
>>> size=5000  status=500 exception=jakarta.validation.ConstraintViolationException

@Validated on the controller routes param constraints through the AOP proxy, and nothing handles ConstraintViolationException, so it lands in the Exception.class catch-all.

L3 — one getCacheKey helper ✅, but it lives on ReviewQueryService and is called from the listener; my cache/ReviewCacheKeys duplicate is orphaned.
T1 — the dangling contract config is gone and contextLoads passes ✅, but that's the only test, and it mocks out Redis and disables Flyway, so it validates neither the migration nor any endpoint.
⬜ Still untouched
M1 — Bucket4j has no expiration strategy; every rate-limit key lives in Redis forever.
L13 — DRIVER/PRODUCT still log-and-skip existence validation.
Dead code — validation/, cache/, exception/ packages I created are referenced by nothing (validator/ won instead, which is fine — the duplicates should go).
spring.cache.type: redis still in application.yml with no @EnableCaching.
application-test.yml inherits spring.main.web-application-type: none, so no web-layer test can run under the test profile without an override — that's what blocked my first probe run.
Port mismatch: application.yml defaults to 8089, Dockerfile and Deployment/reviews-service.yml say 8085.
Datasource points at the shared food_delivery DB rather than a dedicated reviews_db (Database-per-Service is the design doc's stated foundation), and reviews_db isn't in init-multiple-dbs.sql.
No docker-compose.yml entry.
All 7 phase checklists still have zero ticked boxes.
