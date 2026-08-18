-- HydraPay Phase 5 Security & Audit Hardening Schema Migration
-- Database Engine: PostgreSQL 16+

-- 1. Create Index on Idempotency Request Hash for payload conflict lookup performance
CREATE INDEX IF NOT EXISTS idx_idempotency_request_hash ON idempotency_records(request_hash);

-- 2. Add Length Constraint to Transaction Description
ALTER TABLE ledger_transactions
    ADD CONSTRAINT chk_description_length CHECK (description IS NULL OR length(description) <= 255);

-- 3. Add Index on Ledger Entry Account and Entry Type
CREATE INDEX IF NOT EXISTS idx_entries_account_type ON ledger_entries(account_id, entry_type);
