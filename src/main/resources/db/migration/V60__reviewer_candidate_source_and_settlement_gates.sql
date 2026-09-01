ALTER TABLE analysis_report
  ADD COLUMN source_requirement_revision INTEGER
    CHECK (source_requirement_revision IS NULL OR source_requirement_revision>=0);

CREATE TRIGGER trg_reviewer_report_source_revision_anchor
BEFORE INSERT ON reviewer_report_candidate_source_snapshot
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM analysis_report owner
    WHERE owner.id=NEW.analysis_report_id
      AND owner.source_requirement_revision=NEW.source_revision
      AND owner.source_requirement=trim(owner.source_requirement)
      AND length(owner.source_requirement)>0)
    THEN RAISE(ABORT,'Reviewer source snapshot requirement revision mismatch') END;
END;

CREATE TRIGGER trg_reviewer_candidate_launch_source_revision_anchor
BEFORE INSERT ON ai_candidate_internal_launch
WHEN NEW.candidate_kind='REVIEWER_REPORT_V1'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM analysis_report owner
    WHERE owner.id=NEW.analysis_report_id
      AND owner.designer_session_id=NEW.designer_session_id
      AND owner.source_requirement_revision=NEW.source_revision)
    THEN RAISE(ABORT,'Reviewer candidate launch requirement revision mismatch') END;
END;

CREATE TRIGGER trg_reviewer_candidate_initial_attempt_gate
BEFORE INSERT ON ai_candidate_submission_attempt
WHEN EXISTS (
  SELECT 1 FROM ai_candidate_submission_run run
  WHERE run.id=NEW.run_id
    AND run.candidate_kind='REVIEWER_REPORT_V1'
    AND run.submission_channel='INTERNAL_MCP')
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM ai_candidate_submission_run run
    JOIN ai_candidate_internal_launch launch ON launch.candidate_run_id=run.id
    JOIN ai_candidate_prompt_dispatch dispatch ON dispatch.run_id=run.id
    WHERE run.id=NEW.run_id
      AND dispatch.dispatch_kind='INITIAL'
      AND dispatch.candidate_launch_id=launch.id
      AND dispatch.external_session_id=run.external_session_id
      AND dispatch.runtime_generation_id=run.runtime_generation_id
      AND dispatch.state='ACKNOWLEDGED'
      AND dispatch.acknowledged=1)
    THEN RAISE(ABORT,'Reviewer candidate submission requires acknowledged initial prompt') END;
END;

CREATE TRIGGER trg_reviewer_report_candidate_settlement_owner_gate
BEFORE UPDATE OF settled_analysis_report_id ON reviewer_report_candidate_accepted_result
WHEN OLD.settled_analysis_report_id IS NULL
  AND NEW.settled_analysis_report_id IS NOT NULL
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM analysis_report owner
    JOIN ai_candidate_submission_run run ON run.id=NEW.candidate_run_id
    JOIN ai_candidate_internal_launch launch ON launch.candidate_run_id=run.id
    WHERE owner.id=NEW.analysis_report_id
      AND owner.id=NEW.settled_analysis_report_id
      AND owner.state='READY'
      AND owner.designer_session_id=run.designer_session_id
      AND owner.external_session_id=run.external_session_id
      AND owner.external_session_state IN ('REMOTE_COMPLETED','ABORT_ACKNOWLEDGED','ALREADY_ABSENT')
      AND owner.source_requirement_revision=NEW.source_revision
      AND owner.reviewer_contract_version=NEW.contract_version
      AND owner.markdown=NEW.markdown
      AND owner.findings_json=NEW.canonical_findings_json
      AND owner.evidence_json=NEW.evidence_json
      AND owner.content_sha256=NEW.content_sha256
      AND owner.source_snapshot_sha256=NEW.source_snapshot_sha256
      AND run.state='ACCEPTED'
      AND launch.analysis_report_id=owner.id
      AND launch.termination_proof=owner.external_session_state
      AND launch.state IN ('COMPLETED','FAILED_STOPPED','CANCELLED','STALE'))
    THEN RAISE(ABORT,'Reviewer accepted result requires exact proven READY owner') END;
END;
