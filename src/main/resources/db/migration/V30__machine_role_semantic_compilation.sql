ALTER TABLE task_decomposition ADD COLUMN semantic_plan_json TEXT;
ALTER TABLE task_decomposition ADD COLUMN format_repair_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE task_decomposition ADD COLUMN semantic_repair_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE task_decomposition ADD COLUMN server_compiled INTEGER NOT NULL DEFAULT 0;

ALTER TABLE loop_spec_compilation ADD COLUMN semantic_plan_json TEXT;
ALTER TABLE loop_spec_compilation ADD COLUMN format_repair_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE loop_spec_compilation ADD COLUMN semantic_repair_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE loop_spec_compilation ADD COLUMN server_compiled INTEGER NOT NULL DEFAULT 0;

UPDATE task_decomposition SET format_repair_count = planning_repair_count;
UPDATE loop_spec_compilation SET format_repair_count = planning_repair_count;
