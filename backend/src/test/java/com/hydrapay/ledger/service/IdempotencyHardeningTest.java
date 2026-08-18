package com.hydrapay.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydrapay.ledger.domain.entity.IdempotencyRecord;
import com.hydrapay.ledger.domain.enums.IdempotencyStatus;
import com.hydrapay.ledger.dto.TransferResponse;
import com.hydrapay.ledger.exception.IdempotencyConflictException;
import com.hydrapay.ledger.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyHardeningTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private IdempotencyRecordRepository idempotencyRepository;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private IdempotencyService idempotencyService;

    private String key;
    private String originalHash;
    private String differentHash;

    @BeforeEach
    void setUp() {
        key = "idk_hardened_100";
        originalHash = "hash_payload_1111";
        differentHash = "hash_payload_2222";
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Should detect conflict when idempotency key is reused with a different request payload")
    void testPayloadHashMismatchConflict() {
        IdempotencyRecord existingRecord = IdempotencyRecord.builder()
                .idempotencyKey(key)
                .requestHash(originalHash)
                .status(IdempotencyStatus.COMPLETED)
                .responsePayload("{\"status\":\"SETTLED\"}")
                .build();

        when(valueOperations.get(any())).thenReturn(null); // Cache miss
        when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(idempotencyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existingRecord));

        assertThrows(IdempotencyConflictException.class, () ->
                idempotencyService.checkOrAcquireLock(key, differentHash));
    }

    @Test
    @DisplayName("Should return cached response when idempotency key and payload hash match")
    void testMatchingKeyAndPayload() {
        TransferResponse mockResponse = TransferResponse.builder()
                .transactionId(UUID.randomUUID())
                .idempotencyKey(key)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .cachedResponse(false)
                .build();

        IdempotencyRecord existingRecord = IdempotencyRecord.builder()
                .idempotencyKey(key)
                .requestHash(originalHash)
                .status(IdempotencyStatus.COMPLETED)
                .responsePayload("{\"idempotencyKey\":\"" + key + "\",\"amount\":100.00,\"currency\":\"USD\"}")
                .build();

        when(valueOperations.get(any())).thenReturn(null);
        when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(idempotencyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existingRecord));

        Optional<TransferResponse> responseOpt = idempotencyService.checkOrAcquireLock(key, originalHash);

        assertTrue(responseOpt.isPresent());
        assertTrue(responseOpt.get().isCachedResponse());
        assertEquals(key, responseOpt.get().getIdempotencyKey());
    }
}
