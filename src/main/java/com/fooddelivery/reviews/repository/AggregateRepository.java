package com.fooddelivery.reviews.repository;

import com.fooddelivery.reviews.entity.EntityKey;
import com.fooddelivery.reviews.entity.ReviewAggregate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AggregateRepository extends JpaRepository<ReviewAggregate, EntityKey> {
}
