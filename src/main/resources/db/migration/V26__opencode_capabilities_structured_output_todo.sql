-- Record the response transport chosen for every structured design/review turn
-- and the workspace-scoped Todo capability of implementation Sessions.
ALTER TABLE task_decomposition ADD COLUMN planning_response_mode TEXT NOT NULL DEFAULT 'TEXT_MARKER';
ALTER TABLE task_decomposition ADD COLUMN planning_response_schema_id TEXT;
ALTER TABLE task_decomposition ADD COLUMN planning_format_fallback_used INTEGER NOT NULL DEFAULT 0;
ALTER TABLE task_decomposition ADD COLUMN final_response_mode TEXT NOT NULL DEFAULT 'TEXT_MARKER';
ALTER TABLE task_decomposition ADD COLUMN final_response_schema_id TEXT;
ALTER TABLE task_decomposition ADD COLUMN final_format_fallback_used INTEGER NOT NULL DEFAULT 0;

ALTER TABLE loop_spec_compilation ADD COLUMN planning_response_mode TEXT NOT NULL DEFAULT 'TEXT_MARKER';
ALTER TABLE loop_spec_compilation ADD COLUMN planning_response_schema_id TEXT;
ALTER TABLE loop_spec_compilation ADD COLUMN planning_format_fallback_used INTEGER NOT NULL DEFAULT 0;
ALTER TABLE loop_spec_compilation ADD COLUMN final_response_mode TEXT NOT NULL DEFAULT 'TEXT_MARKER';
ALTER TABLE loop_spec_compilation ADD COLUMN final_response_schema_id TEXT;
ALTER TABLE loop_spec_compilation ADD COLUMN final_format_fallback_used INTEGER NOT NULL DEFAULT 0;

ALTER TABLE judge_run ADD COLUMN response_mode TEXT NOT NULL DEFAULT 'TEXT_MARKER';
ALTER TABLE judge_run ADD COLUMN response_schema_id TEXT;

ALTER TABLE execution_session ADD COLUMN todo_capability TEXT NOT NULL DEFAULT 'UNKNOWN';
