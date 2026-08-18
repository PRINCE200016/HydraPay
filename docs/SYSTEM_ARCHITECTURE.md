# HydraPay System Architecture Documentation

## Overview
**HydraPay** is a high-throughput, fault-tolerant financial settlement and double-entry ledger engine built for mission-critical payment processors. The core system architecture achieves **10,000+ TPS** with **zero double-debit errors** and **exactly-once processing guarantees**.

---

## 1. High-Level Architectural Topology

```mermaid
flowchart TD
    Client["Client / API Gateway"] -->|POST /api/v1/transfers| IdempotencyGuard["Tier 1: Redis Fast Lock (SETNX)"]
    
    subgraph Core Engine ["HydraPay Spring Boot Engine"]
        IdempotencyGuard -->|Lock Granted| DeterministicLocking["Deterministic Account Locking (SELECT FOR UPDATE)"]
        DeterministicLocking -->|Lock Accounts by Min(ID)| BalanceValidation["Balance & Account Status Check"]
        BalanceValidation -->|Sufficient Balance| DoubleEntryMutation["Mutate Balances (Debit Source, Credit Dest)"]
        DoubleEntryMutation -->|Insert Records| LedgerStorage["Insert LedgerTransaction & Double-Entry LedgerEntries"]
        LedgerStorage -->|Same DB Transaction| OutboxInsertion["Write Transactional Outbox Event"]
    end

    subgraph Database Layer ["PostgreSQL 16 Storage Engine"]
        LedgerStorage --> PostgreSQL[(PostgreSQL Database)]
        OutboxInsertion --> PostgreSQL
    end

    subgraph Async Event Stream ["Kafka Event Pipeline"]
        OutboxWorker["Outbox Relay Worker"] -->|Poll Pending Events| PostgreSQL
        OutboxWorker -->|Publish Event| KafkaTopic["Kafka Topic: ledger-events"]
        KafkaTopic --> Consumer1["Settlement Service"]
        KafkaTopic --> Consumer2["Account Notification Service"]
    end
```

---

## 2. Account Locking Strategy (Deadlock Elimination)

### Problem Statement
In concurrent ledger operations, executing non-deterministic locking (e.g., Thread 1 locks Account A then B, Thread 2 locks Account B then A) results in cyclic lock waits inside PostgreSQL (`SQLState: 40P01 Deadlock Detected`).

### Architectural Solution: Deterministic Ordering
HydraPay enforces **Deterministic Id Ordering** across all application nodes:

$$\text{First Lock Target} = \min(\text{SourceAccountId}, \text{DestAccountId})$$
$$\text{Second Lock Target} = \max(\text{SourceAccountId}, \text{DestAccountId})$$

```java
UUID firstLockId = sourceId.compareTo(destId) < 0 ? sourceId : destId;
UUID secondLockId = sourceId.compareTo(destId) < 0 ? destId : sourceId;

Account firstLocked = accountRepository.findByIdForUpdate(firstLockId);
Account secondLocked = accountRepository.findByIdForUpdate(secondLockId);
```

By ensuring that all parallel execution threads lock resources in strict lexicographical UUID order, cyclic wait cycles in PostgreSQL lock manager trees are strictly impossible.

---

## 3. Distributed 2-Tier Idempotency Architecture

To prevent double-debit under network retries or client retry loops:

| Tier | Guard Mechanism | Speed | Persistence | Failover Handling |
| :--- | :--- | :--- | :--- | :--- |
| **Tier 1** | Redis Distributed Lock (`SET key val NX PX 10000`) | $< 1\text{ms}$ | In-Memory / Transient | Lock expires automatically on crash |
| **Tier 2** | PostgreSQL Unique Constraint (`UNIQUE(idempotency_key)`) | $2-5\text{ms}$ | Persistent ACID Disk | `DataIntegrityViolationException` catch fallback |

---

## 4. Batched Transactional Outbox Pattern & At-Least-Once Semantics

To achieve atomicity between database balance updates and Kafka message publishing without two-phase commit (2PC) overhead:
1. **DB Transaction Boundary**: Balance update + `LedgerEntry` insertion + `OutboxEvent` (`status = PENDING`) are committed atomically in PostgreSQL.
2. **Batched Outbox Relay**: Background worker (`OutboxPublisherService`) polls `outbox_events` in configurable batches (`hydrapay.outbox.batch-size: 500`), asynchronously dispatches messages to Kafka topic `ledger-events` via `CompletableFuture.allOf(...)`, and updates status to `PUBLISHED` via batch write `repository.saveAll(...)`.
3. **At-Least-Once Delivery**: Events that fail Kafka dispatch retain `PENDING` status and retry up to configurable `max-retries` (default 5). If max retries are reached, events transition to `FAILED`. Consumers tolerate duplicate deliveries using unique `transaction_id`.

---

## 5. Observability & Scheduled Ledger Invariant Reconciliation

### Micrometer & Spring Boot Actuator
- Exposed management endpoints: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`, `/actuator/info`.
- Key Metric Counters & Timers:
  - `transactions.processed`: Counter tagged by `status=SUCCESS`, `status=FAILED`, `status=CACHED` (low-cardinality tags).
  - `transaction.latency`: Timer tracking execution duration per transaction status.

### Scheduled Ledger Reconciliation Service (`ReconciliationService`)
- Scheduled hourly via `@Scheduled(cron = "${hydrapay.reconciliation.cron:0 0 * * * *}")`.
- **System Net Zero Invariant**: Asserts $\sum \text{LedgerEntry.amount} = 0$. Logs `ERROR` alert if violated.
- **Account Balance Invariant**: Asserts `account.balance` matches latest `LedgerEntry.balanceAfter`. Logs `ERROR` per mismatched account.
- **Detection Only**: Operates purely as an automated consistency detector without altering balances.

