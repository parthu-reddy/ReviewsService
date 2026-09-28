-- Reviews remain immutable, but each order participant may independently review the same target.
-- This migration is additive and leaves the previously applied migrations untouched.

ALTER TABLE reviews ADD COLUMN author_role VARCHAR(32);
ALTER TABLE reviews ADD COLUMN visibility VARCHAR(16);

-- All reviews written by the previous API were customer-authored. Restaurant and product reviews
-- were public; driver reviews were already restricted to the driver and administrators.
UPDATE reviews
SET author_role = 'CUSTOMER',
    visibility = CASE WHEN entity_type = 'DRIVER' THEN 'PRIVATE' ELSE 'PUBLIC' END
WHERE author_role IS NULL OR visibility IS NULL;

ALTER TABLE reviews ALTER COLUMN author_role SET NOT NULL;
ALTER TABLE reviews ALTER COLUMN visibility SET NOT NULL;

ALTER TABLE reviews
    ADD CONSTRAINT chk_reviews_author_role
        CHECK (author_role IN ('CUSTOMER', 'RESTAURANT', 'DELIVERY')),
    ADD CONSTRAINT chk_reviews_visibility
        CHECK (visibility IN ('PUBLIC', 'PRIVATE'));

-- CUSTOMER is a new entity_type partition. It has not been accepted by the old application, so no
-- rows for it can be present in reviews_default when this migration runs.
CREATE TABLE reviews_customer
    PARTITION OF reviews FOR VALUES IN ('CUSTOMER') PARTITION BY HASH (entity_id);
CREATE TABLE reviews_customer_0 PARTITION OF reviews_customer FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE reviews_customer_1 PARTITION OF reviews_customer FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE reviews_customer_2 PARTITION OF reviews_customer FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE reviews_customer_3 PARTITION OF reviews_customer FOR VALUES WITH (MODULUS 4, REMAINDER 3);

-- The old constraint allowed only one review per target/order, no matter who authored it. Preserve
-- immutability per participant while allowing other order participants their own review.
DROP INDEX IF EXISTS uq_reviews_entity_order;
CREATE UNIQUE INDEX uq_reviews_entity_order_author
    ON reviews (entity_type, entity_id, order_id, user_id);
