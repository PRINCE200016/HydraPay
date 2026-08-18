package com.hydrapay.ledger.service;

import com.hydrapay.ledger.domain.entity.Account;
import com.hydrapay.ledger.domain.entity.LedgerEntry;
import com.hydrapay.ledger.domain.enums.AccountStatus;
import com.hydrapay.ledger.domain.enums.EntryType;
import com.hydrapay.ledger.repository.AccountRepository;
import com.hydrapay.ledger.repository.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private LedgerEntryRepository entryRepository;

    @InjectMocks
    private ReconciliationService reconciliationService;

    private Account account1;
    private Account account2;

    @BeforeEach
    void setUp() {
        account1 = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber("ACC-1001")
                .accountHolderName("User One")
                .balance(new BigDecimal("800.00"))
                .status(AccountStatus.ACTIVE)
                .build();

        account2 = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber("ACC-2002")
                .accountHolderName("User Two")
                .balance(new BigDecimal("700.00"))
                .status(AccountStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("Should pass reconciliation audit when global net sum is 0 and account balances match entries")
    void testReconciliationSuccess() {
        when(accountRepository.findAll()).thenReturn(List.of(account1, account2));
        when(entryRepository.calculateGlobalLedgerNetSum()).thenReturn(BigDecimal.ZERO);

        LedgerEntry entry1 = LedgerEntry.builder()
                .accountId(account1.getId())
                .entryType(EntryType.DEBIT)
                .amount(new BigDecimal("-200.00"))
                .balanceAfter(new BigDecimal("800.00"))
                .build();

        LedgerEntry entry2 = LedgerEntry.builder()
                .accountId(account2.getId())
                .entryType(EntryType.CREDIT)
                .amount(new BigDecimal("200.00"))
                .balanceAfter(new BigDecimal("700.00"))
                .build();

        when(entryRepository.findFirstByAccountIdOrderByCreatedAtDesc(account1.getId()))
                .thenReturn(Optional.of(entry1));
        when(entryRepository.findFirstByAccountIdOrderByCreatedAtDesc(account2.getId()))
                .thenReturn(Optional.of(entry2));

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcileLedger();

        assertTrue(report.isBalanced());
        assertEquals(0, report.getTotalDiscrepancies());
        assertEquals(2, report.getTotalAccountsChecked());
    }

    @Test
    @DisplayName("Should detect discrepancy when account balance differs from latest ledger entry balance_after")
    void testReconciliationDiscrepancy() {
        when(accountRepository.findAll()).thenReturn(List.of(account1));
        when(entryRepository.calculateGlobalLedgerNetSum()).thenReturn(BigDecimal.ZERO);

        // Account balance is 800.00, but latest ledger entry says 900.00
        LedgerEntry entry1 = LedgerEntry.builder()
                .accountId(account1.getId())
                .entryType(EntryType.DEBIT)
                .amount(new BigDecimal("-100.00"))
                .balanceAfter(new BigDecimal("900.00"))
                .build();

        when(entryRepository.findFirstByAccountIdOrderByCreatedAtDesc(account1.getId()))
                .thenReturn(Optional.of(entry1));

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcileLedger();

        assertFalse(report.isBalanced());
        assertEquals(1, report.getTotalDiscrepancies());
        assertEquals("ACC-1001", report.getDiscrepancies().get(0).getAccountNumber());
    }
}
