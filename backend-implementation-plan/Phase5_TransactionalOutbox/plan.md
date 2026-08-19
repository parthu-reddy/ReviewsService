# Phase 5: Transactional Outbox and Kafka Plan

## 1. Architectural Objective
Resolve the Dual-Write problem. Write events to an outbox atomically during the review creation transaction. Use a background process to reliably forward these events to Kafka.

## 2. Shared Library Entities (Do NOT Duplicate)
- Use `com.fooddelivery.common.outbox.entity.OutboxEvent` and `com.fooddelivery.common.idempotency.entity.IdempotencyKey` from the `common-library`. 
- **CRITICAL:** Do NOT create localized copies of these entities or tables. This breaks the single source of truth and crashes Hibernate.

## 3. Kafka Configuration
- Define `domain.events.reviews` topic.
- Producer properties: `acks=all`, `enable.idempotence=true`.
- Key/Value Serializers: `StringSerializer`.
- **Observability:** Explicitly enable Kafka Micrometer observation in `application.yml` (`spring.kafka.template.observation-enabled=true`).

## 4. Outbox Relay
- Class: `OutboxRelayService` marked `@Component`.
- Method: `@Scheduled(fixedDelay = 5000) @Transactional void processOutboxMessages()`.
- Logic: Fetch up to 100 unprocessed events, use `kafkaTemplate.send(topic, partitionKey, payload).get()`, then invoke `event.markProcessed()`.
- **Partition Key:** You MUST pass a specific entity ID (e.g. `reviewId`) as the Kafka message key to preserve chronological partition ordering.

## 5. DLQ and Manual Intervention
- Create `AdminDlqController` to expose a REST endpoint for manually re-publishing dead-lettered payloads from the DLQ back to the primary topic for re-processing.

## 6. Edge Cases & Resilience Scenarios
- **Duplicate Bean Mappings:** Creating duplicate `OutboxEvent` entities locally causes `DuplicateMappingException`.
- **Kafka Broker Unavailability:** If the broker is unreachable, `.get()` will throw an `ExecutionException`. Catch this, gracefully abort the batch, and let the scheduler retry naturally.
- **Idempotency Hashing Bug:** When deduplicating payloads (if consuming events in this service), you MUST use `objectMapper.writeValueAsString(payload)` before hashing. Relying on `payload.toString()` hashes memory addresses, completely breaking idempotency.
- **Missing DLQ / DLT:** Any Kafka consumer MUST have an accompanying `@DltHandler` method; otherwise, terminal failures are silently dropped.
- **Volatile Idempotency Storage:** Idempotency keys must be stored in the DB, not Redis. Redis eviction causes duplicate processing.
