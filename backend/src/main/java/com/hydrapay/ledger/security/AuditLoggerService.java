package com.hydrapay.ledger.security;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Slf4j
public class AuditLoggerService {

    private String getCorrelationId() {
        String cid = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
        return cid != null ? cid : "N/A";
    }

    public void logTransferRequested(String idempotencyKey, UUID sourceId, UUID destId, BigDecimal amount, String currency) {
        log.info("[AUDIT EVENT: TRANSFER_REQUESTED] correlationId={}, idempotencyKey={}, sourceAccount={}, destAccount={}, amount={} {}",
                getCorrelationId(), idempotencyKey, sourceId, destId, amount, currency);
    }

    public void logTransferSucceeded(UUID txId, String idempotencyKey, UUID sourceId, UUID destId, BigDecimal amount) {
        log.info("[AUDIT EVENT: TRANSFER_SUCCEEDED] correlationId={}, txId={}, idempotencyKey={}, sourceAccount={}, destAccount={}, amount={}",
                getCorrelationId(), txId, idempotencyKey, sourceId, destId, amount);
    }

    public void logTransferFailed(String idempotencyKey, String reason) {
        log.warn("[AUDIT EVENT: TRANSFER_FAILED] correlationId={}, idempotencyKey={}, reason={}",
                getCorrelationId(), idempotencyKey, reason);
    }

    public void logInsufficientFunds(UUID sourceId, BigDecimal requestedAmount, String message) {
        log.warn("[AUDIT EVENT: INSUFFICIENT_FUNDS] correlationId={}, sourceAccount={}, requestedAmount={}, details={}",
                getCorrelationId(), sourceId, requestedAmount, message);
    }

    public void logIdempotencyConflict(String idempotencyKey, String reason) {
        log.warn("[AUDIT EVENT: IDEMPOTENCY_CONFLICT] correlationId={}, idempotencyKey={}, reason={}",
                getCorrelationId(), idempotencyKey, reason);
    }

    public void logAuthFailure(String username, String path, String reason) {
        log.warn("[AUDIT EVENT: AUTH_FAILURE] correlationId={}, user={}, path={}, reason={}",
                getCorrelationId(), username, path, reason);
    }

    public void logAccessDenied(String username, String path, String requiredRole) {
        log.warn("[AUDIT EVENT: ACCESS_DENIED] correlationId={}, user={}, path={}, requiredRole={}",
                getCorrelationId(), username, path, requiredRole);
    }

    public void logReconciliationDiscrepancy(int discrepancyCount, int accountsAudited) {
        log.error("[AUDIT EVENT: RECONCILIATION_DISCREPANCY] correlationId={}, discrepancyCount={}, totalAccountsAudited={}",
                getCorrelationId(), discrepancyCount, accountsAudited);
    }
}
