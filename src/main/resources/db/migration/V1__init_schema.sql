-- Aggregate table.
--
-- rating_sum is the source of truth for the average: recomputing the running total from a
-- rounded average_rating reintroduces up to half a scale-2 unit of error on every write, and
-- that error compounds. average_rating is a derived, display-only projection of rating_sum.
CREATE TABLE review_aggregates (
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    total_reviews BIGINT NOT NULL DEFAULT 0,
    rating_sum BIGINT NOT NULL DEFAULT 0,
    average_rating NUMERIC(3, 2) NOT NULL DEFAULT 0.00,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (entity_type, entity_id),
    CONSTRAINT chk_review_aggregates_totals CHECK (total_reviews >= 0 AND rating_sum >= 0)
);

CREATE TABLE reviews (
    id UUID NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    rating INT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment VARCHAR(4000),
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (entity_type, entity_id, id),
    UNIQUE (entity_type, entity_id, user_id)
) PARTITION BY LIST (entity_type);

-- Partitions by LIST
CREATE TABLE reviews_restaurant PARTITION OF reviews FOR VALUES IN ('RESTAURANT') PARTITION BY HASH (entity_id);
CREATE TABLE reviews_driver PARTITION OF reviews FOR VALUES IN ('DRIVER') PARTITION BY HASH (entity_id);
CREATE TABLE reviews_product PARTITION OF reviews FOR VALUES IN ('PRODUCT') PARTITION BY HASH (entity_id);

-- Catch-all so that adding a value to EntityType does not reject every insert for that type
-- until a migration lands. Rows landing here are a signal that a dedicated partition is due.
CREATE TABLE reviews_default PARTITION OF reviews DEFAULT;

-- Sub-partitions by HASH (Modulus 4)
CREATE TABLE reviews_restaurant_0 PARTITION OF reviews_restaurant FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE reviews_restaurant_1 PARTITION OF reviews_restaurant FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE reviews_restaurant_2 PARTITION OF reviews_restaurant FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE reviews_restaurant_3 PARTITION OF reviews_restaurant FOR VALUES WITH (MODULUS 4, REMAINDER 3);

CREATE TABLE reviews_driver_0 PARTITION OF reviews_driver FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE reviews_driver_1 PARTITION OF reviews_driver FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE reviews_driver_2 PARTITION OF reviews_driver FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE reviews_driver_3 PARTITION OF reviews_driver FOR VALUES WITH (MODULUS 4, REMAINDER 3);

CREATE TABLE reviews_product_0 PARTITION OF reviews_product FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE reviews_product_1 PARTITION OF reviews_product FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE reviews_product_2 PARTITION OF reviews_product FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE reviews_product_3 PARTITION OF reviews_product FOR VALUES WITH (MODULUS 4, REMAINDER 3);

-- Serves the only read query we have: filter on (entity_type, entity_id) ordered by created_at DESC.
-- Without it a hot entity sorts its whole partition on every page request.


-- NOTE: outbox_events is owned by common-library's db/migration/common migrations, which ship
-- inside the jar and are picked up by Flyway's default (recursive) classpath:db/migration scan.
-- Creating it here made V20260817000000__add_idempotency_key_to_outbox.sql fail on startup.


CREATE INDEX idx_reviews_entity_created ON reviews (entity_type, entity_id, created_at DESC, id);