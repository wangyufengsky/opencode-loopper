ALTER TABLE analysis_report ADD COLUMN reviewer_contract_version TEXT;
ALTER TABLE analysis_report ADD COLUMN response_mode TEXT;
ALTER TABLE analysis_report ADD COLUMN findings_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE analysis_report ADD COLUMN deadline_at TEXT;
