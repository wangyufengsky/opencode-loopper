-- Explicit local confirmation freezes one result and commit identity; recovery never reselects live files.
CREATE TABLE workflow_publication (
    id TEXT PRIMARY KEY,
    requirement_id TEXT NOT NULL UNIQUE REFERENCES workflow_requirement(id),
    project_id TEXT NOT NULL REFERENCES project(id),
    request_key TEXT NOT NULL UNIQUE,
    request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
    intent_json TEXT NOT NULL CHECK(json_valid(intent_json)),
    intent_sha256 TEXT NOT NULL CHECK(length(intent_sha256)=64),
    state TEXT NOT NULL CHECK(state IN ('CONFIRMED','BLOCKED','COMMITTED')),
    version INTEGER NOT NULL CHECK(version>=0),
    commit_sha TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK((state='COMMITTED' AND length(commit_sha) IN (40,64)) OR (state<>'COMMITTED' AND commit_sha IS NULL))
);
CREATE INDEX idx_workflow_publication_pending ON workflow_publication(requirement_id) WHERE state='CONFIRMED';
CREATE TRIGGER trg_workflow_publication_owner BEFORE INSERT ON workflow_publication
WHEN NEW.state<>'CONFIRMED' OR NEW.version<>0 OR NOT EXISTS(
    SELECT 1 FROM workflow_requirement r
    JOIN workflow_plan_node p ON p.requirement_id=r.id AND p.plan_revision=r.head_revision
    JOIN workflow_node_run n ON n.id=p.node_run_id AND n.node_key=json_extract(NEW.intent_json,'$.preview.source.nodeKey')
    JOIN workflow_node_attempt a ON a.id=n.latest_attempt_id
    JOIN workflow_attempt_stop stop ON stop.attempt_id=a.id
    JOIN workflow_node_delivery d ON d.attempt_id=a.id
    JOIN json_each(d.content_json,'$.outputs') output
    JOIN workflow_code_snapshot c ON c.attempt_id=a.id
    JOIN workflow_code_manifest m ON m.snapshot_id=c.id
    JOIN workflow_workspace w ON w.attempt_id=a.id
    WHERE r.id=NEW.requirement_id AND r.project_id=NEW.project_id AND r.state='COMPLETED'
      AND r.head_revision=json_extract(NEW.intent_json,'$.preview.planRevision')
      AND r.version=json_extract(NEW.intent_json,'$.preview.requirementVersion')
      AND a.state IN ('SUCCEEDED','FAILED') AND a.adapter_key='model.write.v1'
      AND a.id=json_extract(NEW.intent_json,'$.preview.source.attemptId')
      AND output.key=json_extract(NEW.intent_json,'$.preview.source.outputName')
      AND json_extract(output.value,'$.kind')='CODE'
      AND json_extract(output.value,'$.content.snapshotId')=c.id
      AND json_extract(output.value,'$.content.sha256')=m.sha256
      AND c.id=json_extract(NEW.intent_json,'$.preview.reference.snapshotId')
      AND m.sha256=json_extract(NEW.intent_json,'$.preview.reference.sha256')
      AND w.state='RELEASED' AND w.object_repository IS NULL AND c.object_repository IS NULL
      AND w.source_commit=json_extract(NEW.intent_json,'$.commit.parent')
      AND c.result_tree=json_extract(NEW.intent_json,'$.commit.tree')
)
BEGIN SELECT RAISE(ABORT,'workflow publication scope mismatch'); END;
CREATE TRIGGER trg_workflow_publication_update BEFORE UPDATE ON workflow_publication
WHEN NEW.id<>OLD.id OR NEW.requirement_id<>OLD.requirement_id OR NEW.project_id<>OLD.project_id
  OR NEW.request_key<>OLD.request_key OR NEW.request_sha256<>OLD.request_sha256
  OR NEW.intent_json<>OLD.intent_json OR NEW.intent_sha256<>OLD.intent_sha256 OR NEW.created_at<>OLD.created_at
  OR NEW.version<>OLD.version+1
  OR NOT ((OLD.state='CONFIRMED' AND NEW.state IN ('BLOCKED','COMMITTED')) OR (OLD.state='BLOCKED' AND NEW.state='CONFIRMED'))
BEGIN SELECT RAISE(ABORT,'workflow publication intent is immutable'); END;
CREATE TRIGGER trg_workflow_publication_no_delete BEFORE DELETE ON workflow_publication
BEGIN SELECT RAISE(ABORT,'workflow publication history is immutable'); END;
