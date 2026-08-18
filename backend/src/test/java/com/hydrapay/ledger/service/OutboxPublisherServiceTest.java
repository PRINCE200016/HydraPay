package com.hydrapay.ledger.service;

import com.hydrapay.ledger.domain.entity.OutboxEvent;
import com.hydrapay.ledger.domain.enums.OutboxStatus;
import com.hydrapay.ledger.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private OutboxPublisherService outboxPublisherService;

    private OutboxEvent event1;
    private OutboxEvent event2;

    @BeforeEach
    void setUp() {
        event1 = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("TRANSFER")
                .aggregateId("tx-1")
                .eventType("TRANSACTION_SETTLED")
                .payload("{\"amount\": 100.00}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();

        event2 = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("TRANSFER")
                .aggregateId("tx-2")
                .eventType("TRANSACTION_SETTLED")
                .payload("{\"amount\": 200.00}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }

    @Test
    @DisplayName("Should publish pending outbox events in batch asynchronously")
    void testBatchPublishSuccess() {
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any()))
                .thenReturn(List.of(event1, event2));
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisherService.publishPendingOutboxEvents();

        assertEquals(OutboxStatus.PUBLISHED, event1.getStatus());
        assertEquals(OutboxStatus.PUBLISHED, event2.getStatus());
        assertNotNull(event1.getPublishedAt());
        assertNotNull(event2.getPublishedAt());
        verify(outboxEventRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("Should handle partial failures in batch without skipping other events")
    void testPartialFailureHandling() {
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any()))
                .thenReturn(List.of(event1, event2));

        // event1 succeeds, event2 fails
        when(kafkaTemplate.send(eq("ledger-events"), eq("tx-1"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(eq("ledger-events"), eq("tx-2"), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka connection reset")));

        outboxPublisherService.publishPendingOutboxEvents();

        // event1 marked PUBLISHED
        assertEquals(OutboxStatus.PUBLISHED, event1.getStatus());

        // event2 retry count incremented and retained as PENDING for next cycle retry
        assertEquals(OutboxStatus.PENDING, event2.getStatus());
        assertEquals(1, event2.getRetryCount());

        verify(outboxEventRepository, times(1)).saveAll(any());
    }
}
