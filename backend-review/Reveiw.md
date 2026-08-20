I've read the whole service, its dependencies in `CommonLibrary`, the platform conventions in the sibling services, and the phase plans. Compiles clean. Here's the review.

---

# ReviewsService — code review

**Verdict:** the domain design is sound (CQRS split, append-only reviews + OCC aggregate, transactional outbox, partitioned table), but the service is **not runnable and not deployable as it stands**. Five separate startup/wiring blockers, and the outbox never actually publishes. Most of them trace back to one omission: it doesn't component-scan `com.fooddelivery.common`, which every other service in the repo does.

---

## Blockers — the service cannot start

### B1. Flyway migration conflict → startup failure
[V1__init_schema.sql:47](ReviewsService/src/main/resources/db/migration/V1__init_schema.sql:47) creates `outbox_events` with `idempotency_key VARCHAR(255) NOT NULL UNIQUE`.

`common-library-1.0-SNAPSHOT.jar` ships migrations at `db/migration/common/`. Flyway's default location `classpath:db/migration` scans recursively, and no service in this repo overrides `spring.flyway.locations`. Version `1` sorts first, so afterwards `V20260817000000__add_idempotency_key_to_outbox.sql` runs:

```sql
ALTER TABLE outbox_events ADD COLUMN idempotency_key VARCHAR(255) UNIQUE;
```

No `IF NOT EXISTS` → `ERROR: column "idempotency_key" of relation "outbox_events" already exists` → Flyway fails → app dies.

Every other service lets CommonLibrary own that table (`CustomerApplication` uses `CREATE TABLE IF NOT EXISTS` at a *later* version, which is why it survives). **Fix: delete the `outbox_events` DDL and its index from V1.**

### B2. No datasource, port, Eureka, or Flyway config exists anywhere
[application.yml](ReviewsService/src/main/resources/application.yml) has no `spring.datasource.*`, no `server.port`, no `eureka.client.*`, no `spring.flyway.*`, no `spring.data.redis.host/port`. Config import is `optional:configserver:`, so a missing config server is silently tolerated — and there is **no `Deployment/reviews-service.yml`**, unlike all 20-odd sibling services. H2 is `test` scope only, so Boot fails with *"Failed to configure a DataSource: 'url' attribute is not specified"*.

### B3. Feign fallback bean doesn't exist → context fails to build
`RestaurantServiceClient` declares `fallback = RestaurantServiceClientFallback.class`. `spring.cloud.openfeign.circuitbreaker.enabled: true` plus `spring-cloud-starter-circuitbreaker-resilience4j` (confirmed on the runtime classpath via `dependency:list`, transitively from common-library) means `FeignCircuitBreakerTargeter` resolves that fallback **as a Spring bean**.

`RestaurantServiceClientFallback` is `@Component` in `com.fooddelivery.common.client`. [ReviewsApplication.java:13-16](ReviewsService/src/main/java/com/fooddelivery/reviews/ReviewsApplication.java:13) only component-scans `com.fooddelivery.reviews` — `@EntityScan` and `@EnableJpaRepositories` do not perform component scanning, and `@EnableFeignClients(basePackages=…)` only scans for `@FeignClient` interfaces. Result: `IllegalStateException: No fallback instance of type ... found for feign client restaurant-service`.

### B4. Security starter on the classpath with no filter chain → every endpoint 401
`spring-boot-starter-security:3.3.0` arrives transitively (compile scope in CommonLibrary — verified). `CommonSecurityConfig` lives in `com.fooddelivery.common.security` and is **not scanned**, and CommonLibrary registers no autoconfiguration (no `META-INF/spring/*.imports`, no `spring.factories`).

With no `SecurityFilterChain` bean, Boot's `SpringBootWebSecurityConfiguration` installs the default: `anyRequest().authenticated()` + httpBasic/formLogin + a generated password logged at boot. The gateway sends `X-User-Id`/`X-User-Roles`, never Basic credentials. Every request 401s.

