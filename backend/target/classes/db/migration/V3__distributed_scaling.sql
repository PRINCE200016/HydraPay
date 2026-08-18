-- HydraPay Phase 6 Distributed Scaling & High Availability Migration
-- Database Engine: PostgreSQL 16+

-- 1. PROCESSED EVENTS TABLE FOR IDEMPOTENT KAFKA CONSUMERS
CREATE TABLE IF NOT EXISTS processed_events (
    event_id UUID PRIMARY KEY,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_processed_events_aggregate ON processed_events(aggregate_id);

-- 2. ADD DISTRIBUTED WORKER CLAIM TRACKING TO OUTBOX EVENTS
ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS worker_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_outbox_worker_claim ON outbox_events(status, worker_id, claimed_at);
