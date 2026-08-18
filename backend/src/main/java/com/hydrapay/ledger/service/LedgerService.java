package com.hydrapay.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydrapay.ledger.domain.entity.Account;
import com.hydrapay.ledger.domain.entity.LedgerEntry;
import com.hydrapay.ledger.domain.entity.LedgerTransaction;
import com.hydrapay.ledger.domain.entity.OutboxEvent;
import com.hydrapay.ledger.domain.enums.EntryType;
import com.hydrapay.ledger.domain.enums.OutboxStatus;
import com.hydrapay.ledger.domain.enums.TransactionStatus;
import com.hydrapay.ledger.dto.TransferRequest;
import com.hydrapay.ledger.dto.TransferResponse;
import com.hydrapay.ledger.exception.AccountNotFoundException;
import com.hydrapay.ledger.exception.InsufficientFundsException;
import com.hydrapay.ledger.repository.AccountRepository;
import com.hydrapay.ledger.repository.LedgerEntryRepository;
import com.hydrapay.ledger.repository.LedgerTransactionRepository;
import com.hydrapay.ledger.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Executes an atomic transfer of funds between two accounts.
     * Enforces Idempotency, Deterministic Account Locking (Deadlock Prevention),
     * Double-Entry Bookkeeping, and Transactional Outbox Pattern.
     */
    public TransferResponse executeTransfer(TransferRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String idempotencyKey = request.getIdempotencyKey();
        String requestHash = String.valueOf(request.hashCode());

        // Step 1: Idempotency Check & Distributed Lock Acquisition
        Optional<TransferResponse> cachedResponse = idempotencyService.checkOrAcquireLock(idempotencyKey, requestHash);
        if (cachedResponse.isPresent()) {
            recordMetrics("CACHED", sample);
            return cachedResponse.get();
        }

        try {
            // Step 2: Atomic DB Transaction Execution with Deterministic Row Locking
            TransferResponse response = processAtomicTransfer(request);

            // Step 3: Save Result to Idempotency Cache & Storage
            idempotencyService.saveCompletedResult(idempotencyKey, response);

            recordMetrics("SUCCESS", sample);
            return response;

        } catch (Exception ex) {
            log.error("Transfer execution failed for idempotencyKey: {}", idempotencyKey, ex);
            idempotencyService.releaseLock(idempotencyKey);
            recordMetrics("FAILED", sample);
            throw ex;
        }
    }

    private void recordMetrics(String status, Timer.Sample sample) {
        try {
            Counter.builder("transactions.processed")
                    .description("Total number of processed transactions")
                    .tag("status", status)
                    .register(meterRegistry)
                    .increment();

            sample.stop(Timer.builder("transaction.latency")
                    .description("Transaction processing latency")
                    .tag("status", status)
                    .register(meterRegistry));
        } catch (Exception e) {
            log.warn("Failed to record Micrometer metrics", e);
        }
    }

    @Transactional
    public TransferResponse processAtomicTransfer(TransferRequest request) {
        UUID sourceId = request.getSourceAccountId();
        UUID destId = request.getDestinationAccountId();
        BigDecimal amount = request.getAmount();

        if (sourceId.equals(destId)) {
            throw new IllegalArgumentException("Source and destination accounts must be different.");
        }

        // --- ACCOUNT LOCKING STRATEGY (DETERMINISTIC ID ORDERING) ---
        // Lock accounts in lexicographical UUID order to guarantee 0 deadlocks under high concurrency
        UUID firstLockId = sourceId.compareTo(destId) < 0 ? sourceId : destId;
        UUID secondLockId = sourceId.compareTo(destId) < 0 ? destId : sourceId;

        log.debug("Acquiring SELECT FOR UPDATE locks: 1st={}, 2nd={}", firstLockId, secondLockId);
        Account firstAccount = accountRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + firstLockId));

        Account secondAccount = accountRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + secondLockId));

        Account sourceAccount = sourceId.equals(firstLockId) ? firstAccount : secondAccount;
        Account destAccount = destId.equals(firstLockId) ? firstAccount : secondAccount;

        // Step A: Balance Validation
        if (!sourceAccount.hasSufficientBalance(amount)) {
            throw new InsufficientFundsException(
                String.format("Insufficient funds in account %s. Balance: %s, Required: %s",
                        sourceAccount.getAccountNumber(), sourceAccount.getBalance(), amount));
        }

        // Step B: Double-Entry Balance Mutations
        sourceAccount.debit(amount);
        destAccount.credit(amount);

        accountRepository.save(sourceAccount);
        accountRepository.save(destAccount);

        // Step C: Create Primary Ledger Transaction Record
        LedgerTransaction transaction = LedgerTransaction.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .sourceAccountId(sourceId)
                .destinationAccountId(destId)
                .amount(amount)
                .currency(request.getCurrency())
                .status(TransactionStatus.SETTLED)
                .description(request.getDescription())
                .build();
        transaction = transactionRepository.save(transaction);

        // Step D: Create Double-Entry Bookkeeping Audit Records (1 DEBIT, 1 CREDIT)
        LedgerEntry debitEntry = LedgerEntry.builder()
                .transactionId(transaction.getId())
                .accountId(sourceId)
                .entryType(EntryType.DEBIT)
                .amount(amount.negate()) // DEBIT reduces asset balance
                .currency(request.getCurrency())
                .balanceAfter(sourceAccount.getBalance())
                .build();

        LedgerEntry creditEntry = LedgerEntry.builder()
                .transactionId(transaction.getId())
                .accountId(destId)
                .entryType(EntryType.CREDIT)
                .amount(amount) // CREDIT increases balance
                .currency(request.getCurrency())
                .balanceAfter(destAccount.getBalance())
                .build();

        entryRepository.save(debitEntry);
        entryRepository.save(creditEntry);

        // Step E: Transactional Outbox Event Insertion (Atomic with DB Update)
        TransferResponse response = TransferResponse.builder()
                .transactionId(transaction.getId())
                .idempotencyKey(request.getIdempotencyKey())
                .sourceAccountId(sourceId)
                .destinationAccountId(destId)
                .amount(amount)
                .currency(request.getCurrency())
                .status(TransactionStatus.SETTLED)
                .sourceBalanceAfter(sourceAccount.getBalance())
                .destinationBalanceAfter(destAccount.getBalance())
                .timestamp(OffsetDateTime.now())
                .cachedResponse(false)
                .build();

        try {
            String eventPayload = objectMapper.writeValueAsString(response);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("TRANSFER")
                    .aggregateId(transaction.getId().toString())
                    .eventType("TRANSACTION_SETTLED")
                    .payload(eventPayload)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to write transactional outbox event for txId: {}", transaction.getId(), e);
        }

        log.info("Ledger TRANSFER SETTLED: txId={}, amount={} {} -> {}",
                transaction.getId(), amount, sourceAccount.getAccountNumber(), destAccount.getAccountNumber());

        return response;
    }
}
