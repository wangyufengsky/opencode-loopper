CREATE TABLE template_length_continuation (
    batch_id TEXT NOT NULL REFERENCES template_task_batch(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK(ordinal > 0),
    prior_prompt_json TEXT NOT NULL,
    prompt_json TEXT NOT NULL,
    prompt_sha256 TEXT NOT NULL CHECK(length(prompt_sha256) = 64),
    distinct_submissions INTEGER NOT NULL CHECK(distinct_submissions >= 0),
    stagnant_lengths INTEGER NOT NULL CHECK(stagnant_lengths BETWEEN 0 AND 2),
    created_at TEXT NOT NULL,
    PRIMARY KEY(batch_id, ordinal)
);
