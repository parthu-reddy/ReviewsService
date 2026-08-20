package com.fooddelivery.reviews.repository;

import com.fooddelivery.reviews.enums.EntityType;
import com.fooddelivery.reviews.entity.Review;
import com.fooddelivery.reviews.entity.ReviewId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, ReviewId> {

    Page<Review> findByEntityTypeAndEntityId(EntityType entityType, String entityId, Pageable pageable);
}
