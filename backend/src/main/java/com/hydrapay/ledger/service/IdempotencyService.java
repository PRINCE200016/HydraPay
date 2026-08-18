package com.hydrapay.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydrapay.ledger.domain.entity.IdempotencyRecord;
import com.hydrapay.ledger.domain.enums.IdempotencyStatus;
import com.hydrapay.ledger.dto.TransferResponse;
import com.hydrapay.ledger.exception.IdempotencyConflictException;
import com.hydrapay.ledger.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    private static final String REDIS_LOCK_PREFIX = "idempotency:lock:";
    private static final String REDIS_CACHE_PREFIX = "idempotency:cache:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    /**
     * Attempts fast L1 Redis SETNX lock acquisition.
     * Fallback to PostgreSQL idempotency_records table for persistent ACID guarantee.
     * Returns cached response if request was previously processed.
     */
    public Optional<TransferResponse> checkOrAcquireLock(String idempotencyKey, String requestPayloadHash) {
        String lockKey = REDIS_LOCK_PREFIX + idempotencyKey;
        String cacheKey = REDIS_CACHE_PREFIX + idempotencyKey;

        // 1. Check L1 Redis Cache for existing settled response
        String cachedJson = redisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            try {
                log.info("Idempotency HIT [Redis Cache]: key={}", idempotencyKey);
                TransferResponse response = objectMapper.readValue(cachedJson, TransferResponse.class);
                response.setCachedResponse(true);
                return Optional.of(response);
            } catch (Exception e) {
                log.warn("Failed to deserialize Redis cached response for key: {}", idempotencyKey, e);
            }
        }

        // 2. Redis SETNX lock acquisition
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "PROCESSING", LOCK_TTL);
        if (Boolean.FALSE.equals(acquired)) {
            log.warn("Idempotency CONFLICT [Redis Lock Active]: key={}", idempotencyKey);
            throw new IdempotencyConflictException("Concurrent request in progress for idempotency key: " + idempotencyKey);
        }

        // 3. PostgreSQL Tier 2 Fallback Check
        Optional<IdempotencyRecord> dbRecordOpt = idempotencyRepository.findByIdempotencyKey(idempotencyKey);
        if (dbRecordOpt.isPresent()) {
            IdempotencyRecord dbRecord = dbRecordOpt.get();
            if (dbRecord.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                throw new IdempotencyConflictException("Transaction currently processing in DB for key: " + idempotencyKey);
            } else if (dbRecord.getStatus() == IdempotencyStatus.COMPLETED && dbRecord.getResponsePayload() != null) {
                try {
                    log.info("Idempotency HIT [PostgreSQL DB]: key={}", idempotencyKey);
                    TransferResponse response = objectMapper.readValue(dbRecord.getResponsePayload(), TransferResponse.class);
                    response.setCachedResponse(true);
                    return Optional.of(response);
                } catch (Exception e) {
                    log.error("Failed to parse DB idempotency response for key: {}", idempotencyKey, e);
                }
            }
        } else {
            // Save initial IN_PROGRESS record in PostgreSQL
            IdempotencyRecord record = IdempotencyRecord.builder()
                    .idempotencyKey(idempotencyKey)
                    .requestHash(requestPayloadHash)
                    .status(IdempotencyStatus.IN_PROGRESS)
                    .expiresAt(OffsetDateTime.now().plusHours(24))
                    .build();
            idempotencyRepository.save(record);
        }

        return Optional.empty();
    }

    /**
     * Stores completed transfer result into both Redis L1 cache and PostgreSQL DB.
     */
    public void saveCompletedResult(String idempotencyKey, TransferResponse response) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(response);

            // Update Redis Cache
            redisTemplate.opsForValue().set(REDIS_CACHE_PREFIX + idempotencyKey, jsonPayload, CACHE_TTL);
            redisTemplate.delete(REDIS_LOCK_PREFIX + idempotencyKey);

            // Update PostgreSQL DB
            idempotencyRepository.findByIdempotencyKey(idempotencyKey).ifPresent(record -> {
                record.setStatus(IdempotencyStatus.COMPLETED);
                record.setResponsePayload(jsonPayload);
                idempotencyRepository.save(record);
            });
            log.info("Idempotency RECORD STORED: key={}", idempotencyKey);
        } catch (Exception e) {
            log.error("Failed to save idempotency result for key: {}", idempotencyKey, e);
        }
    }

    public void releaseLock(String idempotencyKey) {
        redisTemplate.delete(REDIS_LOCK_PREFIX + idempotencyKey);
        idempotencyRepository.deleteById(idempotencyKey);
    }
}
