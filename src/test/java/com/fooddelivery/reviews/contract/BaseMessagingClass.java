package com.fooddelivery.reviews.contract;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.verifier.messaging.boot.AutoConfigureMessageVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Producer side of the {@code review-events} contract.
 *
 * <p>Added 2026-09-12. The review payload keys already live in {@code EventPayloadConstants}, shared
 * by this producer and RestaurantApplication's consumer, so a renamed key is a compile error in both
 * modules — a stronger and earlier guarantee than a contract gives. What that does <em>not</em>
 * cover is the rest of the envelope: the topic the event lands on, the {@code eventType} header
 * {@code OutboxProcessor} stamps, and the JSON types the consumer parses. A contract covers those,
 * which is why this exists alongside the constants rather than instead of them.
 */
@SpringBootTest(classes = BaseMessagingClass.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
            "spring.kafka.consumer.auto-offset-reset=earliest"
        })
@org.springframework.test.context.ActiveProfiles("contract-test")
@AutoConfigureMessageVerifier
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
@EmbeddedKafka(adminTimeout = 60, partitions = 1, topics = {"review-events"})
public abstract class BaseMessagingClass {

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers",
                () -> System.getProperty("spring.embedded.kafka.brokers", "localhost:9092"));
    }

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class TestConfig {
        @Bean
        public KafkaMessageVerifier kafkaMessageVerifier() {
            return new KafkaMessageVerifier();
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * REVIEW_CREATED, exactly as {@code ReviewCommandService.writeOutboxEvent} builds it.
     *
     * <p>Keyed on {@code EventPayloadConstants} rather than string literals, for the same reason the
     * producer is: if a key is renamed, this stops compiling instead of silently drifting alongside
     * the contract and leaving both green.
     *
     * <p>{@code totalReviews} and {@code averageRating} are the aggregate's state <em>after</em> the
     * write, not a delta — that is what lets RestaurantApplication assign them verbatim and stay
     * idempotent under redelivery.
     */
    public void fireReviewCreated() throws Exception {
        String entityId = "9c8b7a65-1e2d-4f30-b5a6-7c8d9e0f1a23";
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put(com.fooddelivery.common.constants.EventPayloadConstants.REVIEW_ID,
                "3f2504e0-4f89-41d3-9a0c-0305e82c3301");
        payload.put(com.fooddelivery.common.constants.EventPayloadConstants.ORDER_ID,
                "6b1d3c22-9f45-4a7e-8c11-2d4e6f8a9b02");
        payload.put(com.fooddelivery.common.constants.EventPayloadConstants.ENTITY_TYPE, "RESTAURANT");
        payload.put(com.fooddelivery.common.constants.EventPayloadConstants.ENTITY_ID, entityId);
        payload.put(com.fooddelivery.common.constants.EventPayloadConstants.USER_ID,
                "7a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9");
        payload.put(com.fooddelivery.common.constants.EventPayloadConstants.RATING, 5);
        payload.put(com.fooddelivery.common.constants.EventPayloadConstants.TOTAL_REVIEWS, 7L);
        payload.put(com.fooddelivery.common.constants.EventPayloadConstants.AVERAGE_RATING, "4.29");
        payload.put(com.fooddelivery.common.constants.EventPayloadConstants.TIMESTAMP,
                "2026-09-11T10:15:30Z");

        publishViaOutbox(com.fooddelivery.common.constants.AggregateType.REVIEW, entityId,
                com.fooddelivery.common.constants.EventType.REVIEW_CREATED, payload);
    }

    /**
     * Publishes through the real {@link com.fooddelivery.common.outbox.service.OutboxProcessor},
     * which is what decides the topic from the aggregate type and stamps the eventType header. Only
     * the repository is mocked; persistence is not part of the contract.
     */
    protected void publishViaOutbox(com.fooddelivery.common.constants.AggregateType aggregateType,
                                    String aggregateId,
                                    com.fooddelivery.common.constants.EventType eventType,
                                    Object payloadObject) throws Exception {
        com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent =
                com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(java.util.UUID.randomUUID())
                        .aggregateType(aggregateType)
                        .aggregateId(aggregateId)
                        .eventType(eventType)
                        .payload(payloadObject instanceof String
                                ? (String) payloadObject
                                : objectMapper.writeValueAsString(payloadObject))
                        .createdAt(java.time.LocalDateTime.now())
                        .build();

        com.fooddelivery.common.outbox.repository.OutboxEventRepository repo =
                org.mockito.Mockito.mock(com.fooddelivery.common.outbox.repository.OutboxEventRepository.class);
        org.mockito.Mockito.when(repo.findTop100ByStatusInOrderByCreatedAtAsc(
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new java.util.ArrayList<>(java.util.List.of(outboxEvent)));
        new com.fooddelivery.common.outbox.service.OutboxProcessor(
                repo, kafkaTemplate, new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
                .processOutboxEvents();
    }
}
