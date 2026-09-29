-- Reuse contexts are fixed before creating a Session; receipts are not model reading credentials.
CREATE TABLE workflow_snapshot_reuse_context (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_snapshot_work_input(attempt_id),
    fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64),
    input_sha256 TEXT NOT NULL CHECK(length(input_sha256)=64),
    created_at TEXT NOT NULL
);
CREATE INDEX idx_workflow_snapshot_reuse_fingerprint ON workflow_snapshot_reuse_context(fingerprint,created_at,attempt_id);
CREATE TRIGGER trg_snapshot_reuse_context_owner BEFORE INSERT ON workflow_snapshot_reuse_context
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id JOIN workflow_model_launch m ON m.attempt_id=a.id
    JOIN workflow_snapshot_work_input i ON i.attempt_id=a.id
    WHERE a.id=NEW.attempt_id AND a.id=n.latest_attempt_id AND a.ordinal=1 AND a.state='PREPARING' AND n.state='ACTIVE'
      AND a.adapter_key='model.readonly.v1' AND a.external_session_id IS NULL AND a.role_snapshot_json IS NOT NULL
      AND m.state='PREPARING' AND m.suspended=0 AND m.creation_plan_json IS NULL AND m.requirement_id=r.id
      AND r.state IN ('RUNNING','PAUSED','STALLED') AND i.sha256=NEW.input_sha256
      AND json_extract(n.definition_json,'$.kind')='WORK' AND json_extract(n.definition_json,'$.moduleId')='snapshot.analyze'
      AND json_extract(n.definition_json,'$.moduleVersion')=1
      AND coalesce(json_extract(n.definition_json,'$.parameters.snapshotReuse'),'ALLOW')='ALLOW')
BEGIN SELECT RAISE(ABORT,'snapshot reuse context owner mismatch'); END;
CREATE TRIGGER trg_snapshot_reuse_context_immutable BEFORE UPDATE ON workflow_snapshot_reuse_context
BEGIN SELECT RAISE(ABORT,'snapshot reuse context is immutable'); END;
CREATE TRIGGER trg_snapshot_reuse_context_retained BEFORE DELETE ON workflow_snapshot_reuse_context
BEGIN SELECT RAISE(ABORT,'snapshot reuse context must be retained'); END;

