package com.fooddelivery.reviews.entity;

import com.fooddelivery.common.enums.ReviewEntityType;
import com.fooddelivery.common.enums.RoleName;
import com.fooddelivery.reviews.enums.ReviewVisibility;
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
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable participant's statement about an entity on one delivered order.
 *
 * <p><strong>Immutable by construction.</strong> Every mapped column is {@code updatable = false},
 * so even a detached-then-merged instance cannot rewrite a row, and there is no PUT, PATCH or
 * DELETE anywhere on the review surface. A second submission for the same
 * {@code (entityType, entityId, orderId, userId)} collides with
 * {@code uq_reviews_entity_order_author}; another participant can independently review the same
 * target on the same order.
 */
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
    private ReviewEntityType entityType;

    @Id
    @Column(name = "entity_id", nullable = false, updatable = false, length = 255)
    private String entityId;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The delivered order this review is about.
     *
     * <p>{@code nullable = false} states the application invariant and shapes the H2 schema the
     * tests generate. The Postgres column is deliberately looser: {@code SCHEMA_POLICY.md} forbids
     * adding a NOT NULL column with no default to a table that may already hold rows, so any row
     * predating this change keeps NULL. Nothing this service writes can: {@code @NotNull} on
     * {@code CreateReviewRequest.orderId} rejects it at the edge. Hibernate's {@code validate} does
     * not compare nullability, so the two do not conflict at boot.
     */
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false, updatable = false, length = 255)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", nullable = false, updatable = false, length = 32)
    private RoleName authorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, updatable = false, length = 16)
    private ReviewVisibility visibility;

    /**
     * How the author is shown publicly — "Priya R.". Snapshotted from the order at write time
     * rather than resolved on read: the review is immutable, so the name it carries is the name
     * that was true when it was written, and listing reviews never fans out to identity-service.
     */
    @Column(name = "author_display_name", updatable = false, length = 120)
    private String authorDisplayName;

    @Column(name = "rating", nullable = false, updatable = false)
    private int rating;

    @Column(name = "comment", updatable = false, length = 4000)
    private String comment;

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
