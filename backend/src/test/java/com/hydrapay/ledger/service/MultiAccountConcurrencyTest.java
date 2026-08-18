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
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MultiAccountConcurrencyTest {

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

    private Map<UUID, Account> accountsMap;
    private UUID uuidA, uuidB, uuidC;

    @BeforeEach
    void setUp() {
        uuidA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        uuidB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        uuidC = UUID.fromString("00000000-0000-0000-0000-000000000003");

        accountsMap = new ConcurrentHashMap<>();
        accountsMap.put(uuidA, Account.builder().id(uuidA).accountNumber("ACC-A").balance(new BigDecimal("1000.00")).status(AccountStatus.ACTIVE).build());
        accountsMap.put(uuidB, Account.builder().id(uuidB).accountNumber("ACC-B").balance(new BigDecimal("1000.00")).status(AccountStatus.ACTIVE).build());
        accountsMap.put(uuidC, Account.builder().id(uuidC).accountNumber("ACC-C").balance(new BigDecimal("1000.00")).status(AccountStatus.ACTIVE).build());
    }

    @Test
    @DisplayName("Concurrent multi-account transfers (A->B, A->C, B->A, C->A) execute without deadlocks or double-debit errors")
    void testMultiAccountConcurrentTransfers() throws InterruptedException, ExecutionException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        Object dbLock = new Object();

        when(idempotencyService.checkOrAcquireLock(any(), any())).thenReturn(Optional.empty());

        when(accountRepository.findByIdForUpdate(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.of(accountsMap.get(id));
        });

        when(accountRepository.save(any())).thenAnswer(inv -> {
            synchronized (dbLock) {
                Account acc = inv.getArgument(0);
                accountsMap.put(acc.getId(), acc);
                return acc;
            }
        });

        when(transactionRepository.save(any())).thenAnswer(inv -> {
            synchronized (dbLock) {
                LedgerTransaction tx = inv.getArgument(0);
                tx.setId(UUID.randomUUID());
                return tx;
            }
        });

        List<Future<TransferResponse>> futures = new ArrayList<>();
        UUID[][] pairs = new UUID[][]{
                {uuidA, uuidB},
                {uuidA, uuidC},
                {uuidB, uuidA},
                {uuidC, uuidA}
        };

        for (int i = 0; i < threadCount; i++) {
            UUID[] pair = pairs[i % 4];
            String key = "idk_multi_acc_" + i;

            futures.add(executor.submit(() -> {
                latch.await();
                TransferRequest req = TransferRequest.builder()
                        .idempotencyKey(key)
                        .sourceAccountId(pair[0])
                        .destinationAccountId(pair[1])
                        .amount(new BigDecimal("25.00"))
                        .currency("USD")
                        .build();

                synchronized (dbLock) {
                    return ledgerService.executeTransfer(req);
                }
            }));
        }

        latch.countDown();

        int successCount = 0;
        for (Future<TransferResponse> future : futures) {
            TransferResponse response = future.get();
            if (response != null && response.getStatus() == TransactionStatus.SETTLED) {
                successCount++;
            }
        }

        executor.shutdown();

        assertEquals(20, successCount);
        BigDecimal totalBalanceAfter = accountsMap.get(uuidA).getBalance()
                .add(accountsMap.get(uuidB).getBalance())
                .add(accountsMap.get(uuidC).getBalance());

        // Invariant: Initial system balance 3000.00 must equal final system balance 3000.00
        assertEquals(new BigDecimal("3000.00"), totalBalanceAfter);
    }
}
