# Phase 1 Checklist

- [ ] Initialize Spring Boot 3 project via Initializr.
- [ ] Configure `build.gradle` / `pom.xml` with dependencies listed in `plan.md` (Including Micrometer OTEL & Actuator).
- [ ] Create the four top-level packages: `config`, `domain`, `infrastructure`, `web`.
- [ ] Create `application.yml` and `application-dev.yml` (Set `Dev` as the active profile).
- [ ] Set up HikariCP configuration for PostgreSQL inside the properties file.
- [ ] Configure tracing via `management.tracing.enabled=true` and sampling probability.
- [ ] Update `logging.pattern.level` in application properties to inject `traceId` and `spanId` (MDC).
- [ ] Document Oracle as the exclusive deployment target in project README.
- [ ] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
