# HydraPay Database & Double-Entry Ledger Design

## Overview
HydraPay utilizes PostgreSQL 16 as its primary ACID storage engine. The ledger schema enforces **strict double-entry bookkeeping** where no money is ever created or destroyed out of thin air.

---

## Entity Relationship (ER) Diagram

```mermaid
erDiagram
    ACCOUNTS ||--o{ LEDGER_ENTRIES : contains
    LEDGER_TRANSACTIONS ||--|{ LEDGER_ENTRIES : has
    LEDGER_TRANSACTIONS ||--o| OUTBOX_EVENTS : triggers

    ACCOUNTS {
        uuid id PK
        varchar account_number UK
        varchar account_holder_name
        varchar currency
        numeric balance
        varchar status
        bigint version
    }

    LEDGER_TRANSACTIONS {
        uuid id PK
        varchar idempotency_key UK
        uuid source_account_id FK
        uuid destination_account_id FK
        numeric amount
        varchar status
    }

    LEDGER_ENTRIES {
        uuid id PK
        uuid transaction_id FK
        uuid account_id FK
        varchar entry_type
        numeric amount
        numeric balance_after
    }

    OUTBOX_EVENTS {
        uuid id PK
        varchar aggregate_type
        varchar aggregate_id
        varchar event_type
        jsonb payload
        varchar status
    }
```

---

## Double-Entry Invariant Rule

For every financial transfer committed in `ledger_transactions`, exactly two entries are recorded in `ledger_entries`:
1. **DEBIT Entry**: Applied to the Source Account ($\text{Amount} < 0$).
2. **CREDIT Entry**: Applied to the Destination Account ($\text{Amount} > 0$).

### Mathematical Proof of Balance Invariant
For any given transaction $T$:

$$\sum_{e \in \text{Entries}(T)} \text{Amount}(e) = \text{DebitAmount} + \text{CreditAmount} = (-A) + (+A) = 0$$

Across all ledger entries in the entire database:

$$\sum_{\text{All Entries}} \text{Amount} = 0$$

This mathematical balance invariant guarantees that total assets and total liabilities across the system remain perfectly net-zero at all times.

---

## High-Throughput Indexing Strategy

To support 10,000+ TPS query execution:

1. **`accounts(account_number)`**: B-Tree index for fast account lookup.
2. **`ledger_transactions(idempotency_key)`**: Unique Index for Tier 2 DB idempotency fallback checks.
3. **`outbox_events(status, created_at) WHERE status = 'PENDING'`**: Partial Index optimization for instant outbox polling without table scans.
