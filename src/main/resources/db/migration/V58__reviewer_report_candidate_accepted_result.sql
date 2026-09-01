-- REVIEWER_REPORT_V1 persists only immutable source-manifest metadata and the
-- deterministic compiler output. Source contents, path authority, owner state
-- and protocol identifiers remain server-owned.
CREATE TABLE reviewer_report_candidate_source_snapshot (
    -- The deterministic run ID is reserved before any remote I/O. This row
    -- intentionally has no FK to the not-yet-created candidate run.
    candidate_run_id TEXT PRIMARY KEY CHECK (length(candidate_run_id) > 0),
    analysis_report_id TEXT NOT NULL
        REFERENCES analysis_report(id) ON DELETE CASCADE,
    source_revision INTEGER NOT NULL CHECK (source_revision >= 0),
    prepared_owner_version INTEGER NOT NULL CHECK (prepared_owner_version >= 0),
    contract_version TEXT NOT NULL CHECK (contract_version = 'REVIEWER_REPORT_V1'),
    canonical_source_manifest_json TEXT NOT NULL CHECK (
        length(canonical_source_manifest_json) BETWEEN 2 AND 4194304
        AND json_valid(canonical_source_manifest_json)
        AND json_type(canonical_source_manifest_json) = 'array'),
    source_manifest_sha256 TEXT NOT NULL CHECK (length(source_manifest_sha256) = 64),
    created_at TEXT NOT NULL
);

CREATE INDEX idx_reviewer_report_source_snapshot_owner
    ON reviewer_report_candidate_source_snapshot(
        analysis_report_id,source_revision,prepared_owner_version,created_at);

CREATE TABLE reviewer_report_candidate_accepted_result (
    candidate_run_id TEXT PRIMARY KEY
        REFERENCES ai_candidate_submission_run(id) ON DELETE CASCADE,
    analysis_report_id TEXT NOT NULL
        REFERENCES analysis_report(id) ON DELETE CASCADE,
    source_revision INTEGER NOT NULL CHECK (source_revision >= 0),
    owner_version INTEGER NOT NULL CHECK (owner_version >= 0),
    contract_version TEXT NOT NULL CHECK (contract_version = 'REVIEWER_REPORT_V1'),
    canonical_candidate_json TEXT NOT NULL CHECK (
        length(canonical_candidate_json) > 0
        AND json_valid(canonical_candidate_json)
        AND json_type(canonical_candidate_json) = 'object'),
    canonical_findings_json TEXT NOT NULL CHECK (
        length(canonical_findings_json) > 0
        AND json_valid(canonical_findings_json)
        AND json_type(canonical_findings_json) = 'array'),
    markdown TEXT NOT NULL CHECK (length(markdown) BETWEEN 1 AND 65536),
    evidence_json TEXT NOT NULL CHECK (
        length(evidence_json) > 0
        AND json_valid(evidence_json)
        AND json_type(evidence_json) = 'array'),
    content_sha256 TEXT NOT NULL CHECK (length(content_sha256) = 64),
    source_snapshot_sha256 TEXT NOT NULL CHECK (length(source_snapshot_sha256) = 64),
    candidate_payload_sha256 TEXT NOT NULL CHECK (length(candidate_payload_sha256) = 64),
    canonical_result_sha256 TEXT NOT NULL CHECK (length(canonical_result_sha256) = 64),
    settled_analysis_report_id TEXT
        REFERENCES analysis_report(id) ON DELETE RESTRICT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE(analysis_report_id,source_revision,owner_version),
    FOREIGN KEY(candidate_run_id)
        REFERENCES reviewer_report_candidate_source_snapshot(candidate_run_id) ON DELETE CASCADE
);

CREATE INDEX idx_reviewer_report_candidate_result_unsettled
    ON reviewer_report_candidate_accepted_result(
        settled_analysis_report_id,created_at,candidate_run_id);

CREATE TRIGGER trg_reviewer_report_source_snapshot_insert
BEFORE INSERT ON reviewer_report_candidate_source_snapshot
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM analysis_report owner
        WHERE owner.id=NEW.analysis_report_id
          AND owner.state='RUNNING'
          AND owner.external_session_id IS NULL
          AND owner.version=NEW.prepared_owner_version
          AND owner.reviewer_contract_version=NEW.contract_version)
        THEN RAISE(ABORT,'Reviewer source snapshot owner mismatch') END;
END;

CREATE TRIGGER trg_reviewer_report_source_snapshot_update
BEFORE UPDATE ON reviewer_report_candidate_source_snapshot
BEGIN
    SELECT RAISE(ABORT,'Reviewer source snapshot is immutable');
END;

CREATE TRIGGER trg_reviewer_report_candidate_run_snapshot_gate
BEFORE INSERT ON ai_candidate_submission_run
WHEN NEW.candidate_kind='REVIEWER_REPORT_V1'
  AND NEW.submission_channel='INTERNAL_MCP'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM reviewer_report_candidate_source_snapshot snapshot
        JOIN analysis_report owner ON owner.id=snapshot.analysis_report_id
        WHERE snapshot.candidate_run_id=NEW.id
          AND snapshot.analysis_report_id=NEW.owner_id
          AND snapshot.source_revision=NEW.source_revision
          AND snapshot.prepared_owner_version+1=NEW.owner_version
          AND snapshot.contract_version=NEW.contract_version
          AND owner.designer_session_id=NEW.designer_session_id
          AND NEW.task_id IS NULL AND NEW.project_id IS NULL
          AND NEW.owner_type='ANALYSIS_REPORT'
          AND NEW.workflow_step='REVIEWER_REPORT_V1'
          AND NEW.contract_version='REVIEWER_REPORT_V1'
          AND NEW.max_attempts=3)
        THEN RAISE(ABORT,'Reviewer candidate run requires exact frozen source snapshot') END;
