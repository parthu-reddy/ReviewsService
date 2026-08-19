CREATE TABLE review_aggregates (
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    total_reviews INT NOT NULL DEFAULT 0,
    average_rating NUMERIC(3, 2) NOT NULL DEFAULT 0.00,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (entity_type, entity_id)
);

CREATE TABLE reviews (
    id UUID NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    rating INT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment TEXT,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (entity_type, entity_id, id)
) PARTITION BY LIST (entity_type);

-- Partitions by LIST
CREATE TABLE reviews_restaurant PARTITION OF reviews FOR VALUES IN ('RESTAURANT') PARTITION BY HASH (entity_id);
CREATE TABLE reviews_driver PARTITION OF reviews FOR VALUES IN ('DRIVER') PARTITION BY HASH (entity_id);
CREATE TABLE reviews_product PARTITION OF reviews FOR VALUES IN ('PRODUCT') PARTITION BY HASH (entity_id);

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

CREATE INDEX idx_reviews_metadata ON reviews USING GIN (metadata);
CREATE INDEX idx_reviews_entity_type_id ON reviews (entity_type, entity_id);

CREATE TABLE outbox_events (
    id UUID NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    status VARCHAR(50) NOT NULL DEFAULT 'UNPROCESSED',
    processed_at TIMESTAMP,
    error_message TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE INDEX idx_outbox_events_status ON outbox_events (created_at) WHERE status = 'UNPROCESSED';
