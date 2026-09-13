package com.fooddelivery.reviews.contract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.test.context.ActiveProfiles;

import com.fooddelivery.common.client.CustomerServiceClient;
import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.common.dto.order.OrderReviewContextDto;
import com.fooddelivery.common.enums.DeliveryStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ReviewsService against CustomerApplication's published review-context contract.
 *
 * <p>This was the one integration point in the reviews path with no contract on either side, and it
 * is the one that decides whether a customer may review at all: {@code ReviewEligibilityService}
 * gates E2-E6 entirely on this response. A field renamed or retyped in CustomerApplication would
 * have surfaced as a customer being silently told they cannot review a delivered order.
 *
 * <p>{@code idsToServiceIds} maps the stub artifact to the Feign service name — CustomerApplication
 * publishes as {@code food-delivery-backend} while {@code CustomerServiceClient} resolves
 * {@code customer-service}, so without the mapping the client would not find the stub.
 *
 * <p>Asserting the fields, not just a 200: a stub returning an empty body would satisfy
 * {@code assertNotNull} while telling the eligibility rules nothing.
 */
@ActiveProfiles("contract-test")
@SpringBootTest(classes = ReviewsCustomerContractConsumerTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "stubrunner.idsToServiceIds.food-delivery-backend=customer-service")
@AutoConfigureStubRunner(ids = "com.fooddelivery:food-delivery-backend:+:stubs")
public class ReviewsCustomerContractConsumerTest {

    /** The order the published contract is written against. */
    private static final String CONTRACT_ORDER_ID = "6b1d3c22-9f45-4a7e-8c11-2d4e6f8a9b02";

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @org.springframework.cloud.openfeign.EnableFeignClients(
            basePackages = "com.fooddelivery.common.client")
    static class TestConfig {
    }

    @Autowired
    private CustomerServiceClient customerServiceClient;

    @Test
    public void reviewContextMatchesThePublishedContract() {
        ApiResponse<OrderReviewContextDto> body = customerServiceClient
                .getOrderReviewContext(CONTRACT_ORDER_ID, "reviews-service")
                .getBody();

        assertNotNull(body, "customer-service returned no body for an order the contract declares");
        OrderReviewContextDto context = body.getData();
        assertNotNull(context, "the envelope carried no data - eligibility has nothing to decide on");

        assertEquals(CONTRACT_ORDER_ID, context.getOrderId().toString());

        // E2: only the customer who placed the order may review it.
        assertNotNull(context.getCustomerId(), "without this, ownership cannot be checked");

        // Snapshotted into the review's author label at write time, never looked up on read.
        assertNotNull(context.getCustomerName(), "the author label would be blank on every review");

        // The two always-reviewable targets.
        assertNotNull(context.getRestaurantId());
        assertNotNull(context.getRestaurantName());

        // E3: completion is read from deliveryStatus, never from OrderStatus - that enum has no
        // DELIVERED value and HANDED_OVER is not "the food arrived".
        assertEquals(DeliveryStatus.DELIVERED, context.getDeliveryStatus());

        // E4: start of the review window. A null here closes the window on a delivered order.
        assertNotNull(context.getDeliveredAt(),
                "deliveredAt did not parse as a LocalDateTime - the producer's date format changed");

        // E5: a driver is reviewable only when one was assigned.
        assertNotNull(context.getDeliveryExecutiveId());

        // E6: the dishes. Without ids there is nothing product-level to review.
        assertNotNull(context.getItems());
        assertFalse(context.getItems().isEmpty(), "no items - no reviewable dishes");
        assertNotNull(context.getItems().get(0).getMenuItemId());
        assertNotNull(context.getItems().get(0).getName(),
                "the rating sheet labels each dish by this");
    }
}
