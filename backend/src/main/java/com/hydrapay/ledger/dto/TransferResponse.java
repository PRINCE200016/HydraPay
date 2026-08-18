package com.hydrapay.ledger.dto;

import com.hydrapay.ledger.domain.enums.TransactionStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferResponse {
    private UUID transactionId;
    private String idempotencyKey;
    private UUID sourceAccountId;
    private UUID destinationAccountId;
    private BigDecimal amount;
    private String currency;
    private TransactionStatus status;
    private BigDecimal sourceBalanceAfter;
    private BigDecimal destinationBalanceAfter;
    private OffsetDateTime timestamp;
    private boolean cachedResponse;
}