### B5. Outbox rows are written but never published
`OutboxProcessor` already has a `case REVIEW: return TOPIC_REVIEW_EVENTS;` branch — but it lives in `com.fooddelivery.common.outbox.service` and is registered only via `@EnableOutbox` (as `LedgerApplication` does) or a component scan of that package. ReviewsApplication has neither, despite carrying `@EnableScheduling`. So `REVIEW_CREATED` never reaches Kafka and `outbox_events` grows unbounded. Phase 5 is written but not wired.

**B3, B4, B5 all collapse into one fix:**
```java
@SpringBootApplication(scanBasePackages = {"com.fooddelivery.reviews", "com.fooddelivery.common"})
@EnableOutbox
```

---

## High severity

### H1. `userId` comes from the request body — anyone can review as anyone
[CreateReviewRequest.java:26](ReviewsService/src/main/java/com/fooddelivery/reviews/dto/CreateReviewRequest.java:26). The platform convention is the authenticated principal from `SecurityContextFilter` (`X-User-Id` → `SecurityContextHolder`). As written, a caller forges reviews under any identity — and because of the `UNIQUE (entity_type, entity_id, user_id)` constraint, an attacker can pre-emptively **burn** a real user's ability to review an entity. Drop the field and read the principal.

### H2. The aggregate average drifts
[ReviewAggregate.java:55](ReviewsService/src/main/java/com/fooddelivery/reviews/entity/ReviewAggregate.java:55) reconstructs the running total as `averageRating × totalReviews`. But `averageRating` is persisted at scale 2 (`NUMERIC(3,2)`), so each write reintroduces up to 0.005 of error into the reconstructed sum, and the error compounds across every subsequent review. Store `rating_sum BIGINT` and derive the average for display only. This is a permanent, unrecoverable corruption of the exact number the whole service exists to produce.

### H3. Remote HTTP call inside the DB transaction
[ReviewCommandService.java:57-63](ReviewsService/src/main/java/com/fooddelivery/reviews/service/ReviewCommandService.java:57): `@Transactional` opens, then step 1 is a Feign call — 2s connect + 3s read, times up to 3 resilience4j retries with exponential backoff. A pooled DB connection is pinned for the whole window. The platform's Hikari template is `maximum-pool-size: 5`; a slow restaurant service exhausts the pool almost immediately and takes the read path down with it. Validate before opening the transaction.

### H4. Bad query params and malformed JSON return 500 instead of 400
[GlobalExceptionHandler.java:73](ReviewsService/src/main/java/com/fooddelivery/reviews/web/GlobalExceptionHandler.java:73) registers `@ExceptionHandler(Exception.class)` and the class doesn't extend `ResponseEntityExceptionHandler`. `ExceptionHandlerExceptionResolver` runs *before* `DefaultHandlerExceptionResolver`, so the catch-all swallows the exceptions Spring would otherwise turn into 400s:

- `?entityType=FOO` → `MethodArgumentTypeMismatchException` → **500**
- missing `entityId` → `MissingServletRequestParameterException` → **500**
- malformed body → `HttpMessageNotReadableException` → **500**

…all reported as *"An unexpected error occurred."*

### H5. Upstream outage is reported to clients as 400 Bad Request
`RestaurantServiceClientFallback` throws `IllegalStateException` to fail fast; [GlobalExceptionHandler.java:57](ReviewsService/src/main/java/com/fooddelivery/reviews/web/GlobalExceptionHandler.java:57) maps `IllegalStateException` → 400. So "the restaurant service is down" is a client error the caller must not retry. Phase 7's own plan specifies **503**. The same handler also converts an outbox serialization failure into a 400. Notably, `mistakes_and_improvements.md` records this mapping as an *improvement* — it's the bug.

### H6. The rate limiter is keyed on a client-controlled header
[RateLimitInterceptor.java:45-49](ReviewsService/src/main/java/com/fooddelivery/reviews/config/RateLimitInterceptor.java:45) takes the first `X-Forwarded-For` entry. Spring Cloud Gateway *appends* to XFF rather than replacing it, so a client sending its own `X-Forwarded-For: <random>` per request gets a fresh bucket every time — the limiter is entirely bypassable, on the endpoint whose stated purpose is stopping rating manipulation. Take the *last* hop, or count from the right by trusted-proxy depth. (Also: the plan asked for per-*user* limits; per-IP puts every carrier-NAT user in one bucket.)

