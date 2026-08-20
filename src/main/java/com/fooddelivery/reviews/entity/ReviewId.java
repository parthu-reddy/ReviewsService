package com.fooddelivery.reviews.entity;

import com.fooddelivery.reviews.enums.EntityType;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.util.UUID;

/** Composite identifier for {@link Review}. Read-only once constructed. */
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ReviewId implements Serializable {

    private static final long serialVersionUID = 1L;

    private EntityType entityType;
    private String entityId;
    private UUID id;
}
