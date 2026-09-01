-- Rolling package plan candidates settle through an immutable canonical result
-- owned by the exact task package plan revision that opened the candidate run.
CREATE TABLE rolling_package_plan_candidate_accepted_result (
    candidate_run_id TEXT PRIMARY KEY
        REFERENCES ai_candidate_submission_run(id) ON DELETE CASCADE,
    task_package_plan_revision_id TEXT NOT NULL
        REFERENCES task_package_plan_revision(id) ON DELETE CASCADE,
    source_revision INTEGER NOT NULL CHECK (source_revision >= 0),
    owner_version INTEGER NOT NULL CHECK (owner_version >= 0),
    contract_version TEXT NOT NULL CHECK (length(contract_version) > 0),
    canonical_candidate_json TEXT NOT NULL CHECK (length(canonical_candidate_json) > 0),
    canonical_plan_json TEXT NOT NULL CHECK (length(canonical_plan_json) > 0),
    impact_json TEXT NOT NULL CHECK (length(impact_json) > 0),
    canonical_result_sha256 TEXT NOT NULL CHECK (length(canonical_result_sha256) = 64),
    settled_plan_revision_id TEXT
        REFERENCES task_package_plan_revision(id) ON DELETE RESTRICT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE(task_package_plan_revision_id,source_revision,owner_version)
);

CREATE INDEX idx_rolling_package_plan_result_unsettled
    ON rolling_package_plan_candidate_accepted_result(
        settled_plan_revision_id,created_at,candidate_run_id);
CREATE INDEX idx_rolling_package_plan_result_owner_latest
    ON rolling_package_plan_candidate_accepted_result(
        task_package_plan_revision_id,created_at DESC,candidate_run_id DESC);

-- The accepted-result writer and run writer share one transaction. The result
-- may therefore be inserted while the run is still OPEN, immediately before
-- that same transaction advances the run to ACCEPTED.
CREATE TRIGGER trg_rolling_package_plan_result_insert
BEFORE INSERT ON rolling_package_plan_candidate_accepted_result
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM ai_candidate_submission_run run
            WHERE run.id=NEW.candidate_run_id
              AND run.candidate_kind='ROLLING_PACKAGE_PLAN_V1'
              AND run.owner_type='TASK_PACKAGE_PLAN_REVISION'
              AND run.owner_id=NEW.task_package_plan_revision_id)
            THEN RAISE(ABORT,'rolling package plan accepted result run mismatch')
        WHEN NOT EXISTS (
            SELECT 1 FROM ai_candidate_submission_run run
            JOIN task_package_plan_revision owner
              ON owner.id=NEW.task_package_plan_revision_id
            WHERE run.id=NEW.candidate_run_id AND run.task_id=owner.task_id)
            THEN RAISE(ABORT,'rolling package plan accepted result task scope mismatch')
        WHEN NOT EXISTS (
            SELECT 1 FROM ai_candidate_submission_run run
            WHERE run.id=NEW.candidate_run_id
              AND run.source_revision=NEW.source_revision)
            THEN RAISE(ABORT,'rolling package plan accepted result source mismatch')
        WHEN NOT EXISTS (
            SELECT 1 FROM ai_candidate_submission_run run
            WHERE run.id=NEW.candidate_run_id
              AND run.owner_version=NEW.owner_version)
            THEN RAISE(ABORT,'rolling package plan accepted result owner version mismatch')
        WHEN NOT EXISTS (
            SELECT 1 FROM ai_candidate_submission_run run
            WHERE run.id=NEW.candidate_run_id
              AND run.contract_version=NEW.contract_version)
            THEN RAISE(ABORT,'rolling package plan accepted result contract mismatch')
        WHEN NOT EXISTS (
            SELECT 1 FROM ai_candidate_submission_run run
            WHERE run.id=NEW.candidate_run_id AND run.state IN ('OPEN','ACCEPTED'))
            THEN RAISE(ABORT,'rolling package plan accepted result run is not accepting a result')
        WHEN NEW.settled_plan_revision_id IS NOT NULL
             AND NEW.settled_plan_revision_id<>NEW.task_package_plan_revision_id
            THEN RAISE(ABORT,'rolling package plan accepted result settlement owner mismatch')
    END;
END;

CREATE TRIGGER trg_rolling_package_plan_result_update
BEFORE UPDATE ON rolling_package_plan_candidate_accepted_result
BEGIN
    SELECT CASE
        WHEN NEW.candidate_run_id IS NOT OLD.candidate_run_id
          OR NEW.task_package_plan_revision_id IS NOT OLD.task_package_plan_revision_id
          OR NEW.source_revision IS NOT OLD.source_revision
          OR NEW.owner_version IS NOT OLD.owner_version
          OR NEW.contract_version IS NOT OLD.contract_version
          OR NEW.canonical_candidate_json IS NOT OLD.canonical_candidate_json
          OR NEW.canonical_plan_json IS NOT OLD.canonical_plan_json
          OR NEW.impact_json IS NOT OLD.impact_json
          OR NEW.canonical_result_sha256 IS NOT OLD.canonical_result_sha256
          OR NEW.created_at IS NOT OLD.created_at
            THEN RAISE(ABORT,'rolling package plan accepted result payload is immutable')
        WHEN OLD.settled_plan_revision_id IS NOT NULL
             AND NEW.settled_plan_revision_id IS NOT OLD.settled_plan_revision_id
            THEN RAISE(ABORT,'rolling package plan accepted result settlement is irreversible')
        WHEN NEW.settled_plan_revision_id IS NOT NULL
             AND NEW.settled_plan_revision_id<>NEW.task_package_plan_revision_id
            THEN RAISE(ABORT,'rolling package plan accepted result settlement owner mismatch')
        WHEN OLD.settled_plan_revision_id IS NULL
             AND NEW.settled_plan_revision_id IS NOT NULL
             AND NEW.version<>OLD.version+1
            THEN RAISE(ABORT,'rolling package plan accepted result settlement version mismatch')
        WHEN NEW.settled_plan_revision_id IS OLD.settled_plan_revision_id
             AND (NEW.updated_at IS NOT OLD.updated_at OR NEW.version<>OLD.version)
            THEN RAISE(ABORT,'rolling package plan accepted result only settlement may change')
    END;
END;