END;

CREATE TRIGGER trg_reviewer_report_initial_prompt_snapshot_gate
BEFORE INSERT ON ai_candidate_prompt_dispatch
WHEN NEW.dispatch_kind='INITIAL' AND EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    WHERE run.id=NEW.run_id AND run.candidate_kind='REVIEWER_REPORT_V1')
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM ai_candidate_submission_run run
        JOIN reviewer_report_candidate_source_snapshot snapshot
          ON snapshot.candidate_run_id=run.id
        WHERE run.id=NEW.run_id
          AND snapshot.analysis_report_id=run.owner_id
          AND snapshot.source_revision=run.source_revision
          AND snapshot.prepared_owner_version+1=run.owner_version
          AND snapshot.contract_version=run.contract_version)
        THEN RAISE(ABORT,'Reviewer initial prompt requires exact frozen source snapshot') END;
END;

CREATE TRIGGER trg_reviewer_report_accepted_result_insert
BEFORE INSERT ON reviewer_report_candidate_accepted_result
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM ai_candidate_submission_run run
            JOIN reviewer_report_candidate_source_snapshot snapshot
              ON snapshot.candidate_run_id=run.id
            WHERE run.id=NEW.candidate_run_id
              AND run.candidate_kind='REVIEWER_REPORT_V1'
              AND run.workflow_step='REVIEWER_REPORT_V1'
              AND run.owner_type='ANALYSIS_REPORT'
              AND run.owner_id=NEW.analysis_report_id
              AND run.source_revision=NEW.source_revision
              AND run.owner_version=NEW.owner_version
              AND run.contract_version=NEW.contract_version
              AND run.max_attempts=3
              AND run.state IN ('OPEN','ACCEPTED')
              AND snapshot.analysis_report_id=NEW.analysis_report_id
              AND snapshot.source_revision=NEW.source_revision
              AND snapshot.prepared_owner_version+1=NEW.owner_version
              AND snapshot.contract_version=NEW.contract_version)
            THEN RAISE(ABORT,'Reviewer accepted result run mismatch')
        WHEN NEW.settled_analysis_report_id IS NOT NULL
             AND NEW.settled_analysis_report_id<>NEW.analysis_report_id
            THEN RAISE(ABORT,'Reviewer accepted result settlement owner mismatch')
    END;
END;

CREATE TRIGGER trg_reviewer_report_candidate_run_snapshot_cleanup
AFTER DELETE ON ai_candidate_submission_run
WHEN OLD.candidate_kind='REVIEWER_REPORT_V1'
BEGIN
    DELETE FROM reviewer_report_candidate_source_snapshot
    WHERE candidate_run_id=OLD.id;
END;

CREATE TRIGGER trg_reviewer_report_accepted_result_update
BEFORE UPDATE ON reviewer_report_candidate_accepted_result
BEGIN
    SELECT CASE
        WHEN NEW.candidate_run_id IS NOT OLD.candidate_run_id
          OR NEW.analysis_report_id IS NOT OLD.analysis_report_id
          OR NEW.source_revision IS NOT OLD.source_revision
          OR NEW.owner_version IS NOT OLD.owner_version
          OR NEW.contract_version IS NOT OLD.contract_version
          OR NEW.canonical_candidate_json IS NOT OLD.canonical_candidate_json
          OR NEW.canonical_findings_json IS NOT OLD.canonical_findings_json
          OR NEW.markdown IS NOT OLD.markdown
          OR NEW.evidence_json IS NOT OLD.evidence_json
          OR NEW.content_sha256 IS NOT OLD.content_sha256
          OR NEW.source_snapshot_sha256 IS NOT OLD.source_snapshot_sha256
          OR NEW.candidate_payload_sha256 IS NOT OLD.candidate_payload_sha256
          OR NEW.canonical_result_sha256 IS NOT OLD.canonical_result_sha256
          OR NEW.created_at IS NOT OLD.created_at
            THEN RAISE(ABORT,'Reviewer accepted result payload is immutable')
        WHEN OLD.settled_analysis_report_id IS NOT NULL
             AND NEW.settled_analysis_report_id IS NOT OLD.settled_analysis_report_id
            THEN RAISE(ABORT,'Reviewer accepted result settlement is irreversible')
        WHEN NEW.settled_analysis_report_id IS NOT NULL
             AND NEW.settled_analysis_report_id<>NEW.analysis_report_id
            THEN RAISE(ABORT,'Reviewer accepted result settlement owner mismatch')
        WHEN OLD.settled_analysis_report_id IS NULL
             AND NEW.settled_analysis_report_id IS NOT NULL
             AND NEW.version<>OLD.version+1
            THEN RAISE(ABORT,'Reviewer accepted result settlement version mismatch')
        WHEN OLD.settled_analysis_report_id IS NULL
             AND NEW.settled_analysis_report_id IS NOT NULL
             AND NOT EXISTS (
                 SELECT 1 FROM ai_candidate_submission_run run
                 WHERE run.id=NEW.candidate_run_id AND run.state='ACCEPTED')
            THEN RAISE(ABORT,'Reviewer accepted result settlement requires accepted run')
        WHEN NEW.settled_analysis_report_id IS OLD.settled_analysis_report_id
             AND (NEW.updated_at IS NOT OLD.updated_at OR NEW.version<>OLD.version)
            THEN RAISE(ABORT,'Reviewer accepted result only settlement may change')
    END;
END;
