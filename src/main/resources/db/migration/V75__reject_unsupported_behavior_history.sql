-- Retain V74's checksum and evidence. Never reinterpret an experimental contract as ordinary V2.
-- Empty V74 databases (the default configuration) remain upgradeable after the code rollback.
CREATE TABLE package_behavior_rollback_check (
    compatible INTEGER NOT NULL CONSTRAINT PACKAGE_BEHAVIOR_HISTORY_REQUIRES_0_3_79 CHECK(compatible=1)
);
INSERT INTO package_behavior_rollback_check
SELECT CASE WHEN EXISTS (SELECT 1 FROM package_behavior_policy)
    OR EXISTS (SELECT 1 FROM package_behavior_preparation)
    OR EXISTS (SELECT 1 FROM package_behavior_run)
    OR EXISTS (SELECT 1 FROM designer_conversation WHERE scope_key LIKE 'BEHAVIOR_REVIEW:%')
    OR EXISTS (SELECT 1 FROM ai_candidate_submission_run WHERE workflow_step='PACKAGE_DESIGN_V2_BEHAVIOR_V1')
    THEN 0 ELSE 1 END;
DROP TABLE package_behavior_rollback_check;
