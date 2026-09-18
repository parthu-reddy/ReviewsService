package com.fooddelivery.reviews;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.kafka.test.context.EmbeddedKafka;

import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;

@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
@EmbeddedKafka(adminTimeout = 60, partitions = 1)
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


    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void outboxProcessorBeanExists() {
        assertTrue(applicationContext.containsBean("outboxProcessor"), "OutboxProcessor bean should be present");
    }
}
