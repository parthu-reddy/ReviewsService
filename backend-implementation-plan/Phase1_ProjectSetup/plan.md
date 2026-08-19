# Phase 1: Project Setup and Architecture Plan

## 1. Architectural Objective
Bootstrap a Spring Boot 3 Java 17+ microservice designed for high concurrency and DDD boundary isolation, tailored to handle generalized entity reviews. Establish robust observability and distributed tracing from day one.

## 2. Core Dependencies
- **Spring Web** (REST API)
- **Spring Data JPA & PostgreSQL Driver** (Persistence)
- **Spring Data Redis & Lettuce** (CQRS read caching)
- **Spring Kafka** (Outbox event publishing)
- **Spring Cloud OpenFeign** (External API validation)
- **Resilience4j** (Circuit Breakers)
- **Bucket4j** (Rate Limiting)
- **Validation (Jakarta)** (Input enforcement)
- **Flyway** (Database migrations)
- **Micrometer Tracing & Actuator** (`spring-boot-starter-actuator`, `micrometer-tracing-bridge-otel`, `micrometer-tracing-reporter-otlp`) for distributed observability.

## 3. Package Layout (Domain-Driven)
- `com.enterprise.reviewservice.config`: Global configurations
- `com.enterprise.reviewservice.domain`: Entities, custom exceptions, domain events
- `com.enterprise.reviewservice.infrastructure`: Repositories, Kafka relays, Feign clients
- `com.enterprise.reviewservice.web`: Controllers, DTOs, custom validators, @RestControllerAdvice

## 4. Deployment, Observability, and Environment Configuration
- **Profile:** Only deploy as the `Dev` profile for all microservices unless explicitly requested otherwise.
- **Infrastructure:** Only use Oracle for deployment.
- **Tracing Configuration:** Set `management.tracing.enabled=true`. In Dev profile, set `management.tracing.sampling.probability=1.0`. Enforce MDC correlation in log patterns (`%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]`).

## 5. Edge Cases & Resilience Scenarios
- **Dependency Conflicts:** Misaligned Spring Cloud BOMs can cause OpenFeign/Resilience4j to fail. Ensure strict version alignment with Spring Boot 3.
- **Tomcat Thread Exhaustion:** Default Tomcat settings can easily be exhausted if external calls (like Feign) hang. We must configure aggressive timeouts natively on HTTP clients.
- **Environment Parity & Database Collisions:** The `dev` profile must point to isolated databases (e.g., via Testcontainers) to prevent multiple engineers from polluting shared development tables.
- **Fail Fast Policy (Financial/Core Rules):** If any startup validations fail, the application context must fail fast and not attempt to fallback to default states.
