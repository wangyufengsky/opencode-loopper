CREATE TABLE snapshot_review_context_request (
    batch_id TEXT NOT NULL REFERENCES template_task_batch(id) ON DELETE CASCADE,
    request_sha256 TEXT NOT NULL,
    PRIMARY KEY(batch_id, request_sha256)
);

CREATE TABLE snapshot_review_reusable (
    batch_id TEXT PRIMARY KEY REFERENCES template_task_batch(id) ON DELETE CASCADE,
    fingerprint TEXT NOT NULL,
    output_sha256 TEXT NOT NULL
);
CREATE INDEX idx_snapshot_review_reusable_key ON snapshot_review_reusable(fingerprint);
CREATE TABLE snapshot_review_reuse (
    batch_id TEXT PRIMARY KEY REFERENCES template_task_batch(id) ON DELETE CASCADE,
    source_batch_id TEXT NOT NULL,
    source_task_id TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    output_sha256 TEXT NOT NULL
);
