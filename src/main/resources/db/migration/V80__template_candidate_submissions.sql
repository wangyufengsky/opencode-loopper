CREATE TABLE template_candidate_submission (
    batch_id TEXT NOT NULL REFERENCES template_task_batch(id) ON DELETE CASCADE,
    submission_revision INTEGER NOT NULL CHECK(submission_revision > 0),
    idempotency_key TEXT NOT NULL CHECK(length(idempotency_key) BETWEEN 1 AND 128),
    candidate_sha256 TEXT NOT NULL CHECK(length(candidate_sha256) = 64),
    accepted INTEGER NOT NULL CHECK(accepted IN (0,1)),
    output_json TEXT,
    response_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY(batch_id, submission_revision),
    UNIQUE(batch_id, idempotency_key),
    CHECK((accepted=1 AND output_json IS NOT NULL) OR (accepted=0 AND output_json IS NULL))
);
CREATE UNIQUE INDEX template_candidate_one_acceptance ON template_candidate_submission(batch_id) WHERE accepted=1;
