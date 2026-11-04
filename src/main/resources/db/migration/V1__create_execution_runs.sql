CREATE TABLE execution_runs (
    request_id UUID PRIMARY KEY,
    model TEXT NOT NULL,
    prompt TEXT NOT NULL,
    response TEXT,
    duration_ms BIGINT NOT NULL CHECK (duration_ms >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    error_code TEXT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX execution_runs_history_idx ON execution_runs (created_at DESC, request_id DESC);
