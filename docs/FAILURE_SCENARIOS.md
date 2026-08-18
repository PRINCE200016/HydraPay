# HydraPay Failure Scenarios & Resilience Guide

## Architectural Fault Matrix

| Failure Mode | Impact | Engine Mitigation & Recovery Strategy |
| :--- | :--- | :--- |
| **1. Client Network Timeout / Double Submit** | Client re-sends request with same `X-Idempotency-Key` | **Redis SETNX Lock / Cache**: Tier 1 checks Redis. Returns previous settled response immediately without re-executing balance mutations. Zero double-debit. |
| **2. Redis Cache Failover / Crash** | L1 cache temporarily unavailable | **PostgreSQL Tier 2 Guard**: `idempotency_records` table has `UNIQUE(idempotency_key)`. DB raises `DataIntegrityViolationException` and rejects duplicate. |
| **3. Application Node Crash Mid-Transfer** | Server dies during transaction execution | **PostgreSQL Transaction Rollback**: Uncommitted `@Transactional` boundary rolls back balance changes, entries, and outbox records automatically. |
| **4. Kafka Broker Outage** | Broker down or unreachable | **Batched Transactional Outbox Worker**: Outbox events stay committed in `outbox_events` table with `status = PENDING`. `OutboxPublisherService` retries with exponential backoff up to `max-retries` (default 5) before setting `status = FAILED`. Zero lost events. |
| **5. High-Concurrency Account Deadlock** | Concurrent transfers (A $\rightarrow$ B and B $\rightarrow$ A) | **Deterministic Lock Ordering**: Accounts are sorted by UUID and locked in ascending order (`min(A,B)` then `max(A,B)`). Deadlock graphs strictly prevented. |
| **6. Database Connection Pool Exhaustion** | Extreme load spikes | **HikariCP Tuning & Actuator Metrics**: Fast-fail connection timeout (30s) with active health check endpoints (`/actuator/health`). |
| **7. Unnoticed Ledger Imbalance / Discrepancy** | Unscheduled DB data mutation or corruption | **Scheduled Ledger Reconciliation**: Hourly audit (`ReconciliationService`) computes system net-zero sum and validates per-account balance invariants, logging `ERROR` alerts immediately. |

---

## Detailed Crash & Recovery Scenarios

### Scenario A: Network Partition During Transfer
1. Client sends Transfer Request `T1` with `IdempotencyKey="idk_991"`.
2. Engine processes `T1`, mutates balances, and commits PostgreSQL transaction.
3. Network connection drops before HTTP 200 response reaches client.
4. Client retries sending `T1` with `IdempotencyKey="idk_991"`.
5. **Mitigation**: Redis cache returns cached `TransferResponse` payload. Client gets instant success response; zero balance duplication occurs.

### Scenario B: Database Outage During Outbox Publishing
1. Engine writes transfer record and outbox event (`PENDING`) to PostgreSQL.
2. Kafka broker experiences a temporary partition.
3. **Mitigation**: The background `OutboxPublisherService` encounters an error, increments `retry_count`, and keeps outbox status `PENDING`. As soon as Kafka recovers, worker publishes events cleanly in batches. If `retryCount >= max-retries`, the event is marked `FAILED` for dead-letter processing without dropping historical outbox records. Zero lost financial events!

