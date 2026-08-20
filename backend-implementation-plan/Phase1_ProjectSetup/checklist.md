# Phase 1 Checklist

- [x] Initialize Spring Boot 3 project via Initializr.
- [x] Add `common-library` and all dependencies to `pom.xml` (Ensure `flyway-database-postgresql` and `commons-pool2` are explicitly included).
- [x] Add `maven-surefire-plugin` with `<argLine>-XX:+EnableDynamicAgentLoading -Xshare:off -Dnet.bytebuddy.experimental=true</argLine>`.
- [x] Create main class and annotate with `@EntityScan` and `@EnableJpaRepositories` referencing BOTH `com.fooddelivery.reviews` and `com.fooddelivery.common`.
- [x] Ensure `scanBasePackages` does not overlap `com.fooddelivery` and `com.fooddelivery.common`.
- [x] Create `application.yml` and configure `spring.config.import` for the Spring Cloud Config Server.
- [x] Configure `spring.data.redis.lettuce.pool.max-active` (and related pooling properties) in `application.yml`.
- [x] Create `application-contract-test.yml` in `src/test/resources` excluding Redis auto-configurations.
- [x] Configure tracing via `management.tracing.enabled=true`, OTLP endpoint, and MDC logging pattern.
- [x] Document Oracle as the exclusive deployment target in project README.
- [x] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