CREATE TABLE workflow_snapshot_reuse (
    attempt_id TEXT PRIMARY KEY REFERENCES workflow_snapshot_reuse_context(attempt_id),
    source_attempt_id TEXT NOT NULL REFERENCES workflow_snapshot_reuse_context(attempt_id),
    source_requirement_id TEXT NOT NULL REFERENCES workflow_requirement(id),
    source_title TEXT NOT NULL,
    source_node_title TEXT NOT NULL,
    fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64),
    source_delivery_sha256 TEXT NOT NULL CHECK(length(source_delivery_sha256)=64),
    claims_json TEXT NOT NULL CHECK(json_valid(claims_json) AND length(CAST(claims_json AS BLOB))<=600000
        AND json_type(claims_json,'$.coverage')='array' AND json_array_length(claims_json,'$.coverage') BETWEEN 1 AND 64
        AND json_array_length(claims_json,'$.findings')=0 AND json_array_length(claims_json,'$.supplements')=0
        AND json_array_length(claims_json,'$.limitations')=0),
    claims_sha256 TEXT NOT NULL CHECK(length(claims_sha256)=64),
    created_at TEXT NOT NULL
);
CREATE TRIGGER trg_snapshot_reuse_owner BEFORE INSERT ON workflow_snapshot_reuse
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    JOIN workflow_requirement r ON r.id=n.requirement_id JOIN workflow_model_launch m ON m.attempt_id=a.id
    JOIN workflow_snapshot_reuse_context c ON c.attempt_id=a.id JOIN workflow_snapshot_work_input i ON i.attempt_id=a.id
    JOIN workflow_snapshot_reuse_context sc ON sc.attempt_id=NEW.source_attempt_id
    JOIN workflow_node_attempt sa ON sa.id=sc.attempt_id JOIN workflow_node_run sn ON sn.id=sa.node_run_id
    JOIN workflow_requirement sr ON sr.id=sn.requirement_id
    JOIN workflow_plan_node sp ON sp.requirement_id=sr.id AND sp.plan_revision=sr.head_revision AND sp.node_run_id=sn.id
    JOIN workflow_model_launch sm ON sm.attempt_id=sa.id JOIN workflow_attempt_stop ss ON ss.attempt_id=sa.id
    JOIN workflow_node_delivery sd ON sd.attempt_id=sa.id JOIN workflow_snapshot_work_input si ON si.attempt_id=sa.id
    WHERE a.id=NEW.attempt_id AND a.id=n.latest_attempt_id AND a.ordinal=1 AND a.state='PREPARING' AND n.state='ACTIVE'
      AND a.external_session_id IS NULL AND m.state='PREPARING' AND m.creation_plan_json IS NULL AND m.suspended=0
      AND r.state IN ('RUNNING','PAUSED','STALLED') AND r.id<>sr.id AND r.project_id=sr.project_id
      AND c.fingerprint=NEW.fingerprint AND sc.fingerprint=NEW.fingerprint AND c.input_sha256=i.sha256 AND sc.input_sha256=si.sha256
      AND sa.id=sn.latest_attempt_id AND sa.state='SUCCEEDED' AND sn.state='SUCCEEDED' AND sm.state='SUCCEEDED'
      AND sa.external_session_id IS NOT NULL AND ss.external_session_id=sa.external_session_id AND ss.kind='SESSION_TERMINAL'
      AND sa.adapter_key=a.adapter_key AND a.adapter_key='model.readonly.v1' AND a.role_snapshot_json=sa.role_snapshot_json
      AND m.model_json=sm.model_json AND sd.outcome='NO_FINDINGS' AND sd.sha256=NEW.source_delivery_sha256
      AND sr.id=NEW.source_requirement_id AND sr.title=NEW.source_title AND json_extract(sn.definition_json,'$.title')=NEW.source_node_title
      AND json_extract(n.definition_json,'$.kind')='WORK' AND json_extract(n.definition_json,'$.moduleId')='snapshot.analyze'
      AND json_extract(n.definition_json,'$.moduleVersion')=1
      AND coalesce(json_extract(n.definition_json,'$.parameters.snapshotReuse'),'ALLOW')='ALLOW'
      AND NOT EXISTS(SELECT 1 FROM workflow_node_attempt bad JOIN workflow_node_run bn ON bn.id=bad.node_run_id
          WHERE bn.requirement_id=r.id AND bad.state IN ('FAILED','CANCELLED')))
    OR EXISTS(SELECT 1 FROM json_each(NEW.claims_json,'$.coverage') c
        WHERE json_array_length(c.value,'$.evidence')<>0 OR json_array_length(c.value,'$.limitations')<>0)
BEGIN SELECT RAISE(ABORT,'snapshot reuse provenance mismatch'); END;
CREATE TRIGGER trg_snapshot_reuse_immutable BEFORE UPDATE ON workflow_snapshot_reuse
BEGIN SELECT RAISE(ABORT,'snapshot reuse receipt is immutable'); END;
CREATE TRIGGER trg_snapshot_reuse_retained BEFORE DELETE ON workflow_snapshot_reuse
BEGIN SELECT RAISE(ABORT,'snapshot reuse receipt must be retained'); END;
CREATE TRIGGER trg_snapshot_reuse_no_session BEFORE UPDATE OF creation_plan_json ON workflow_model_launch
WHEN NEW.creation_plan_json IS NOT NULL AND EXISTS(SELECT 1 FROM workflow_snapshot_reuse x WHERE x.attempt_id=NEW.attempt_id)
BEGIN SELECT RAISE(ABORT,'reused work cannot create a model Session'); END;
CREATE TRIGGER trg_snapshot_reuse_no_attachment BEFORE UPDATE OF external_session_id ON workflow_node_attempt
WHEN NEW.external_session_id IS NOT NULL AND EXISTS(SELECT 1 FROM workflow_snapshot_reuse x WHERE x.attempt_id=NEW.id)
BEGIN SELECT RAISE(ABORT,'reused work cannot attach a model Session'); END;
CREATE TRIGGER trg_snapshot_reuse_model_success BEFORE UPDATE OF state ON workflow_model_launch
WHEN OLD.state='PREPARING' AND NEW.state='SUCCEEDED' AND NOT EXISTS(
    SELECT 1 FROM workflow_snapshot_reuse x JOIN workflow_attempt_stop s ON s.attempt_id=x.attempt_id
    WHERE x.attempt_id=NEW.attempt_id AND s.kind='NO_SESSION_CREATED' AND NEW.creation_plan_json IS NULL)
