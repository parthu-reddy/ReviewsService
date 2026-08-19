# Phase 1 Checklist

- [ ] Initialize Spring Boot 3 project via Initializr.
- [ ] Add `common-library` and all dependencies to `pom.xml` (Ensure `flyway-database-postgresql` and `commons-pool2` are explicitly included).
- [ ] Add `maven-surefire-plugin` with `<argLine>-XX:+EnableDynamicAgentLoading -Xshare:off -Dnet.bytebuddy.experimental=true</argLine>`.
- [ ] Create main class and annotate with `@EntityScan` and `@EnableJpaRepositories` referencing BOTH `com.fooddelivery.reviews` and `com.fooddelivery.common`.
- [ ] Ensure `scanBasePackages` does not overlap `com.fooddelivery` and `com.fooddelivery.common`.
- [ ] Create `application.yml` and configure `spring.config.import` for the Spring Cloud Config Server.
- [ ] Configure `spring.data.redis.lettuce.pool.max-active` (and related pooling properties) in `application.yml`.
- [ ] Create `application-contract-test.yml` in `src/test/resources` excluding Redis auto-configurations.
- [ ] Configure tracing via `management.tracing.enabled=true`, OTLP endpoint, and MDC logging pattern.
- [ ] Document Oracle as the exclusive deployment target in project README.
- [ ] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
