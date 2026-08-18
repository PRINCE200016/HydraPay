package com.hydrapay.ledger.service;

import com.hydrapay.ledger.domain.entity.Account;
import com.hydrapay.ledger.domain.entity.LedgerEntry;
import com.hydrapay.ledger.repository.AccountRepository;
import com.hydrapay.ledger.repository.LedgerEntryRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;

    @Data
    @Builder
    public static class ReconciliationReport {
        private boolean isBalanced;
        private BigDecimal globalNetDelta;
        private int totalAccountsChecked;
        private int totalDiscrepancies;
        private List<DiscrepancyDetail> discrepancies;
    }

    @Data
    @Builder
    public static class DiscrepancyDetail {
        private UUID accountId;
        private String accountNumber;
        private BigDecimal accountBalance;
        private BigDecimal entriesSum;
        private BigDecimal latestEntryBalanceAfter;
        private String reason;
    }

    /**
     * Scheduled hourly reconciliation job to verify the Ledger Invariant:
     * 1. System Net Zero Invariant: SUM(LedgerEntry.amount) == 0 across all settled transactions.
     * 2. Per-Account Balance Invariant: Check if Account Balance matches ledger entries record.
     */
    @Scheduled(cron = "${hydrapay.reconciliation.cron:0 0 * * * *}")
    public ReconciliationReport reconcileLedger() {
        log.info("Starting scheduled Ledger Invariant Reconciliation audit...");
        List<Account> accounts = accountRepository.findAll();
        List<DiscrepancyDetail> discrepancies = new ArrayList<>();

        // 1. Verify Global System Invariant: Sum of all DEBIT (-X) and CREDIT (+X) entries must equal 0
        BigDecimal globalNetSum = entryRepository.calculateGlobalLedgerNetSum();
        boolean globalInvariantValid = globalNetSum.compareTo(BigDecimal.ZERO) == 0;

        if (!globalInvariantValid) {
            log.error("LEDGER CRITICAL ERROR: Global system net sum invariant violated! Net sum delta = {}", globalNetSum);
        }

        // 2. Verify Per-Account Invariants
        for (Account account : accounts) {
            BigDecimal currentBalance = account.getBalance();
            BigDecimal entriesSum = entryRepository.calculateSumByAccountId(account.getId());
            Optional<LedgerEntry> latestEntryOpt = entryRepository.findFirstByAccountIdOrderByCreatedAtDesc(account.getId());

            if (latestEntryOpt.isPresent()) {
                LedgerEntry latestEntry = latestEntryOpt.get();
                BigDecimal balanceAfter = latestEntry.getBalanceAfter();

                if (currentBalance.compareTo(balanceAfter) != 0) {
                    DiscrepancyDetail detail = DiscrepancyDetail.builder()
                            .accountId(account.getId())
                            .accountNumber(account.getAccountNumber())
                            .accountBalance(currentBalance)
                            .entriesSum(entriesSum)
                            .latestEntryBalanceAfter(balanceAfter)
                            .reason(String.format("Account balance %s does not match latest entry balance_after %s",
                                    currentBalance, balanceAfter))
                            .build();
                    discrepancies.add(detail);
                    log.error("LEDGER RECONCILIATION DISCREPANCY DETECTED: {}", detail.getReason());
                }
            }
        }

        boolean isBalanced = globalInvariantValid && discrepancies.isEmpty();

        if (isBalanced) {
            log.info("LEDGER RECONCILIATION PASSED: Verified {} account(s). Global net balance delta: {}. System is 100% consistent.",
                    accounts.size(), globalNetSum);
        } else {
            log.error("LEDGER RECONCILIATION FAILED: Found {} discrepancy(ies) across {} accounts audited.",
                    discrepancies.size(), accounts.size());
        }

        return ReconciliationReport.builder()
                .isBalanced(isBalanced)
                .globalNetDelta(globalNetSum)
                .totalAccountsChecked(accounts.size())
                .totalDiscrepancies(discrepancies.size())
                .discrepancies(discrepancies)
                .build();
    }
}
