# Phase 2: PostgreSQL Schema and Partitioning Plan

## 1. Architectural Objective
Deploy a Composite Partitioned PostgreSQL schema that isolates domains by LIST (entity type) and load-balances I/O by HASH (entity ID), along with Optimistic Concurrency versioning.

## 2. Table Definitions

### `review_aggregates`
Contains `entity_type`, `entity_id`, `total_reviews`, `average_rating`, `version` (for OCC), `updated_at`. PK is `(entity_type, entity_id)`.

### `reviews` (Partitioned by LIST)
Contains `id`, `entity_type`, `entity_id`, `user_id`, `rating`, `comment`, `metadata` (JSONB), `created_at`.
- List Partitions: `reviews_restaurant`, `reviews_driver`, `reviews_product`.
- Hash Sub-partitions: Modulus 4 routing on `entity_id`.

### `outbox_events`
Contains `id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload` (JSONB), `processed` (boolean), `created_at`.

## 3. Indexing
- GIN index on `metadata`.
- Partial index on `outbox_events` where `processed = false`.

## 4. Edge Cases & Resilience Scenarios
- **Dynamic Partitioning Constraints:** If an upstream service requests a review for a new `entity_type` (e.g., "CUSTOMER") that lacks a LIST partition, PostgreSQL will throw a constraint error. Partitions must be provisioned ahead of time via Flyway migrations.
- **Hash Modulus Calculation:** Choosing too few modulus buckets leads to huge partition files; too many causes file descriptor bloat. The strategy dictates a moderate starting point (e.g., MODULUS 4 or 8) per LIST.
- **GIN Index Misses:** Misusing the JSONB column for highly relational data without explicit indexing will result in sequential scans, causing database CPU spikes under load.
- **Shared Buffers Ram Exhaustion:** B-Tree indexes on the primary partition must not exceed the `shared_buffers` RAM; partition pruning mitigates this heavily.
