# Phase 4 Checklist

- [ ] Add Redis properties to `application.yml`.
- [ ] Create `RedisConfig` setting up `RedisTemplate` with `GenericJackson2JsonRedisSerializer`.
- [ ] Define the `AggregateUpdatedLocalEvent` class.
- [ ] Implement `RedisCacheUpdater` with `@Async` and `@EventListener(AggregateUpdatedLocalEvent.class)`.
- [ ] Implement `ReviewQueryService` for the read path with DB fallback logic.
- [ ] Create `mistakes_and_improvements.md` upon completion and sync lessons to `CommonMistakesDocumentation` (categorized correctly).
