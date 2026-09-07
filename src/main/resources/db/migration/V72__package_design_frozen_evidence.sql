-- Existing runs keep their original workflow/policy; there is intentionally no historical backfill.
CREATE TABLE package_design_evidence (
    run_id TEXT PRIMARY KEY REFERENCES ai_candidate_submission_run(id),
    policy_version TEXT NOT NULL CHECK(policy_version='PACKAGE_GAP_ASSESSMENT_V1'),
    requirement_sha256 TEXT NOT NULL CHECK(length(requirement_sha256)=64),
    snapshot_json TEXT NOT NULL CHECK(json_valid(snapshot_json) AND length(CAST(snapshot_json AS BLOB))<=131072),
    snapshot_sha256 TEXT NOT NULL CHECK(length(snapshot_sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TRIGGER package_design_evidence_no_update
BEFORE UPDATE ON package_design_evidence
BEGIN SELECT RAISE(ABORT,'package design evidence is immutable'); END;
CREATE TRIGGER package_design_evidence_no_delete
BEFORE DELETE ON package_design_evidence
BEGIN SELECT RAISE(ABORT,'package design evidence is immutable'); END;