BEGIN SELECT RAISE(ABORT,'model preparation success requires accepted reuse proof'); END;

DROP TRIGGER trg_workflow_stop_owner;
CREATE TRIGGER trg_workflow_stop_owner BEFORE INSERT ON workflow_attempt_stop
WHEN NOT EXISTS(SELECT 1 FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id
    WHERE a.id=NEW.attempt_id AND a.external_session_id IS NEW.external_session_id
    AND (a.adapter_key<>'system.verify.command.v1' OR NEW.kind IN ('COMMAND_TERMINAL','COMMAND_NOT_LAUNCHED'))
    AND (NEW.kind<>'NO_EXTERNAL_WORK' OR a.adapter_key='human.v1'
        OR (a.adapter_key='system.verify.files.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.verify.files'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.snapshot.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.snapshot'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.test-profile.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.test-profile'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.test-plan.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.test-plan'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.test-summary.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.test-summary'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.design-plan.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.design-plan'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.source.design-document.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.source.design-document'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.document.review-plan.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.document.review-plan'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.document.review-report.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.document.review-report'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.history.plan.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.history.plan'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.history.report.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.history.report'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.snapshot.plan.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.snapshot.plan'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.snapshot.report.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.snapshot.report'
            AND json_extract(n.definition_json,'$.moduleVersion')=1)
        OR (a.adapter_key='system.review.dual.v1' AND a.role_snapshot_json IS NULL
            AND json_extract(n.definition_json,'$.kind')='SYSTEM'
            AND json_extract(n.definition_json,'$.moduleId')='system.review.dual'
            AND json_extract(n.definition_json,'$.moduleVersion')=1))
    AND (NEW.kind<>'NO_SESSION_CREATED' OR EXISTS(SELECT 1 FROM workflow_model_launch l
        WHERE l.attempt_id=a.id AND l.creation_plan_json IS NULL AND (l.state='STOPPING'
            OR (l.state='PREPARING' AND a.state='RUNNING' AND a.adapter_key='model.readonly.v1'
                AND json_extract(n.definition_json,'$.moduleId')='snapshot.analyze' AND json_extract(n.definition_json,'$.moduleVersion')=1
                AND EXISTS(SELECT 1 FROM workflow_snapshot_reuse x WHERE x.attempt_id=a.id
                    AND json_extract(NEW.evidence_json,'$.mode')='SNAPSHOT_REUSE'
                    AND json_extract(NEW.evidence_json,'$.sourceAttempt')=x.source_attempt_id
                    AND json_extract(NEW.evidence_json,'$.sourceDeliverySha256')=x.source_delivery_sha256
                    AND json_extract(NEW.evidence_json,'$.fingerprint')=x.fingerprint)))))
    AND (NEW.kind NOT IN ('SESSION_ABSENT','CREATION_STOP_CONFIRMED') OR EXISTS(SELECT 1 FROM workflow_model_launch l
        WHERE l.attempt_id=a.id AND l.creation_plan_json IS NOT NULL AND l.state='STOPPING'))
    AND (NEW.kind NOT IN ('COMMAND_TERMINAL','COMMAND_NOT_LAUNCHED') OR EXISTS(SELECT 1 FROM workflow_command_run c
        WHERE c.attempt_id=a.id AND a.adapter_key='system.verify.command.v1' AND a.role_snapshot_json IS NULL
        AND ((NEW.kind='COMMAND_TERMINAL' AND c.result_json IS NOT NULL
            AND json_extract(c.result_json,'$.stopConfirmed')=1
            AND json_extract(NEW.evidence_json,'$.requestSha256')=c.request_sha256
            AND json_extract(NEW.evidence_json,'$.resultSha256')=c.result_sha256)
        OR (NEW.kind='COMMAND_NOT_LAUNCHED' AND c.state='STOPPING' AND c.request_json IS NULL AND c.registration_json IS NULL)))))
BEGIN SELECT RAISE(ABORT,'workflow stop identity mismatch'); END;
