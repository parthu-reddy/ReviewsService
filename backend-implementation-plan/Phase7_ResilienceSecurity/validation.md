# Phase 7 Validation

Verify the global exception handler mappings.

```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class ResilienceValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void verifyValidationExceptionHandlerReturns400() throws Exception {
        // Send a request with rating = 10 (which should trigger a Jakarta constraint violation)
        String badPayload = "{\"entityType\": \"RESTAURANT\", \"entityId\": \"123\", \"rating\": 10}";
        
        mockMvc.perform(post("/api/v1/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(badPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
```
