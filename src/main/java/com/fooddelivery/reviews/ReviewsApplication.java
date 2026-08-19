package com.fooddelivery.reviews;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableFeignClients(basePackages = {"com.fooddelivery.common.client"})
@EntityScan(basePackages = {"com.fooddelivery.reviews", "com.fooddelivery.common"})
@EnableJpaRepositories(basePackages = {"com.fooddelivery.reviews", "com.fooddelivery.common"})
public class ReviewsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewsApplication.class, args);
    }
}
