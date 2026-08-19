# Phase 1: Project Setup and Architecture Plan

## 1. Architectural Objective
Bootstrap a Spring Boot 3 Java 17+ microservice designed for high concurrency and DDD boundary isolation, tailored to handle generalized entity reviews. Align perfectly with the enterprise architecture using common libraries and centralized configuration.

## 2. Core Dependencies
- **Common Library** (`com.fooddelivery:common-library`)
- **Spring Cloud Config** (`spring-cloud-starter-config`)
- **Spring Web** (REST API)
- **Spring Data JPA & PostgreSQL Driver** (Persistence)
- **Flyway DB Migrations** (`flyway-core` and **CRITICAL:** `flyway-database-postgresql` for Flyway 10+)
- **Spring Data Redis & Lettuce** (Plus **CRITICAL:** `commons-pool2` for connection pooling)
- **Spring Kafka** (Outbox event publishing)
- **Spring Cloud OpenFeign** (External API validation)
- **Resilience4j & Bucket4j** (Circuit Breakers & Rate Limiting)
- **Validation (Jakarta)** (Input enforcement)
- **Micrometer OTEL & Actuator** (`spring-boot-starter-actuator`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`)
- **Swagger / OpenAPI** (`springdoc-openapi-starter-webmvc-ui` & `springdoc-openapi-maven-plugin`)
- **Integration Test Stack** (`testcontainers`, `rest-assured`, `awaitility`, `h2`)

## 3. Package Layout (Domain-Driven)
- **Base Package:** `com.fooddelivery.reviews`
- `com.fooddelivery.reviews.config`: Global configurations & `@EnableFeignClients(basePackages = {"com.fooddelivery.common.client"})`
- `com.fooddelivery.reviews.domain`: Entities, custom exceptions, domain events
- `com.fooddelivery.reviews.infrastructure`: Repositories, Kafka relays
- `com.fooddelivery.reviews.web`: Controllers, custom validators, @RestControllerAdvice

## 4. Main Application Class Configurations
To properly load shared entities (like Outbox and Idempotency) from the `common-library` without crashing Spring Boot:
- Do NOT use overlapping `scanBasePackages = {"com.fooddelivery", "com.fooddelivery.common"}`.
- Use explicit `@EntityScan(basePackages = {"com.fooddelivery.reviews", "com.fooddelivery.common"})`.
- Use explicit `@EnableJpaRepositories(basePackages = {"com.fooddelivery.reviews", "com.fooddelivery.common"})`.

## 5. Deployment, Observability, and Environment Configuration
- **Profile & Infra:** Only deploy as the `Dev` profile. Only use Oracle for deployment.
- **Config Server:** `application.yml` MUST use `spring.config.import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}`.
- **Tracing Configuration:** Set `management.tracing.enabled=true`. In Dev profile, set `management.tracing.sampling.probability=1.0` and exporter `management.otlp.tracing.endpoint`.
- **MDC Correlation:** Inject `traceId` and `spanId` into logging patterns.

## 6. Edge Cases & Resilience Scenarios
- **Maven Surefire Java 17+ Bug:** We MUST add `<argLine>-XX:+EnableDynamicAgentLoading -Xshare:off -Dnet.bytebuddy.experimental=true</argLine>` in the `maven-surefire-plugin`.
- **Contract Test Context Loading:** `application-contract-test.yml` must exclude Redis auto-configurations to prevent whack-a-mole context loading failures.
- **Flyway 10 Crash:** Booting without `flyway-database-postgresql` will cause an `Unsupported Database: PostgreSQL` crash.
- **Redis Connection Exhaustion:** Booting Lettuce without `commons-pool2` blocks all threads under heavy load. We must explicitly define `spring.data.redis.lettuce.pool.*` configurations.
