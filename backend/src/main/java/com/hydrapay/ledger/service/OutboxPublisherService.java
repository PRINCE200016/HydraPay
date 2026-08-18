package com.hydrapay.ledger.service;

import com.hydrapay.ledger.domain.entity.OutboxEvent;
import com.hydrapay.ledger.domain.enums.OutboxStatus;
import com.hydrapay.ledger.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${hydrapay.outbox.batch-size:500}")
    private int batchSize = 500;

    @Value("${hydrapay.outbox.max-retries:5}")
    private int maxRetries = 5;

    private static final String KAFKA_TOPIC_LEDGER = "ledger-events";

    @Scheduled(fixedDelayString = "${hydrapay.outbox.polling-rate-ms:500}")
    public void publishPendingOutboxEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING, PageRequest.of(0, batchSize));

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Outbox Worker polling found {} pending event(s) to publish (batch size: {})", pendingEvents.size(), batchSize);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (OutboxEvent event : pendingEvents) {
            try {
                CompletableFuture<Void> future = kafkaTemplate.send(KAFKA_TOPIC_LEDGER, event.getAggregateId(), event.getPayload())
                        .thenAccept(result -> {
                            event.setStatus(OutboxStatus.PUBLISHED);
                            event.setPublishedAt(OffsetDateTime.now());
                            log.debug("Kafka Outbox PUBLISHED: eventId={}, aggregateId={}", event.getId(), event.getAggregateId());
                        })
                        .exceptionally(ex -> {
                            int nextRetry = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
                            event.setRetryCount(nextRetry);
                            if (nextRetry >= maxRetries) {
                                event.setStatus(OutboxStatus.FAILED);
                                log.error("Kafka Outbox PERMANENT FAILURE: eventId={}, max retries reached", event.getId(), ex);
                            } else {
                                event.setStatus(OutboxStatus.PENDING);
                                log.warn("Kafka Outbox PUBLISH RETRY (attempt {}/{}): eventId={}", nextRetry, maxRetries, event.getId(), ex);
                            }
                            return null;
                        });
                futures.add(future);
            } catch (Exception e) {
                log.error("Outbox dispatch exception prior to async send for eventId: {}", event.getId(), e);
                int nextRetry = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
                event.setRetryCount(nextRetry);
                if (nextRetry >= maxRetries) {
                    event.setStatus(OutboxStatus.FAILED);
                }
            }
        }

        if (!futures.isEmpty()) {
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Timed out or interrupted waiting for outbox batch Kafka sends to complete: {}", e.getMessage());
            }
        }

        try {
            outboxEventRepository.saveAll(pendingEvents);
            log.info("Outbox batch completed: updated status for {} event(s)", pendingEvents.size());
        } catch (Exception e) {
            log.error("Failed to batch save outbox events state to database", e);
        }
    }
}

