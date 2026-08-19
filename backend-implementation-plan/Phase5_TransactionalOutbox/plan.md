# Phase 5: Transactional Outbox and Kafka Plan

## 1. Architectural Objective
Resolve the Dual-Write problem. Write events to `outbox_events` atomically during the review creation transaction. Use a background process to reliably forward these events to Kafka.

## 2. Kafka Configuration
- Define `domain.events.reviews` topic.
- Producer properties: `acks=all`, `enable.idempotence=true`.
- Key/Value Serializers: `StringSerializer`.

## 3. Outbox Relay
- Class: `OutboxRelayService` marked `@Component`.
- Method: `@Scheduled(fixedDelay = 5000) @Transactional void processOutboxMessages()`.
- Logic: Fetch up to 100 unprocessed events, use `kafkaTemplate.send().get()` (synchronous inside the async worker), then invoke `event.markProcessed()`.

## 4. Edge Cases & Resilience Scenarios
- **Duplicate Message Publishing (At-Least-Once):** If the relay service crashes *after* publishing the message to Kafka but *before* the database commits `markProcessed()`, the event will be re-published upon restart. Downstream consumers **must** implement strict idempotency to handle identical, duplicate messages.
- **Kafka Broker Unavailability:** If the broker is unreachable, `.get()` will throw an `ExecutionException` or timeout. The relay must catch this, cleanly abort the current polling batch without crashing the JVM, and retry naturally on the next schedule tick.
- **Database Thread Starvation:** The relay must process events in small chunks (e.g., `findTop100`). Trying to load thousands of unprocessed events simultaneously will lock tables and cause OutOfMemory errors.
- **Outbox Bloat:** Events marked as `processed = true` accumulate forever. We require a separate cron job to aggressively prune (DELETE) historical events older than X days.
