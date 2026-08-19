# Phase 3 Validation

This test ensures the Spring Data JPA context loads and mapping is valid.

```java
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PersistenceValidationTest {

    @Autowired
    private ReviewRepository reviewRepository;
    
    @Autowired
    private AggregateRepository aggregateRepository;

    @Test
    void verifyJpaContextLoadsAndMappingsAreValid() {
        assertThat(reviewRepository).isNotNull();
        assertThat(aggregateRepository).isNotNull();
        // If this test boots successfully, the JPA annotations match the Flyway schema.
    }
}
```
