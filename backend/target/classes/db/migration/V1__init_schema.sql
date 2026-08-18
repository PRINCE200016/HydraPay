-- HydraPay Double-Entry Settlement & Ledger Schema
-- Database Engine: PostgreSQL 16+

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. ACCOUNTS TABLE
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number VARCHAR(64) UNIQUE NOT NULL,
    account_holder_name VARCHAR(128) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT chk_positive_balance CHECK (balance >= 0)
);

-- Index for account searches
CREATE INDEX idx_accounts_account_number ON accounts(account_number);

-- 2. LEDGER TRANSACTIONS TABLE
CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(128) UNIQUE NOT NULL,
    source_account_id UUID NOT NULL REFERENCES accounts(id),
    destination_account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    description VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_transaction_status CHECK (status IN ('PENDING', 'PROCESSING', 'SETTLED', 'FAILED')),
    CONSTRAINT chk_positive_amount CHECK (amount > 0),
    CONSTRAINT chk_different_accounts CHECK (source_account_id <> destination_account_id)
);

CREATE INDEX idx_ledger_tx_idempotency ON ledger_transactions(idempotency_key);
CREATE INDEX idx_ledger_tx_source ON ledger_transactions(source_account_id);
CREATE INDEX idx_ledger_tx_dest ON ledger_transactions(destination_account_id);
CREATE INDEX idx_ledger_tx_created ON ledger_transactions(created_at DESC);

-- 3. LEDGER ENTRIES TABLE (DOUBLE-ENTRY BOOKKEEPING)
-- Immutable ledger entries: Every transaction creates 1 DEBIT and 1 CREDIT entry.
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES ledger_transactions(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id),
    entry_type VARCHAR(6) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    balance_after NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_non_zero_entry_amount CHECK (amount <> 0)
);

CREATE INDEX idx_entries_tx_id ON ledger_entries(transaction_id);
CREATE INDEX idx_entries_account_id ON ledger_entries(account_id);

-- 4. IDEMPOTENCY RECORDS TABLE
CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    response_payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT chk_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_idempotency_expires ON idempotency_records(expires_at);

-- 5. TRANSACTIONAL OUTBOX EVENTS TABLE
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_outbox_pending ON outbox_events(status, created_at) WHERE status = 'PENDING';

-- Seed initial test accounts for instant operational verification
INSERT INTO accounts (id, account_number, account_holder_name, currency, balance, status) VALUES
    ('00000000-0000-0000-0000-000000000001', 'ACC-1001-TREASURY', 'HydraPay Central Liquidity Pool', 'USD', 10000000.0000, 'ACTIVE'),
    ('00000000-0000-0000-0000-000000000002', 'ACC-2002-MERCHANT-ALPHA', 'Stripe Retail Global LLC', 'USD', 250000.0000, 'ACTIVE'),
    ('00000000-0000-0000-0000-000000000003', 'ACC-3003-MERCHANT-BETA', 'Adyen E-Commerce Ltd', 'USD', 500000.0000, 'ACTIVE'),
    ('00000000-0000-0000-0000-000000000004', 'ACC-4004-USER-ALICE', 'Alice Vance (Retail)', 'USD', 15000.0000, 'ACTIVE'),
    ('00000000-0000-0000-0000-000000000005', 'ACC-5005-USER-BOB', 'Bob Miller (Retail)', 'USD', 8500.0000, 'ACTIVE');
