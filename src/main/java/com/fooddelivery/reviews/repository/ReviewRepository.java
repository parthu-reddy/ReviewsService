package com.fooddelivery.reviews.repository;

import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.reviews.entity.Review;
import com.fooddelivery.reviews.entity.ReviewId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, ReviewId> {

    /** Served by {@code idx_reviews_entity_created}. */
    Page<Review> findByEntityTypeAndEntityId(EntityType entityType, String entityId, Pageable pageable);

    /**
     * Everything already reviewed on one order — at most one outlet, one driver and the order's
     * items, so this is a handful of rows. Served by {@code idx_reviews_order}, which exists
     * because the table is partitioned on {@code entity_type} and this query does not filter on it.
     */
    List<Review> findByOrderId(UUID orderId);

    /** The author's own reviews, newest first. Served by {@code idx_reviews_user_created}. */
    Page<Review> findByUserId(String userId, Pageable pageable);
}