### H7. A Redis blip takes down the entire service
`bucket.tryConsume(1)` throws `RedisException` straight out of `preHandle` → 500 on every request under `/api/v1/reviews/**`, including reads that would otherwise be served fine from Postgres. Fail open (log + allow) rather than fail closed.

---

## Medium severity

| # | Issue |
|---|---|
| M1 | **Bucket4j keys never expire.** `LettuceBasedProxyManager.builderFor(client).build()` uses the default no-op expiration strategy — every distinct IP leaves a permanent Redis key. Add `.withExpirationStrategy(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(...))`. |
| M2 | `BucketConfiguration` is rebuilt on **every request** in `preHandle`. Hoist to a field. |
| M3 | [Bucket4jConfig.java:14](ReviewsService/src/main/java/com/fooddelivery/reviews/config/Bucket4jConfig.java:14) casts `getNativeClient()` to `RedisClient` — `ClassCastException` under `RedisClusterClient`, NPE if uninitialized. Guard with `instanceof`. |
| M4 | [RedisConfiguration.java:24](ReviewsService/src/main/java/com/fooddelivery/reviews/config/RedisConfiguration.java:24) enables Jackson default typing with `LaissezFaireSubTypeValidator` — the textbook gadget-deserialization sink. Only one DTO is ever cached; use `Jackson2JsonRedisSerializer<ReviewAggregateDto>`. |
| M5 | **Cache can pin a stale value for 24h.** `RedisCacheUpdater` swallows Redis failures with no retry and no eviction; and being `@Async` on `AFTER_COMMIT` with a pre-computed DTO, two concurrent creates can commit v2→v3 but write the cache v3→v2. Evicting the key instead of writing it fixes both. |
| M6 | **Stampede "protection" that doesn't protect.** [ReviewQueryService.java:51-67](ReviewsService/src/main/java/com/fooddelivery/reviews/service/ReviewQueryService.java:51): lock losers `Thread.sleep(100)` up to 5× on a servlet thread and then hit the DB anyway — net effect is +500ms latency *plus* the same DB load. Also `delete(lockKey)` in `finally` can delete a lock another thread acquired after TTL expiry (no fencing token). |
| M7 | **Cache penetration.** `fetchFromDbAndWarmCache` returns the zero-DTO for a missing aggregate without caching it, so every request for an unrated entity reaches Postgres forever. |
| M8 | **No index supports the read path.** V1 indexes only `metadata` (GIN) and the outbox. `findByEntityTypeAndEntityId` + `ORDER BY created_at DESC` sorts the whole partition for a hot restaurant, and `Page` adds a `COUNT(*)` per request. Add `(entity_type, entity_id, created_at DESC, id)`; consider keyset pagination. Conversely, **nothing queries `metadata`** — that GIN index is pure write cost. |
| M9 | **No `@Retryable` on OCC**, contrary to Phase 6's plan (`spring-retry` is already on the classpath). Two concurrent reviews → one gets a 409 it could have retried through. Worse for the *first* review of an entity: no row exists, both transactions INSERT, one gets a PK violation surfaced as *"duplicate entry"*, which is simply wrong. |
| M10 | **`metadata` is an unvalidated raw JSON string.** Invalid JSON reaches Postgres as `jsonb`, raises SQLSTATE 22P02, which Spring maps to `DataIntegrityViolationException` → the client gets **409 "duplicate entry"** for a malformed body. |
| M11 | **No size limits** on `comment` (`TEXT`) or `metadata` (`jsonb`). One request can store an arbitrarily large blob. Phase 6's checklist explicitly called for `@Size`. |
| M12 | `Page<ReviewResponseDto>` is serialized directly — Spring Data 3.3 warns that `PageImpl` JSON is unsupported and not a stable contract. Use `PagedModel`. |
| M13 | [ReviewController.java:31](ReviewsService/src/main/java/com/fooddelivery/reviews/web/ReviewController.java:31) injects `ReviewRepository` and maps entities in the web layer, bypassing `ReviewQueryService`; also not `@Transactional(readOnly = true)`. |

