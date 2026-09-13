package com.fooddelivery.reviews.config;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.HeaderConstants;
import com.fooddelivery.common.dto.ApiResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Per-principal rate limiting.
 *
 * <p>Keyed on the authenticated user, not on {@code X-Forwarded-For}. That header is set by the
 * client; the gateway appends to it rather than replacing it, so keying on its first element meant
 * a caller could pick a fresh bucket per request by writing a different value. Behind the gateway
 * the identity is already established — {@code GlobalJwtAuthFilter} strips inbound {@code X-User-*}
 * headers and re-stamps them from a verified JWT — so it is both cheaper and honest to use it.
 *
 * <p>The remote address remains the key for the rare unauthenticated request, which is the only
 * case where there is nothing better.
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final ProxyManager<byte[]> proxyManager;
    private final ObjectMapper objectMapper;
    private final BucketConfiguration writeConfiguration;
    private final BucketConfiguration readConfiguration;

    public RateLimitInterceptor(@Lazy ProxyManager<byte[]> proxyManager,
                                ObjectMapper objectMapper,
                                ReviewProperties properties) {
        this.proxyManager = proxyManager;
        this.objectMapper = objectMapper;
        // Writes are far scarcer than reads: a customer submits reviews for an order once, while a
        // single restaurant page issues several aggregate and list reads. One bucket for both would
        // either throttle browsing or leave review spam unbounded.
        this.writeConfiguration = BucketConfiguration.builder()
                .addLimit(limit -> limit
                        .capacity(properties.getWriteRateLimitPerMinute())
                        .refillGreedy(properties.getWriteRateLimitPerMinute(), Duration.ofMinutes(1)))
                .build();
        this.readConfiguration = BucketConfiguration.builder()
                .addLimit(limit -> limit
                        .capacity(properties.getReadRateLimitPerMinute())
                        .refillGreedy(properties.getReadRateLimitPerMinute(), Duration.ofMinutes(1)))
                .build();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        boolean isWrite = !HttpMethod.GET.matches(request.getMethod());
        String principal = resolvePrincipal(request);
        String bucketKey = (isWrite ? "reviews:w:" : "reviews:r:") + principal;

        try {
            var bucket = proxyManager.builder()
                    .build(bucketKey.getBytes(StandardCharsets.UTF_8),
                            isWrite ? writeConfiguration : readConfiguration);

            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                return true;
            }

            log.warn("Rate limit exceeded for principal={} write={}", principal, isWrite);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.setHeader("Retry-After",
                    String.valueOf(probe.getNanosToWaitForRefill() / 1_000_000_000));
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.error("Too many requests. Please try again later.", "RATE_LIMITED")));
            return false;

        } catch (Exception e) {
            // Fail open on a Redis outage. A rate limiter that takes the service down with it has
            // converted a throttle into an availability dependency.
            log.error("Redis rate limiter failed, failing open for principal: {}", principal, e);
            return true;
        }
    }

    private String resolvePrincipal(HttpServletRequest request) {
        String userId = request.getHeader(HeaderConstants.HEADER_USER_ID);
        if (userId != null && !userId.isBlank()) {
            return "u:" + userId;
        }
        return "ip:" + request.getRemoteAddr();
    }
}
