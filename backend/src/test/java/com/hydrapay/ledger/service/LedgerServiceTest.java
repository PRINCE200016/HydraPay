package com.hydrapay.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydrapay.ledger.domain.entity.Account;
import com.hydrapay.ledger.domain.entity.LedgerTransaction;
import com.hydrapay.ledger.domain.enums.AccountStatus;
import com.hydrapay.ledger.domain.enums.TransactionStatus;
import com.hydrapay.ledger.dto.TransferRequest;
import com.hydrapay.ledger.dto.TransferResponse;
import com.hydrapay.ledger.exception.InsufficientFundsException;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

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

    private Account sourceAccount;
    private Account destAccount;
    private UUID sourceId;
    private UUID destId;

    @BeforeEach
    void setUp() {
        sourceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        destId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        sourceAccount = Account.builder()
                .id(sourceId)
                .accountNumber("ACC-SOURCE")
                .accountHolderName("Source User")
                .currency("USD")
                .balance(new BigDecimal("1000.00"))
                .status(AccountStatus.ACTIVE)
                .build();

        destAccount = Account.builder()
                .id(destId)
                .accountNumber("ACC-DEST")
                .accountHolderName("Dest User")
                .currency("USD")
                .balance(new BigDecimal("500.00"))
                .status(AccountStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("Should execute atomic transfer with double-entry balance mutations")
    void testSuccessfulTransfer() {
        TransferRequest request = TransferRequest.builder()
                .idempotencyKey("idk_test_123")
                .sourceAccountId(sourceId)
                .destinationAccountId(destId)
                .amount(new BigDecimal("200.00"))
                .currency("USD")
                .description("Test Transfer")
                .build();

        when(idempotencyService.checkOrAcquireLock(any(), any())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findByIdForUpdate(destId)).thenReturn(Optional.of(destAccount));
        when(transactionRepository.save(any())).thenAnswer(invocation -> {
            LedgerTransaction tx = invocation.getArgument(0);
            tx.setId(UUID.randomUUID());
            return tx;
        });

        TransferResponse response = ledgerService.executeTransfer(request);

        assertNotNull(response);
        assertEquals(TransactionStatus.SETTLED, response.getStatus());
        assertEquals(new BigDecimal("800.00"), sourceAccount.getBalance());
        assertEquals(new BigDecimal("700.00"), destAccount.getBalance());
        verify(entryRepository, times(2)).save(any());
        verify(outboxEventRepository, times(1)).save(any());
        verify(idempotencyService, times(1)).saveCompletedResult(eq("idk_test_123"), any());
    }

    @Test
    @DisplayName("Should throw InsufficientFundsException when balance is too low")
    void testInsufficientFunds() {
        TransferRequest request = TransferRequest.builder()
                .idempotencyKey("idk_test_fail")
                .sourceAccountId(sourceId)
                .destinationAccountId(destId)
                .amount(new BigDecimal("5000.00"))
                .currency("USD")
                .build();

        when(idempotencyService.checkOrAcquireLock(any(), any())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findByIdForUpdate(destId)).thenReturn(Optional.of(destAccount));

        assertThrows(InsufficientFundsException.class, () -> ledgerService.executeTransfer(request));
        verify(entryRepository, never()).save(any());
    }
}
