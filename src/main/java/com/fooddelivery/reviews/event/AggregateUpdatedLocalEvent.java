package com.fooddelivery.reviews.event;

import com.fooddelivery.reviews.dto.ReviewAggregateDto;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class AggregateUpdatedLocalEvent extends ApplicationEvent {
    
    private final ReviewAggregateDto aggregateDto;

    public AggregateUpdatedLocalEvent(Object source, ReviewAggregateDto aggregateDto) {
        super(source);
        this.aggregateDto = aggregateDto;
    }
}
