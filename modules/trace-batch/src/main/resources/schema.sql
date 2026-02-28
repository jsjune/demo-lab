-- Range Partitioned Table for Trace Events
CREATE TABLE IF NOT EXISTS trace_events (
    event_id VARCHAR(64) NOT NULL,
    tx_id VARCHAR(64) NOT NULL,
    type VARCHAR(32),
    category VARCHAR(32),
    server_name VARCHAR(64),
    target TEXT,
    duration_ms BIGINT,
    success BOOLEAN,
    timestamp BIGINT NOT NULL,
    extra_info JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, timestamp) -- Primary key must include partition key
) PARTITION BY RANGE (timestamp);

-- Initial default partition (for safety)
CREATE TABLE IF NOT EXISTS trace_events_default PARTITION OF trace_events DEFAULT;

-- Index for tx_id
CREATE INDEX IF NOT EXISTS idx_trace_events_tx_id ON trace_events(tx_id);

-- Index for monitoring metrics (TPS, Latency, Error Rate)
CREATE INDEX IF NOT EXISTS idx_trace_events_metrics ON trace_events(timestamp, type);
