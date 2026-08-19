# Phase 4: CQRS and Redis Caching Plan

## 1. Architectural Objective
Implement CQRS. The write path (Command) mutates PostgreSQL and publishes a local event. The read path (Query) exclusively hits a Redis cache for aggregate ratings.

## 2. Redis Configuration
- Setup `LettuceConnectionFactory` for Sentinel or Cluster mode.
- Setup `RedisTemplate<String, Object>` configured with Jackson JSON serialization.

## 3. Asynchronous Cache Warming
- Create `AggregateUpdatedLocalEvent` which is published after a DB commit.
- Create `RedisCacheUpdater` service with an `@Async @EventListener`.
- Key format: `review_aggregate:{entity_type}:{entity_id}`.

## 4. Query Service
- Create `ReviewQueryService`.
- Attempt `redisTemplate.opsForValue().get(key)`.
- Cache miss: Fetch from DB, populate Redis asynchronously, and return.

## 5. Edge Cases & Resilience Scenarios
- **Cache Stampede (Thundering Herd):** If a highly requested aggregate key expires or is missing, thousands of concurrent requests might bypass Redis and hit PostgreSQL simultaneously. We must implement a distributed lock (e.g., using Redis `SETNX`) around the cache-miss logic so only one thread fetches from PostgreSQL and warms the cache.
- **Eventual Consistency Latency (Staleness):** Frontend clients will experience a sub-millisecond delay where their submitted review does not immediately reflect in the global Redis cache. Clients must be designed to tolerate this transient staleness.
- **Memory Exhaustion (Redis OOM):** Storing every aggregate forever will exhaust Redis memory. Keys must have a configured Time-To-Live (TTL), and Redis must be configured with an eviction policy like `allkeys-lru`.
