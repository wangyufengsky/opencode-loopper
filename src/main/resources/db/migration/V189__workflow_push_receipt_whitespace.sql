-- The supervised helper emits a line terminator; preserve V188 and validate the same bounded marker.
DROP TRIGGER trg_workflow_push_receipt;
CREATE TRIGGER trg_workflow_push_receipt BEFORE UPDATE ON workflow_push
WHEN NEW.state='PUSHED' AND NOT EXISTS(
    SELECT 1 FROM workflow_push_attempt a WHERE a.id=NEW.active_attempt_id AND a.push_id=NEW.id
      AND json_extract(a.result_json,'$.stopConfirmed')=1 AND json_extract(a.result_json,'$.launched')=1
      AND json_extract(a.result_json,'$.exitCode')=0 AND json_extract(a.result_json,'$.cancelled')=0
      AND json_extract(a.result_json,'$.timedOut')=0 AND json_extract(a.result_json,'$.outputTruncated')=0
      AND json_extract(a.result_json,'$.error')='' AND trim(json_extract(a.result_json,'$.output'),char(9)||char(10)||char(13)||' ')='LOOPPER_GIT_PUSH_CONFIRMED')
BEGIN SELECT RAISE(ABORT,'workflow push proof required'); END;
