-- Persist the semantic planning/evidence-mapping pass separately from the
-- final machine JSON so a restart or transport retry resumes the correct turn.
ALTER TABLE task_decomposition ADD COLUMN workflow_step TEXT NOT NULL DEFAULT 'FINAL_JSON';
ALTER TABLE task_decomposition ADD COLUMN planning_json TEXT;

ALTER TABLE loop_spec_compilation ADD COLUMN workflow_step TEXT NOT NULL DEFAULT 'FINAL_JSON';
ALTER TABLE loop_spec_compilation ADD COLUMN planning_json TEXT;