---

## Low severity

- `LocalDateTime.now()` + `TIMESTAMP` throughout — server-local time, no zone. Use `Instant`/`OffsetDateTime` + `TIMESTAMPTZ`.
- Error bodies are a bare `Map`; success bodies are `ApiResponse<T>`. Two shapes for one API.
- The cache-key format and the 24h TTL are duplicated verbatim in `ReviewQueryService` and `RedisCacheUpdater`.
- Empty aggregate returns `BigDecimal.ZERO` (scale 0) → serializes as `0`, while every other path returns `0.00`.
- `page`/`size` unvalidated — `size<=0` throws from `PageRequest.of` and leaks the raw exception text.
- **`PARTITION BY LIST` with no `DEFAULT` partition** — adding an `EntityType` value (the enum exists to be extended) makes every insert for that type fail until a migration lands.
- 429 response has no JSON content type and no `Retry-After`.
- Unchecked cast on `redisTemplate.opsForValue().get(...)` → `ClassCastException` → 500 if the key ever holds another type.
- A non-UUID `entityId` reaches `InternalRestaurantController.outletExists` → `UUID.fromString` throws → upstream 500 → 500 here, for what is a 400.
- Dead config/deps: `spring.cache.type: redis` + `spring-boot-starter-cache` with no `@EnableCaching` anywhere; `reactor-core` unused; `@Data` (mutable setters) on the identifier types `EntityKey`/`ReviewId`.
- [application-contract-test.yml](ReviewsService/src/test/resources/application-contract-test.yml) is a copy-paste from another service — `r2`, `olamaps`, `brevo`, `exotel`, `twilio`, `stripe`, `razorpay`, `ondc`, `delivery.executive.radius` are all irrelevant here, and it uses the deprecated `spring.redis.*` key.
- Stale `.gitkeep` files in `config/` and `web/`, both of which now hold classes.
- `DRIVER` and `PRODUCT` skip existence validation entirely (logged TODO) — two-thirds of the API accepts any `entityId` string.

---

## Tests and process

**Zero tests.** `src/test/java` does not exist. The pom configures `spring-cloud-contract-maven-plugin` with `baseClassForTests=com.fooddelivery.reviews.contract.ContractTestBase` — a class that doesn't exist — and there's no `src/test/resources/contracts` directory. `failOnNoContracts=false` keeps the build green, so the misconfiguration is invisible. Testcontainers (Postgres/Kafka/Redis), RestAssured, Awaitility, the contract verifier and the stub runner are all declared and entirely unused.

**Plan vs. reality.** Every checklist box across all 7 phases is unchecked, yet `mistakes_and_improvements.md` documents Phase 7 as complete. Items the plans explicitly call for and the code lacks: `@Retryable` on OCC, `@Size` on the DTO, consumer + provider contract tests, `DeadLetterPublishingRecoverer`, `AdminDlqController`, 503 fail-fast, Flyway configuration, per-user rate limits.

**Not deployable.** No `Dockerfile`, no `Deployment/reviews-service.yml`, no `deploy_ReviewsService.sh`, no docker-compose entry, and **no API Gateway route**. There is currently no path by which authenticated traffic can reach this service — which the README's "Only use Oracle for deployment" mandate presumes.

---

## What's good

The bones are right, and worth saying so:

- Append-only `Review` + separately-versioned `ReviewAggregate` is the correct shape, and `Persistable` with a transient `isNew` correctly avoids Hibernate's select-before-insert on an assigned UUID PK.
- The transactional outbox write sits in the same transaction as the review and the aggregate — genuinely atomic, no dual-write hole. The `AFTER_COMMIT` local event for cache warming is exactly the right hook.
- The composite LIST→HASH partitioning is well-formed: the unique constraint correctly includes both partition-key columns at both levels, which is the constraint people usually get wrong.
- `@Version` OCC on the aggregate with a 409 mapping is the right call over pessimistic locking.
- The fail-fast fallback (throw, never return a default) is the right instinct for a service whose output feeds ranking.

