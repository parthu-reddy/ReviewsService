# Phase 6 Validation

Verify the Feign Client against the actual upstream Provider Contract using Spring Cloud Contract.

```java
package com.enterprise.reviewservice.contract;

import com.enterprise.reviewservice.infrastructure.client.RestaurantServiceClient;
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
        ids = {"com.fooddelivery:restaurantservice:+:stubs:8090"}
)
@ActiveProfiles("contract-test")
class RestaurantContractConsumerTest {

    @Autowired
    private RestaurantServiceClient restaurantServiceClient;

    @Test
    void testValidateRestaurantContract() {
        // Consumer-Driven Contract Test ensuring Feign matches the provider
        Map<String, Object> response = restaurantServiceClient.validateEntityExists("123");
        assertThat(response).isNotNull();
    }
}
```
