ALTER TABLE project ADD COLUMN document_path TEXT;

CREATE TABLE template_task_plan (
    task_id TEXT PRIMARY KEY REFERENCES template_task_run(task_id) ON DELETE CASCADE,
    review_batches INTEGER NOT NULL CHECK (review_batches >= 0),
    contributor_batches INTEGER NOT NULL CHECK (contributor_batches >= 0)
);
