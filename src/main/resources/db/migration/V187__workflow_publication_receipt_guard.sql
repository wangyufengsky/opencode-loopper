-- SQLite CHECK accepts NULL expressions. A committed state must carry concrete Git proof.
CREATE TRIGGER trg_workflow_publication_commit_receipt BEFORE UPDATE ON workflow_publication
WHEN NEW.state='COMMITTED' AND (NEW.commit_sha IS NULL OR length(NEW.commit_sha) NOT IN (40,64)
    OR NEW.commit_sha GLOB '*[^0-9a-f]*')
BEGIN SELECT RAISE(ABORT,'workflow publication commit receipt required'); END;
