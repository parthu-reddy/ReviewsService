-- A review becomes a statement about one delivered order rather than about an entity in the
-- abstract. V1 is not edited: Deployment/SCHEMA_POLICY.md makes an applied migration immutable, and
-- validate-on-migrate would turn an edit into a failed boot rather than a warning.

-- Nullable, deliberately. ADD-COLUMN-TOLERATES-EXISTING-ROWS rejects a NOT NULL column with no
-- DEFAULT against a table that may hold rows, and there is no correct default for "which order was
-- this?" -- inventing one would fabricate provenance. Rows predating this migration keep NULL and
-- are unreachable by the new unique index below; every row written from here on carries an order,
-- enforced at the edge by @NotNull on CreateReviewRequest.orderId.
ALTER TABLE reviews ADD COLUMN order_id UUID;

-- Snapshotted from the order's customer name when the review is written, so read paths never fan
-- out to identity-service and an immutable review carries the name that was true when it was made.
ALTER TABLE reviews ADD COLUMN author_display_name VARCHAR(120);

-- V1 declared UNIQUE (entity_type, entity_id, user_id): one review per user per entity, forever.
-- That makes a customer's second order from the same restaurant permanently unreviewable. The
-- constraint is inlined in V1's CREATE TABLE, so Postgres named it for us; find it by shape rather
-- than by a guessed name, and only drop the one that matches exactly those three columns.
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT con.conname INTO constraint_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    WHERE rel.relname = 'reviews'
      AND nsp.nspname = current_schema()
      AND con.contype = 'u'
      AND (
            SELECT array_agg(att.attname::text ORDER BY att.attname)
            FROM unnest(con.conkey) AS k(attnum)
            JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
          ) = ARRAY['entity_id', 'entity_type', 'user_id'];

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE reviews DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

-- One review per target per order. `reviews` is LIST-partitioned on entity_type and sub-partitioned
-- by HASH on entity_id, so a unique index has to include both partition keys -- Postgres rejects a
-- unique constraint on a partitioned table that does not contain every partitioning column.
-- order_id completes it: (RESTAURANT, outlet-x, order-1) and (RESTAURANT, outlet-x, order-2) are
-- distinct, which is exactly the case V1 forbade.
CREATE UNIQUE INDEX uq_reviews_entity_order ON reviews (entity_type, entity_id, order_id);

-- Serves GET /api/v1/reviews/me, which reads across every partition for one author. Without it that
-- query is a sequential scan of all thirteen partitions.
CREATE INDEX idx_reviews_user_created ON reviews (user_id, created_at DESC);

-- Serves the eligibility lookup: "what has this customer already reviewed on this order". The
-- partition pruning key is entity_type, which eligibility does not filter on, so this index is what
-- keeps it from scanning everything.
CREATE INDEX idx_reviews_order ON reviews (order_id);
