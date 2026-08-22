package com.fooddelivery.reviews;

import com.fooddelivery.common.outbox.config.EnableOutbox;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableRetry
@EnableAsync
@EnableScheduling
@EnableOutbox
@SpringBootApplication(
    scanBasePackages = {"com.fooddelivery.reviews", "com.fooddelivery.common"}
)
@EnableFeignClients(basePackages = {"com.fooddelivery.common.client"})
@org.springframework.boot.autoconfigure.domain.EntityScan(basePackages = {"com.fooddelivery.reviews", "com.fooddelivery.common.entity"})
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages = {"com.fooddelivery.reviews", "com.fooddelivery.common.repository"})
public class ReviewsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewsApplication.class, args);
    }
}
