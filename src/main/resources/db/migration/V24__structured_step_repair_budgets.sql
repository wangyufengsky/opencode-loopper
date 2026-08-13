ALTER TABLE task_decomposition
    ADD COLUMN planning_repair_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE loop_spec_compilation
    ADD COLUMN planning_repair_count INTEGER NOT NULL DEFAULT 0;