## Suggested order of work

1. Delete `outbox_events` from V1 (**B1**).
2. `scanBasePackages` + `@EnableOutbox` on `ReviewsApplication` (**B3, B4, B5**).
3. Add `Deployment/reviews-service.yml`, a Dockerfile, and a gateway route (**B2**).
4. Take `userId` from the security context (**H1**).
5. Add `rating_sum` and recompute the average from it (**H2**) — a migration gets harder with every review written.
6. Move the Feign call out of the transaction (**H3**).
7. Fix the exception handler: drop the `Exception` catch-all in favour of extending `ResponseEntityExceptionHandler`, and split upstream failures to 503 (**H4, H5**).
8. Then the rate limiter (**H6, H7, M1**) and a first integration test.

---

## Remediation Checklist

### Blockers
- [x] **B1**: Delete `outbox_events` from `V1__init_schema.sql`.
- [x] **B2**: Add `Deployment/reviews-service.yml`, Dockerfile, and a gateway route.
- [x] **B3, B4, B5**: Add `scanBasePackages` + `@EnableOutbox` on `ReviewsApplication`.

### High Severity
- [x] **H1**: Take `userId` from the security context instead of request body.
- [x] **H2**: Add `rating_sum` to `ReviewAggregate` and recompute the average from it.
- [x] **H3**: Move the remote Feign call out of the database transaction in `ReviewCommandService`.
- [x] **H4, H5**: Fix the exception handler to extend `ResponseEntityExceptionHandler` and map upstream errors properly (e.g., 503).
- [x] **H6, H7**: Fix the rate limiter to use the correct proxy IP and fail-open on Redis errors.

### Medium Severity
- [x] **M1**: Add expiration strategy for Bucket4j Redis keys.
- [x] **M2**: Hoist `BucketConfiguration` to a field instead of rebuilding per request.
- [x] **M3**: Add `instanceof` guard when fetching Redis native client in `Bucket4jConfig`.
- [x] **M4**: Replace `LaissezFaireSubTypeValidator` with `Jackson2JsonRedisSerializer<ReviewAggregateDto>`.
- [x] **M5**: Fix cache pinning by using `redisTemplate.delete(key)` instead of setting to null.
- [x] **M6**: Use distributed locks (`setIfAbsent`) for cache stampede protection.
- [x] **M7**: Cache empty DTOs to stop cache penetration.
- [x] **M8**: Add appropriate index `(entity_type, entity_id, created_at DESC, id)` for the read path.
- [x] **M9**: Add `@Retryable` to OCC transactions.
- [x] **M10, M11**: Add payload size limits and JSON validation to `CreateReviewRequest`.
- [x] **M12**: Use `PagedModel` instead of `Page<T>` for JSON serialization.
- [x] **M13**: Stop bypassing `ReviewQueryService` and mapping entities in the web layer (`ReviewController`).

### Low Severity
- [x] **L1**: Use `Instant` and `OffsetDateTime` instead of `LocalDateTime.now()`.
- [x] **L2**: Unify error and success response bodies (use `ApiResponse<T>`).
- [x] **L3**: Deduplicate the cache-key format and TTL logic.
- [x] **L4**: Fix serialization of empty aggregate zeroes.
- [x] **L5**: Validate `page` and `size` parameters.
- [x] **L6**: Add a `DEFAULT` partition for `PARTITION BY LIST`.
- [x] **L7**: Add proper 429 JSON response structure.
- [x] **L8**: Guard unchecked cast on `redisTemplate.opsForValue().get(...)`.
- [x] **L9**: Catch non-UUID parsing exceptions for `entityId`.
- [x] **L10**: Remove dead configurations and unused dependencies (e.g., `reactor-core`).
- [x] **L11**: Clean up `application-contract-test.yml`.
- [x] **L12**: Delete stale `.gitkeep` files.
- [x] **L13**: Validate existence for DRIVER and PRODUCT entity types (deferred / logged as TODO until upstream services are available).