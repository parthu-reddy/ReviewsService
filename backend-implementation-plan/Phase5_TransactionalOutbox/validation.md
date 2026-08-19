# Phase 5 Validation

Verify Outbox Event creation boundaries.

```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.transaction.TestTransaction;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OutboxValidationTest {

    @Autowired
    private ReviewService reviewService;
    
    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void testOutboxEventIsPersistedInTransaction() throws Exception {
        TestTransaction.start();
        // Invoke service method
        reviewService.createReview(new CreateReviewDto("RESTAURANT", "123", "usr_1", (short) 5, "Good", null));
        
        long count = outboxRepository.count();
        assertThat(count).isEqualTo(1);
        
        TestTransaction.flagForRollback();
        TestTransaction.end();
        
        // Assert rollback was successful
        TestTransaction.start();
        assertThat(outboxRepository.count()).isEqualTo(0);
        TestTransaction.end();
    }
}
```
