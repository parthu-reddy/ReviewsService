package com.fooddelivery.reviews.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock the review window is measured against.
 *
 * <p>Zoned to {@code platform.business-zone}, matching {@code CampaignService.ClockConfig},
 * {@code DaypartFilter} and {@code PacingEngineService}. That matters here because
 * {@code OrderReviewContextDto.deliveredAt} is a {@code LocalDateTime} — a wall-clock reading with
 * no offset of its own — so something has to decide which zone it was read in. Deciding UTC while
 * the deployment runs {@code PLATFORM_BUSINESS_ZONE=Asia/Kolkata} moves every review deadline by
 * five and a half hours.
 *
 * <p>Injected rather than called statically so the window is testable: {@code Instant.now()} inside
 * the service cannot be advanced, which means the expiry branch can only ever be exercised by
 * waiting fourteen days.
 */
@Configuration
public class ClockConfig {

    @Value("${platform.business-zone:UTC}")
    private String businessZone;

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of(businessZone));
    }
}
