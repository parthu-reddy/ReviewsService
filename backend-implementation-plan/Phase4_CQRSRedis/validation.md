# Phase 4 Validation

Verify the Redis integration programmatically.

```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisValidationTest {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void verifyRedisConnectionAndSerialization() {
        String key = "test:connection";
        redisTemplate.opsForValue().set(key, "SUCCESS");
        String value = (String) redisTemplate.opsForValue().get(key);
        
        assertThat(value).isEqualTo("SUCCESS");
        redisTemplate.delete(key);
    }
}
```
