ALTER TABLE project ADD COLUMN managed INTEGER NOT NULL DEFAULT 1;

CREATE INDEX idx_project_managed_created ON project(managed, created_at DESC);
