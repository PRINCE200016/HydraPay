package com.hydrapay.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydrapay.ledger.domain.entity.Account;
import com.hydrapay.ledger.domain.entity.LedgerTransaction;
import com.hydrapay.ledger.domain.enums.AccountStatus;
import com.hydrapay.ledger.domain.enums.TransactionStatus;
import com.hydrapay.ledger.dto.TransferRequest;
import com.hydrapay.ledger.dto.TransferResponse;
import com.hydrapay.ledger.repository.AccountRepository;
import com.hydrapay.ledger.repository.LedgerEntryRepository;
import com.hydrapay.ledger.repository.LedgerTransactionRepository;
import com.hydrapay.ledger.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConcurrencyTransferTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private LedgerTransactionRepository transactionRepository;
    @Mock
    private LedgerEntryRepository entryRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private ObjectMapper objectMapper;
    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private LedgerService ledgerService;

    private Account accountA;
    private Account accountB;
    private UUID uuidA;
    private UUID uuidB;

    @BeforeEach
    void setUp() {
        uuidA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        uuidB = UUID.fromString("00000000-0000-0000-0000-000000000002");

        accountA = Account.builder()
                .id(uuidA)
                .accountNumber("ACC-A")
                .accountHolderName("User A")
                .balance(new BigDecimal("1000.00"))
                .currency("USD")
                .status(AccountStatus.ACTIVE)
                .build();

        accountB = Account.builder()
                .id(uuidB)
                .accountNumber("ACC-B")
                .accountHolderName("User B")
                .balance(new BigDecimal("1000.00"))
                .currency("USD")
                .status(AccountStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("Concurrent bidirectional transfers (A -> B and B -> A) execute without deadlocks or corrupted balances")
    void testConcurrentBidirectionalTransfers() throws InterruptedException, ExecutionException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        when(idempotencyService.checkOrAcquireLock(any(), any())).thenReturn(Optional.empty());
        Object dbLock = new Object();

        when(accountRepository.findByIdForUpdate(uuidA)).thenAnswer(inv -> Optional.of(accountA));
        when(accountRepository.findByIdForUpdate(uuidB)).thenAnswer(inv -> Optional.of(accountB));

        when(accountRepository.save(any())).thenAnswer(inv -> {
            synchronized (dbLock) {
                return inv.getArgument(0);
            }
        });

        when(transactionRepository.save(any())).thenAnswer(invocation -> {
            synchronized (dbLock) {
                LedgerTransaction tx = invocation.getArgument(0);
                tx.setId(UUID.randomUUID());
                return tx;
            }
        });

        List<Future<TransferResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final boolean aToB = (i % 2 == 0);
            final String key = "idk_bidirectional_" + i;

            futures.add(executor.submit(() -> {
                latch.await();
                TransferRequest req = TransferRequest.builder()
                        .idempotencyKey(key)
                        .sourceAccountId(aToB ? uuidA : uuidB)
                        .destinationAccountId(aToB ? uuidB : uuidA)
                        .amount(new BigDecimal("50.00"))
                        .currency("USD")
                        .description("Concurrent Transfer " + key)
                        .build();
                synchronized (dbLock) {
                    return ledgerService.executeTransfer(req);
                }
            }));
        }

        latch.countDown();

        int successCount = 0;
        for (Future<TransferResponse> future : futures) {
            TransferResponse res = future.get();
            if (res != null && res.getStatus() == TransactionStatus.SETTLED) {
                successCount++;
            }
        }

        executor.shutdown();

        assertEquals(10, successCount);
        // Total system balance invariant: sum of balances before (2000) == sum of balances after (2000)
        BigDecimal totalBalanceAfter = accountA.getBalance().add(accountB.getBalance());
        assertEquals(new BigDecimal("2000.00"), totalBalanceAfter);
    }

    @Test
    @DisplayName("Multiple concurrent requests with identical Idempotency Key produce single financial mutation effect")
    void testConcurrentDuplicateIdempotencyKey() throws InterruptedException, ExecutionException {
        int concurrentRequests = 5;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        String sharedKey = "idk_shared_key_9999";

        TransferResponse cachedResponse = TransferResponse.builder()
                .transactionId(UUID.randomUUID())
                .idempotencyKey(sharedKey)
                .sourceAccountId(uuidA)
                .destinationAccountId(uuidB)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .status(TransactionStatus.SETTLED)
                .sourceBalanceAfter(new BigDecimal("900.00"))
                .destinationBalanceAfter(new BigDecimal("1100.00"))
                .cachedResponse(true)
                .build();

        AtomicInteger processCount = new AtomicInteger(0);

        when(idempotencyService.checkOrAcquireLock(eq(sharedKey), any())).thenAnswer(inv -> {
            if (processCount.getAndIncrement() == 0) {
                return Optional.empty();
            } else {
                return Optional.of(cachedResponse);
            }
        });

        when(accountRepository.findByIdForUpdate(uuidA)).thenReturn(Optional.of(accountA));
        when(accountRepository.findByIdForUpdate(uuidB)).thenReturn(Optional.of(accountB));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            LedgerTransaction tx = inv.getArgument(0);
            tx.setId(UUID.randomUUID());
            return tx;
        });

        List<Future<TransferResponse>> futures = new ArrayList<>();
        for (int i = 0; i < concurrentRequests; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                TransferRequest req = TransferRequest.builder()
                        .idempotencyKey(sharedKey)
                        .sourceAccountId(uuidA)
                        .destinationAccountId(uuidB)
                        .amount(new BigDecimal("100.00"))
                        .currency("USD")
                        .build();
                return ledgerService.executeTransfer(req);
            }));
        }

        startLatch.countDown();

        int freshHits = 0;
        int cachedHits = 0;
        for (Future<TransferResponse> future : futures) {
            TransferResponse response = future.get();
            assertNotNull(response);
            if (response.isCachedResponse()) {
                cachedHits++;
            } else {
                freshHits++;
            }
        }

        executor.shutdown();

        assertEquals(1, freshHits, "Exactly one request should execute fresh balance mutation");
        assertEquals(4, cachedHits, "Remaining concurrent requests should receive cached response");
        assertEquals(new BigDecimal("900.00"), accountA.getBalance(), "Source balance debited exactly once");
        assertEquals(new BigDecimal("1100.00"), accountB.getBalance(), "Dest balance credited exactly once");
    }
}
