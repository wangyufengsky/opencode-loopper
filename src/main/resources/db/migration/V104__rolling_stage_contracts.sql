CREATE TABLE task_stage_contract (
    stage_id TEXT PRIMARY KEY REFERENCES stage(id) ON DELETE CASCADE,
    spec_revision_id TEXT NOT NULL REFERENCES task_spec_revision(id) ON DELETE CASCADE,
    stage_index INTEGER NOT NULL CHECK(stage_index>=0)
);
-- Physical stage ordinals include superseded attempts; a spec revision contains only its effective plan.
INSERT INTO task_stage_contract(stage_id,spec_revision_id,stage_index)
SELECT s.id,r.id,json_array_length(r.spec_json,'$.stages')
    -(SELECT count(*) FROM stage p WHERE p.package_run_id=s.package_run_id AND p.ordinal>=s.ordinal)
FROM stage s JOIN task_spec_revision r ON r.package_run_id=s.package_run_id
WHERE r.revision=(SELECT min(old.revision) FROM task_spec_revision old WHERE old.package_run_id=s.package_run_id)
  AND json_array_length(r.spec_json,'$.stages')>=(SELECT count(*) FROM stage p WHERE p.package_run_id=s.package_run_id);
