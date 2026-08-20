package com.fooddelivery.reviews;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.kafka.test.context.EmbeddedKafka;

import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, brokerProperties = { "listeners=PLAINTEXT://localhost:9092", "port=9092" })
class ReviewsApplicationTests {

    @MockBean
    private LettuceBasedProxyManager lettuceBasedProxyManager;

    @MockBean
    private LettuceConnectionFactory lettuceConnectionFactory;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void contextLoads() {
    }

}
