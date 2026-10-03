package com.fooddelivery.reviews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.dto.order.OrderReviewAuthorizationResult;
import com.fooddelivery.common.dto.order.OrderReviewContextDto;
import com.fooddelivery.common.enums.ReviewEntityType;
import com.fooddelivery.common.enums.RoleName;
import com.fooddelivery.common.outbox.entity.OutboxEventEntity;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.reviews.dto.CreateReviewRequest;
import com.fooddelivery.reviews.dto.ReviewEntryRequest;
import com.fooddelivery.reviews.event.AggregateUpdatedLocalEvent;
import com.fooddelivery.reviews.listener.RedisCacheUpdater;
import com.fooddelivery.reviews.repository.AggregateRepository;
import com.fooddelivery.reviews.repository.ReviewRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Spring transaction/events with JDBC commit visibility, rather than a mocked template. */
class ReviewCacheCommitTest {
    private static final String OUTLET = "66666666-6666-6666-6666-666666666666";
    private static final String USER = "88888888-8888-8888-8888-888888888888";
    private AnnotationConfigApplicationContext context;
    private ReviewCommandService service;
    private DataSource dataSource;
    private RedisTemplate<String, Object> redis;

    @BeforeEach @SuppressWarnings("unchecked")
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(ReviewCommandService.class);
        dataSource = context.getBean(DataSource.class);
        redis = context.getBean(RedisTemplate.class);
        var eligibility = context.getBean(ReviewEligibilityService.class);
        when(eligibility.getOrderContext(any())).thenReturn(OrderReviewContextDto.builder().customerName("Cache Fixture").build());
        when(eligibility.authorizeTargets(any(), any(), any(), any())).thenReturn(List.of(
                OrderReviewAuthorizationResult.builder().targetType(ReviewEntityType.RESTAURANT)
                        .targetId(OUTLET).allowed(true).build()));
        when(context.getBean(ReviewRepository.class).findByOrderIdAndUserId(any(), any())).thenReturn(List.of());
        when(context.getBean(AggregateRepository.class).findById(any())).thenReturn(Optional.empty());
        // The repository seam writes an auxiliary JDBC row inside the real submission transaction.
        when(context.getBean(OutboxEventRepository.class).save(any())).thenAnswer(invocation -> {
            OutboxEventEntity event = invocation.getArgument(0);
            new JdbcTemplate(dataSource).update("INSERT INTO commit_guard(id) VALUES (?)", event.getId().toString());
            return event;
        });
    }

    @AfterEach void close() { context.close(); }

    @Test void committedReviewEvictsItsCachedAggregateBeforeTheCommandReturns() {
        doAnswer(invocation -> {
            assertThat(committedRows(dataSource)).as("eviction happens only after JDBC commit").isEqualTo(1);
            return true;
        }).when(redis).delete(ReviewQueryService.getCacheKey(ReviewEntityType.RESTAURANT, OUTLET));

        assertThat(service.createReviews(request(), USER, RoleName.CUSTOMER)).hasSize(1);

        var observer = context.getBean(Observer.class);
        assertThat(observer.events).isEqualTo(1);
        assertThat(observer.publishedInsideTransaction).isTrue();
        assertThat(observer.rowsVisibleAtPublication).as("the publication registers before commit").isZero();
        verify(redis).delete(ReviewQueryService.getCacheKey(ReviewEntityType.RESTAURANT, OUTLET));
        assertThat(committedRows(dataSource)).isEqualTo(1);
    }

    @Test void enclosingRollbackNeverEvictsThePreviousCommittedAggregate() {
        context.getBean(TransactionTemplate.class).execute(status -> {
            assertThat(service.createReviews(request(), USER, RoleName.CUSTOMER)).hasSize(1);
            verify(redis, never()).delete(anyString());
            status.setRollbackOnly();
            return null;
        });
        assertThat(context.getBean(Observer.class).events).isEqualTo(1);
        verify(redis, never()).delete(anyString());
        assertThat(committedRows(dataSource)).isZero();
    }

    @Test void failedOutboxWriteRollsBackWithoutEvictingThePreviousAggregate() {
        doThrow(new IllegalStateException("Outbox unavailable")).when(context.getBean(OutboxEventRepository.class)).save(any());
        assertThatThrownBy(() -> service.createReviews(request(), USER, RoleName.CUSTOMER))
                .isInstanceOf(IllegalStateException.class);
        verify(redis, never()).delete(anyString());
        assertThat(context.getBean(Observer.class).events).isZero();
        assertThat(committedRows(dataSource)).isZero();
    }

    private static CreateReviewRequest request() {
        return CreateReviewRequest.builder().orderId(UUID.randomUUID()).entries(List.of(
                ReviewEntryRequest.builder().entityType(ReviewEntityType.RESTAURANT)
                        .entityId(OUTLET).rating(4).build())).build();
    }

    private static int committedRows(DataSource source) {
        // An independent connection cannot see an uncommitted transaction's row.
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM commit_guard")) {
            rows.next();
            return rows.getInt(1);
        } catch (java.sql.SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    static class Observer {
        private final DataSource source;
        int events;
        int rowsVisibleAtPublication;
        boolean publishedInsideTransaction;
        Observer(DataSource source) { this.source = source; }
        @EventListener public void onAggregate(AggregateUpdatedLocalEvent event) {
            events++;
            publishedInsideTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            rowsVisibleAtPublication = committedRows(source);
        }
    }

    @TestConfiguration(proxyBeanMethods = false) @EnableAsync @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() throws java.sql.SQLException {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:review_cache_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE commit_guard(id VARCHAR(36) PRIMARY KEY)");
            }
            return source;
        }
        @Bean TransactionTemplate transactions(DataSource source) { return new TransactionTemplate(new DataSourceTransactionManager(source)); }
        @Bean ReviewRepository reviews() { return mock(ReviewRepository.class); }
        @Bean AggregateRepository aggregates() { return mock(AggregateRepository.class); }
        @Bean OutboxEventRepository outbox() { return mock(OutboxEventRepository.class); }
        @Bean ReviewEligibilityService eligibility() { return mock(ReviewEligibilityService.class); }
        @Bean RedisTemplate<String, Object> redis() { return mock(RedisTemplate.class); }
        @Bean RedisCacheUpdater cacheUpdater(RedisTemplate<String, Object> redis) { return new RedisCacheUpdater(redis); }
        @Bean Observer observer(DataSource source) { return new Observer(source); }
        @Bean ReviewCommandService command(ReviewRepository reviews, AggregateRepository aggregates,
                OutboxEventRepository outbox, ReviewEligibilityService eligibility,
                ApplicationEventPublisher publisher, TransactionTemplate transactions) {
            return new ReviewCommandService(reviews, aggregates, outbox, eligibility,
                    new ObjectMapper(), publisher, transactions);
        }
    }
}
