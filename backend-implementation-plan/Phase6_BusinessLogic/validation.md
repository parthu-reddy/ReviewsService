# Phase 6 Validation

Verify the Shared Feign Client against the actual upstream Provider Contract using Spring Cloud Contract.

```java
package com.fooddelivery.reviews.contract;

import com.fooddelivery.common.client.RestaurantClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.test.context.ActiveProfiles;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureStubRunner(
        stubsMode = StubRunnerProperties.StubsMode.LOCAL,
        ids = {"com.fooddelivery:restaurant-application:+:stubs:8090"}
)
@ActiveProfiles("contract-test")
class RestaurantContractConsumerTest {

    @Autowired
    private RestaurantClient restaurantClient;

    @Test
    void testValidateRestaurantContract() {
        // Consumer-Driven Contract Test ensuring Feign matches the provider
        var response = restaurantClient.getRestaurantById("123");
        assertThat(response).isNotNull();
    }
}
```
