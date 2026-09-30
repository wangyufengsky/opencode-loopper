ALTER TABLE project_stack_profile ADD COLUMN ordinal INTEGER NOT NULL DEFAULT 0 CHECK (ordinal >= 0);

-- Freeze the existing insertion order once; future reads do not depend on mutable SQLite rowids.
UPDATE project_stack_profile SET ordinal = rowid;

CREATE UNIQUE INDEX idx_project_stack_profile_project_ordinal
    ON project_stack_profile(project_id, ordinal DESC);
