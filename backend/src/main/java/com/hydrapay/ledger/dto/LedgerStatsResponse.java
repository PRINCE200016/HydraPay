package com.hydrapay.ledger.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerStatsResponse {
    private long currentTps;
    private double idempotencyHitRate;
    private long outboxLagMs;
    private double successRatePercent;
    private long totalTransactions;
    private long pendingOutboxEvents;
}
