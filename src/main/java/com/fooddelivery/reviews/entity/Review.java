package com.fooddelivery.reviews.entity;

import com.fooddelivery.reviews.enums.EntityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reviews")
@IdClass(ReviewId.class)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Review implements Persistable<ReviewId> {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, updatable = false, length = 50)
    private EntityType entityType;

    @Id
    @Column(name = "entity_id", nullable = false, updatable = false, length = 255)
    private String entityId;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false, length = 255)
    private String userId;

    @Column(name = "rating", nullable = false, updatable = false)
    private int rating;

    @Column(name = "comment", updatable = false, length = 4000)
    private String comment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", updatable = false)
    private String metadata; // Raw JSON, validated at the edge by @ValidJson

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    /** The review's own UUID, distinct from the composite {@link #getId()}. */
    public UUID getReviewId() {
        return id;
    }

    @Override
    public ReviewId getId() {
        return new ReviewId(entityType, entityId, id);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
