ALTER TABLE design_work_package ADD COLUMN execution_strategy TEXT;
ALTER TABLE design_work_package ADD COLUMN test_policy TEXT;
ALTER TABLE design_work_package ADD COLUMN technologies_json TEXT NOT NULL DEFAULT '[]';

ALTER TABLE analysis_report ADD COLUMN external_session_id TEXT;
ALTER TABLE analysis_report ADD COLUMN external_session_state TEXT;
ALTER TABLE analysis_report ADD COLUMN source_requirement TEXT NOT NULL DEFAULT '';
ALTER TABLE analysis_report ADD COLUMN role_pack_id TEXT;
ALTER TABLE analysis_report ADD COLUMN role_pack_version TEXT;

CREATE INDEX idx_analysis_report_active
    ON analysis_report(state,updated_at)
    WHERE state IN ('RUNNING','VALIDATING');
