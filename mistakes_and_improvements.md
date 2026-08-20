# Mistakes and Improvements

*This file tracks mistakes and improvements encountered during the implementation of the Reviews Service. Upon completion of the service, these lessons will be synced to `CommonMistakesDocumentation`.*

## Phase 7: Resilience & Security

1. **Bucket4j Lettuce Integration:**
   - **Improvement:** In Spring Boot 3 / Lettuce, obtaining the `RedisClient` from `LettuceConnectionFactory.getNativeClient()` directly is required for Bucket4j's `LettuceBasedProxyManager`. This natively integrates with the Redis cluster without adding overhead.

2. **Proxy/Load Balancer Rate Limiting:**
   - **Mistake Avoided:** Relying solely on `request.getRemoteAddr()` will block all legitimate users if the traffic is behind a reverse proxy/load balancer.
   - **Solution:** We explicitly parse the `X-Forwarded-For` header in `RateLimitInterceptor` to apply rate limits per actual client IP, falling back to `getRemoteAddr()` only if the header is absent.

3. **GlobalExceptionHandler Coverage:**
   - **Improvement:** Added `IllegalArgumentException` and `IllegalStateException` mapping to 400 Bad Request. These exceptions are frequently thrown by domain entities (like `ReviewAggregate`) and our fail-fast Feign fallbacks. Capturing them and mapping to 400 prevents generic 500 errors and clearly indicates client/input issues.
