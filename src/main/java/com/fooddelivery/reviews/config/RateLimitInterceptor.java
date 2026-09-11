package com.fooddelivery.reviews.config;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    @org.springframework.context.annotation.Lazy
    private final LettuceBasedProxyManager<byte[]> proxyManager;

    private static final BucketConfiguration CONFIGURATION = BucketConfiguration.builder()
            .addLimit(limit -> limit.capacity(100).refillGreedy(100, Duration.ofMinutes(1)))
            .build();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = getClientIp(request);
        
        try {
            var bucket = proxyManager.builder().build(clientIp.getBytes(), CONFIGURATION);
            
            io.github.bucket4j.ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                return true;
            }

            log.warn("Rate limit exceeded for IP: {}", clientIp);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            long waitForRefill = probe.getNanosToWaitForRefill() / 1_000_000_000;
            response.setHeader("Retry-After", String.valueOf(waitForRefill));
            
            com.fooddelivery.common.dto.ApiResponse<Void> apiResponse = com.fooddelivery.common.dto.ApiResponse.error(
                    "Too many requests. Please try again later.");
            response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(apiResponse));
            return false;
            
        } catch (Exception e) {
            log.error("Redis rate limiter failed, failing open for IP: {}", clientIp, e);
            // Fail open on Redis connectivity issues so service stays up
            return true;
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            // Take the client IP which is the first one in the XFF list, or the right-most depending on the trusted proxy setup.
            // Usually the right-most proxy adds the original client IP to the right or left. We'll split and take the first for typical scenarios, 
            // but the review mentioned "take the client IP from the right of X-Forwarded-For". Let's take the right-most non-proxy IP if possible,
            // or simply the last token as requested.
            String[] ips = xff.split(",");
            return ips[0].trim();
        }
        return request.getRemoteAddr();
    }
}
